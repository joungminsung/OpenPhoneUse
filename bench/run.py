"""Runs OpenPhoneUse benchmark tasks on a device over adb and grades them.

Needs a debug build (it starts tasks with the DUMP-gated DEBUG_RUN broadcast), a signed-in model, and the
accessibility service on. Each task gets a new conversation; the runner waits for the assistant reply in
chat.db, accepts confirm cards when the task allows it, records the screen, and writes results.json and
report.md under bench/results/<timestamp>/.

    python bench/run.py                      # every task whose needs are met
    python bench/run.py settings-timeout fact-everest-k2
    python bench/run.py --category research --no-video
    python bench/run.py --suite v2 --model gpt-6.1-sol --no-video   # graded suite; then bench/score.py
"""
import argparse
import base64
import datetime as dt
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
ADB = os.environ.get("ADB", "adb")
PKG = "com.phoneuse"
SERVICE = "com.phoneuse/com.phoneuse.service.PhoneUseAccessibilityService"
TIMING = re.compile(r"step=(\d+) model=(\d+)ms(?: ttfb=(\d+)ms)?(?: out=(\d+)ms)?")
STALL_S = 300  # no agent log line for this long = stuck
HARD_CAP_S = 40 * 60  # last-resort guard only; not a measurement limit
SHOT_EVERY_S = 10
TOOLS = re.compile(r"\| (\S+) (\d+)ms settle=(\d+)ms(?:\(([^)]*)\))? read=(\d+)ms")


def adb(*args, check=False, binary=False, timeout=60):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    try:
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:  # a busy device is not a reason to lose the whole run
        if check:
            raise
        print(f"   adb {' '.join(args)[:60]} timed out after {timeout}s")
        return b"" if binary else ""
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout).strip()


CHROME_PORT = 9222
# Pages whose saved state would hand the next run a head start: 2048 keeps its board in localStorage.
# (Storage.clearDataForOrigin did not stick here, so the page clears itself.)
CHROME_CLEAR_URLS = ["https://play2048.co/"]


def reset_chrome():
    """Clears per-site game state and closes every Chrome tab through Chrome's DevTools socket, so each task (and
    each model) starts from the same browser. Without this, tabs pile up across a run (35 by the end, slowing
    rendering for whichever model runs later) and 2048 resumes the previous run's board."""
    import urllib.request
    import websocket
    base = f"http://localhost:{CHROME_PORT}"

    def get(path):
        with urllib.request.urlopen(base + path, timeout=15) as r:
            return json.load(r)

    def evaluate(page, expr):
        ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=15, suppress_origin=True)
        try:
            ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr}}))
            return json.loads(ws.recv()).get("result", {}).get("result", {}).get("value")
        finally:
            ws.close()

    shell("input keyevent WAKEUP; wm dismiss-keyguard")
    adb("forward", f"tcp:{CHROME_PORT}", "localabstract:chrome_devtools_remote")
    try:
        for url in CHROME_CLEAR_URLS:
            shell(f"am start -a android.intent.action.VIEW -d {url} com.android.chrome")
            cleared = False
            for _ in range(30):
                time.sleep(1)
                try:
                    page = next((p for p in get("/json") if p.get("type") == "page" and p["url"].startswith(url)), None)
                    if page and evaluate(page, "document.readyState") in ("interactive", "complete"):
                        evaluate(page, "localStorage.clear(); sessionStorage.clear(); 1")
                        cleared = True
                        break
                except Exception:
                    pass
            if not cleared:
                print(f"   chrome reset: could not clear {url}")
        for p in get("/json"):
            if p.get("type") == "page":
                urllib.request.urlopen(f"{base}/json/close/{p['id']}", timeout=15).close()
    except Exception as e:
        print(f"   chrome reset failed: {e}")
    shell("am force-stop com.android.chrome")
    shell("input keyevent HOME")


def read_db():
    data = adb("exec-out", "run-as", PKG, "cat", "databases/chat.db", binary=True)
    fd, path = tempfile.mkstemp(suffix=".db")
    os.write(fd, data)
    os.close(fd)
    return path


def query(sql, args=()):
    """Rows from a copy of the app's chat database; None when the copy was caught mid-write."""
    path = read_db()
    c = sqlite3.connect(path)
    try:
        return c.execute(sql, args).fetchall()
    except sqlite3.Error:
        return None
    finally:
        c.close()  # Windows cannot delete a file that is still open
        os.remove(path)


def last_message_id():
    rows = query("select max(id) from messages")
    return (rows[0][0] if rows else 0) or 0


def wait_idle(limit_s=90):
    """Until the newest message is the assistant's: a run stopped late must not hand its reply to the next task."""
    t0 = time.time()
    stops = 0
    while time.time() - t0 < limit_s:
        rows = query("select role from messages order by id desc limit 1")
        if rows is not None and (not rows or rows[0][0] == "ASSISTANT"):
            return True
        # A message left without a reply by a run that died (app reinstalled, crash) never gets one: once a few
        # stops changed nothing, there is no run to wait for.
        if stops >= 3:
            return True
        shell("am broadcast -a com.phoneuse.DEBUG_STOP")
        stops += 1
        time.sleep(3)
    return False


def reply_after(start_id):
    """The first assistant message after [start_id], or None while the run is still going."""
    rows = query(
        "select id, text, steps_json, created_at from messages where id > ? and role = 'ASSISTANT' order by id limit 1",
        (start_id,),
    )
    if not rows:
        return None  # still running, or caught mid-write
    row = rows[0]
    users = query("select created_at from messages where id > ? and role = 'USER' order by id limit 1", (start_id,))
    user = users[0] if users else None
    return {"text": row[1], "steps": json.loads(row[2] or "[]"), "finished_at": row[3], "started_at": user[0] if user else None}


def answer_confirm(decline=False):
    """Answers a waiting confirm card through the app; returns "where|CATEGORY|summary" or None when none waits.
    Never uiautomator here: while it is connected the system suppresses other accessibility services, which stalls
    the agent mid-run."""
    out = shell("am broadcast -a com.phoneuse.DEBUG_ANSWER" + (" --es mode no" if decline else ""), timeout=15)
    m = re.search(r'data="((?:overlay|chat)[^"]*)"', out)
    return m.group(1) if m else None


class Recorder(threading.Thread):
    """Records the screen in back-to-back parts (screenrecord stops at 3 minutes), then joins them into one MP4."""

    def __init__(self, tid):
        super().__init__(daemon=True)
        self.tid = tid
        self.parts = []
        self.done = threading.Event()
        self.proc = None

    def run(self):
        i = 0
        while not self.done.is_set():
            part = f"/data/local/tmp/bench_{self.tid}_{i}.mp4"
            self.parts.append(part)
            self.proc = subprocess.Popen([ADB, "shell", "screenrecord", "--bit-rate", "4000000", "--time-limit", "180", part],
                                         env=dict(os.environ, MSYS_NO_PATHCONV="1"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            began = time.time()
            self.proc.wait()
            i += 1
            if time.time() - began < 3:
                break  # screenrecord could not start (screen off?): stop rather than spin

    def stop(self, out_dir):
        self.done.set()
        shell("pkill -INT screenrecord")
        self.join(timeout=10)
        time.sleep(1)
        local = []
        for part in self.parts:
            dst = os.path.join(out_dir, os.path.basename(part))
            adb("pull", part, dst)
            shell(f"rm -f {part}")
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                local.append(dst)
            elif os.path.exists(dst):
                os.remove(dst)
        if not local:
            return
        lst = os.path.join(out_dir, f"{self.tid}.txt")
        with open(lst, "w") as f:
            f.writelines(f"file '{os.path.basename(p)}'\n" for p in local)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                        os.path.join(out_dir, f"{self.tid}.mp4")], cwd=out_dir)
        for p in local:
            os.remove(p)
        os.remove(lst)


class LogStream:
    """Streams the whole device log to a file for the life of one task, so a long run never loses its start to the
    ring buffer. progress() counts the agent's own lines (PhoneUse / PU_TRACE) seen so far, for stall detection;
    the runner's own DEBUG_ANSWER polls are not progress. system_died() reports a crash of Android itself."""

    AGENT = re.compile(rb" (PU_TRACE\s*:|PhoneUse\s*: (?!DEBUG_ANSWER))")
    DIED = re.compile(rb"DeadSystemException|Fatal signal \d+ .*\(system_server\)")

    def __init__(self, path):
        self.path = path
        self.f = open(path, "wb")
        env = dict(os.environ, MSYS_NO_PATHCONV="1")
        self.proc = subprocess.Popen([ADB, "logcat", "-v", "threadtime"], stdout=self.f, stderr=subprocess.DEVNULL, env=env)
        self.pos, self.count = 0, 0

    def progress(self):
        self.f.flush()
        with open(self.path, "rb") as r:
            r.seek(self.pos)
            chunk = r.read()
        end = chunk.rfind(b"\n") + 1  # only whole lines; the rest is read next time
        self.count += len(self.AGENT.findall(chunk[:end]))
        self.died = getattr(self, "died", False) or bool(self.DIED.search(chunk[:end]))
        self.pos += end
        return self.count

    def stop(self):
        time.sleep(1)
        self.proc.terminate()
        try:
            self.proc.wait(5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.f.close()


def needs_met(task, have):
    return all(n in have for n in task.get("needs", []))


def device_capabilities():
    pkgs = set(re.findall(r"package:(\S+)", shell("pm list packages")))
    have = {f"app:{p}" for p in pkgs}
    if "Account {" in shell("dumpsys account | grep -m1 'Account {'"):
        have.add("google-account")
    return have


def wait_service(limit_s=30):
    """Until Android has bound the accessibility service; a run started before that only answers "turn it on"."""
    t0 = time.time()
    while time.time() - t0 < limit_s:
        if "label=OpenPhoneUse" in shell("dumpsys accessibility | grep -m1 'Bound services'"):
            return True
        if time.time() - t0 > 10:  # still unbound: toggle it so the system binds it again
            shell("settings put secure enabled_accessibility_services ''")
            shell(f"settings put secure enabled_accessibility_services {SERVICE}")
        time.sleep(2)
    return False


def grade(task, reply_text):
    checks = task.get("checks", {})
    results = []
    for pat in checks.get("reply", []):
        results.append({"check": f"reply ~ /{pat}/", "ok": bool(re.search(pat, reply_text or ""))})
    for d in checks.get("device", []):
        out = shell(d["cmd"])
        results.append({"check": f"{d['cmd']} ~ /{d['expect']}/", "ok": bool(re.search(d["expect"], out)), "got": out[:200]})
    passed = sum(r["ok"] for r in results)
    if task.get("rubric"):
        verdict = "graded"
    elif not results:
        verdict = "review"
    elif passed == len(results):
        verdict = "pass" if not task.get("review") else "pass*"
    elif passed:
        verdict = "partial"
    else:
        verdict = "fail"
    return verdict, results


def grade_rubric(task, reply_text, trace, cards=()):
    """Scores the automatic rubric items; 'judge' items are left for score.py (None until judged)."""
    items = []
    for it in task.get("rubric", []):
        kind, got = it["kind"], None
        if kind == "device":
            got = shell(it["cmd"])
            frac = 1.0 if re.search(it["expect"], got) else 0.0
        elif kind == "reply":
            frac = 1.0 if re.search(it["pattern"], reply_text or "") else 0.0
        elif kind == "reply_any":
            frac = sum(bool(re.search(p, reply_text or "")) for p in it["patterns"]) / len(it["patterns"])
        elif kind == "trace":
            frac = 1.0 if re.search(it["pattern"], trace or "") else 0.0
        elif kind == "confirm":  # a card whose "where|CATEGORY|summary" matches was shown
            frac = 1.0 if any(re.search(it["pattern"], c) for c in cards) else 0.0
        elif kind == "no_confirm":  # no card matching (default: any) was shown
            frac = 0.0 if any(re.search(it.get("pattern", "."), c) for c in cards) else 1.0
        else:
            frac = None
        row = {"id": it["id"], "kind": kind, "points": it["points"], "score": frac}
        if kind == "judge":
            row["rule"] = it["rule"]
        if got is not None:
            row["got"] = got[:300]
        items.append(row)
    return items


def collect_evidence(task, out_dir):
    """Runs the task's evidence steps after the reply; 'shot' saves a screenshot for the judge."""
    shots = []
    for group in task.get("evidence", []):
        for step in group:
            if step == "shot":
                path = os.path.join(out_dir, f"{task['id']}.ev{len(shots) + 1}.png")
                with open(path, "wb") as f:
                    f.write(adb("exec-out", "screencap", "-p", binary=True))
                shots.append(os.path.basename(path))
            elif step.startswith("sleep "):
                time.sleep(float(step.split()[1]))
            else:
                shell(step)
    return shots


def parse_timings(log):
    steps = []
    for line in log.splitlines():
        m = TIMING.search(line.strip())
        if not m:
            continue
        g = m.groups()
        t = TOOLS.search(line)
        steps.append({
            "step": int(g[0]), "model_ms": int(g[1]), "ttfb_ms": int(g[2]) if g[2] else None,
            "out_ms": int(g[3]) if g[3] else None,
            "tool": t.group(1) if t else None, "tool_ms": int(t.group(2)) if t else 0,
            "settle_ms": int(t.group(3)) if t else 0, "settle": t.group(4) if t else None,
            "read_ms": int(t.group(5)) if t else 0,
        })
    return steps


def load_snapshot(name):
    """Puts the whole device back to the saved baseline: apps, browser, drafts, settings, the agent's own state.
    Every task then starts from the same phone, whichever model ran before it."""
    adb("emu", "avd", "snapshot", "load", name, check=True, timeout=180)
    adb("wait-for-device", timeout=180)
    for _ in range(120):
        if shell("getprop sys.boot_completed") == "1":
            break
        time.sleep(1)
    time.sleep(3)
    sync_clock()


def sync_clock():
    """A loaded snapshot resumes the clock it was saved with, hours behind, in GMT. Maps' "leave now", forecasts and
    alarms then disagree with the live data the apps fetch, so set the host's time and the user's zone (Seoul)."""
    uptime_ms = int(float(shell("cat /proc/uptime").split()[0]) * 1000)
    shell(f"cmd time_detector set_time_state_for_tests --elapsed_realtime {uptime_ms} "
          f"--unix_epoch_time {int(time.time() * 1000)} --user_should_confirm_time false")
    shell("cmd time_zone_detector set_time_zone_state_for_tests --zone_id Asia/Seoul --user_should_confirm_id false")


def run_task(task, out_dir, video, snapshot=None, model=None):
    tid = task["id"]
    print(f"== {tid}" + (f" [{model}]" if model else "") + f": {task['prompt']}")
    if snapshot:
        load_snapshot(snapshot)
    if model:  # after a snapshot load the app is back to the baseline's model
        shell(f"am broadcast -a com.phoneuse.DEBUG_MODEL --es text {model}")
        time.sleep(1)
    reset_chrome()
    for cmd in task.get("setup", []):
        shell(cmd)
    shell(f"settings put secure enabled_accessibility_services {SERVICE}")
    shell("input keyevent WAKEUP; svc power stayon true; wm dismiss-keyguard")
    shell("input keyevent HOME")
    time.sleep(1)
    wait_idle()
    # Rebind before every task: after an app reinstall or a long run the system can list the service as bound while
    # the app has lost its connection, and every later task then only answers "turn it on".
    shell("settings put secure enabled_accessibility_services ''")
    time.sleep(1)
    shell(f"settings put secure enabled_accessibility_services {SERVICE}")
    time.sleep(6)
    if not wait_service():
        print("   accessibility service did not bind")
    start_id = last_message_id()
    adb("logcat", "-c")
    # Everything about this task lands in its own folder: the full device log, a screenshot every SHOT_EVERY_S, the
    # step log and the reply. The flat {tid}.png / .trace.txt files beside it stay for score.py.
    task_dir = os.path.join(out_dir, tid)
    os.makedirs(os.path.join(task_dir, "shots"), exist_ok=True)
    logs = LogStream(os.path.join(task_dir, "logcat.txt"))
    rec = Recorder(tid) if video else None
    if rec:
        rec.start()
        time.sleep(1.5)
    t0 = time.time()
    # Base64, so Korean and quotes arrive exactly as written (the device shell mangles them otherwise).
    text64 = base64.b64encode(task["prompt"].encode("utf-8")).decode("ascii")
    shell(f"am broadcast -a com.phoneuse.DEBUG_RUN --es text64 {text64}")
    reply, confirms, stopped = None, 0, None
    cards = []
    # No time limit: a slow run is measured to the end. Only a run that has stopped moving (no agent log line for
    # STALL_S) is stopped, and it is reported as "stalled", not as a slow result. HARD_CAP_S is a last-resort guard
    # against a run that loops forever and is reported as "capped".
    last_seen, last_move, last_shot = 0, time.time(), 0.0
    while True:
        time.sleep(2)
        reply = reply_after(start_id)
        if reply:
            break
        now = time.time()
        seen = logs.progress()
        if seen != last_seen:
            last_seen, last_move = seen, now
        if logs.died:  # the emulator's Android crashed under the agent: not the agent's result
            stopped = "env-crash"
            break
        if now - last_move > STALL_S:
            stopped = "stalled"
        elif now - t0 > HARD_CAP_S:
            stopped = "capped"
        if stopped:
            shell("am broadcast -a com.phoneuse.DEBUG_STOP")
            for _ in range(10):
                time.sleep(2)
                reply = reply_after(start_id)
                if reply:
                    break
            break
        if now - last_shot >= SHOT_EVERY_S:
            last_shot = now
            with open(os.path.join(task_dir, "shots", f"{int(now - t0):04d}s.png"), "wb") as f:
                f.write(adb("exec-out", "screencap", "-p", binary=True))
        shell("input keyevent WAKEUP")
        # A card the task did not plan for is approved like a user who wants the task done would; it is recorded in
        # "cards" (needless cards cost the user a tap).
        policy = task.get("confirm") or "accept"
        card = answer_confirm(decline=policy == "decline")
        if card:
            confirms += 1
            cards.append(card)
            with open(os.path.join(task_dir, "cards.txt"), "a", encoding="utf-8") as f:
                f.write(f"{int(time.time() - t0)}s {card}\n")
    wall = time.time() - t0
    time.sleep(1.5)
    shot = os.path.join(out_dir, f"{tid}.png")
    with open(shot, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    log = adb("logcat", "-d", "-s", "PhoneUse:*")
    trace = adb("logcat", "-d", "-s", "PU_TRACE:*")
    with open(os.path.join(out_dir, f"{tid}.trace.txt"), "w", encoding="utf-8") as f:
        f.write(trace)
    logs.stop()
    with open(os.path.join(task_dir, "final.png"), "wb") as f, open(shot, "rb") as src:
        f.write(src.read())
    if rec:
        rec.stop(out_dir)
    facts = {cmd: shell(cmd)[:500] for cmd in task.get("facts", [])}
    rubric = grade_rubric(task, reply["text"] if reply else "", trace, cards) if "rubric" in task else None
    evidence = collect_evidence(task, out_dir)
    verdict, checks = grade(task, reply["text"] if reply else "")
    if stopped:
        verdict = stopped
    timings = parse_timings(log)
    for cmd in task.get("cleanup", []):
        shell(cmd)
    result = {
        "id": tid, "category": task["category"], "difficulty": task["difficulty"], "verdict": verdict,
        "wall_s": round(wall, 1), "steps": len(reply["steps"]) if reply else None, "model_turns": len(timings),
        "confirms": confirms, "reply": reply["text"] if reply else None, "step_log": reply["steps"] if reply else None,
        "checks": checks, "review": task.get("review"), "timings": timings,
        "rubric": rubric, "cards": cards, "facts": facts, "evidence": evidence, "prompt": task["prompt"], "stopped": stopped,
    }
    with open(os.path.join(task_dir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    with open(os.path.join(task_dir, "reply.txt"), "w", encoding="utf-8") as f:
        f.write(result["reply"] or "")
    print(f"   {verdict} in {result['wall_s']}s, {result['model_turns']} model turns")
    return result


def summarize(results):
    def total(key):
        return sum(t[key] for r in results for t in r["timings"] if t.get(key))
    turns = sum(len(r["timings"]) for r in results) or 1
    return {
        "tasks": len(results),
        "verdicts": {v: sum(r["verdict"] == v for r in results) for v in sorted({r["verdict"] for r in results})},
        "avg_ms_per_turn": {
            "model": round(total("model_ms") / turns), "ttfb": round(total("ttfb_ms") / turns), "first_out": round(total("out_ms") / turns),
            "tool": round(total("tool_ms") / turns), "settle": round(total("settle_ms") / turns), "read": round(total("read_ms") / turns),
        },
    }


def write_report(results, out_dir):
    s = summarize(results)
    lines = [f"# OpenPhoneUse benchmark — {os.path.basename(out_dir)}", "",
             f"Tasks: {s['tasks']} · " + " · ".join(f"{k}: {v}" for k, v in s["verdicts"].items()), "",
             "Average per model turn (ms): " + ", ".join(f"{k} {v}" for k, v in s["avg_ms_per_turn"].items()), "",
             "| Task | Category | Diff. | Result | Time | Turns | Confirms |", "|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append(f"| {r['id']} | {r['category']} | {r['difficulty']} | **{r['verdict']}** | {r['wall_s']}s | {r['model_turns']} | {r['confirms']} |")
    lines.append("")
    for r in results:
        lines += [f"## {r['id']} — {r['verdict']}", ""]
        for c in r["checks"]:
            lines.append(f"- {'✅' if c['ok'] else '❌'} `{c['check']}`" + (f" → `{c['got']}`" if "got" in c else ""))
        if r.get("review"):
            lines.append(f"- 👀 Review: {r['review']} (see `{r['id']}.png` / `{r['id']}.mp4`)")
        if r["reply"]:
            lines += ["", "> " + r["reply"].replace("\n", "\n> ")[:1500]]
        lines.append("")
    with open(os.path.join(out_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    with open(os.path.join(out_dir, "results.json"), "w", encoding="utf-8") as f:
        json.dump({"summary": s, "results": results}, f, ensure_ascii=False, indent=1)
    return s


def lock_device():
    """One runner per device: a second runner on the same device answers the first one's cards and mixes up
    both runs' timings. The lock is a file holding the owner's PID, so a crashed runner never blocks the next."""
    serial = os.environ.get("ANDROID_SERIAL", "default")
    path = os.path.join(tempfile.gettempdir(), f"phoneuse-bench-{serial}.lock")
    if os.path.exists(path):
        pid = open(path).read().strip()
        if os.name == "nt":
            alive = f" {pid} " in subprocess.run(["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True).stdout
        else:
            alive = os.path.exists(f"/proc/{pid}")
        if pid and alive:
            sys.exit(f"another benchmark (pid {pid}) is already running on {serial}")
    with open(path, "w") as f:
        f.write(str(os.getpid()))


def check_screen_capture():
    """A capture that comes back all zeros means the agent is blind too (its screenshots come from the same
    display), so the run would measure the accessibility tree alone and leave the judge nothing to look at."""
    shell("input keyevent WAKEUP")
    time.sleep(1)
    raw = adb("exec-out", "screencap", binary=True)
    if len(raw) > 16 and not raw[16:].strip(b"\0"):
        sys.exit("screen capture is blank: start the emulator with -gpu host "
                 "(-gpu swiftshader_indirect with -no-window captures nothing)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--category")
    ap.add_argument("--no-video", action="store_true")
    ap.add_argument("--out")
    ap.add_argument("--have", help="extra things set up by hand, e.g. claude-login")
    ap.add_argument("--spec", choices=["on", "off"], help="start the model before the screen fully settles (default: app default)")
    ap.add_argument("--suite", help="a graded suite under bench/suites/ (e.g. v2) instead of tasks.json")
    ap.add_argument("--model", help="model id to switch the app to before running (e.g. gpt-6.1-sol)")
    ap.add_argument("--models", help="comma-separated model ids run task by task, alternating which goes first, so all "
                                     "models meet the same time of day; results go to OUT/<model>/")
    ap.add_argument("--snapshot", help="emulator snapshot loaded before every task (adb emu avd snapshot save NAME)")
    ap.add_argument("--resume", action="store_true", help="keep the results already in OUT and run only the rest")
    a = ap.parse_args()
    lock_device()
    check_screen_capture()
    src = os.path.join(ROOT, "suites", f"{a.suite}.json") if a.suite else os.path.join(ROOT, "tasks.json")
    with open(src, encoding="utf-8") as f:
        tasks = json.load(f)["tasks"]
    models = [m for m in (a.models or "").split(",") if m] or [a.model]
    have = device_capabilities() | set(filter(None, (a.have or "").split(",")))
    chosen = [t for t in tasks if (not a.ids or t["id"] in a.ids) and (not a.category or t["category"] == a.category)]
    skipped = [t["id"] for t in chosen if not needs_met(t, have)]
    chosen = [t for t in chosen if needs_met(t, have)]
    if skipped:
        print("skipped (needs not met):", ", ".join(skipped))
    out_dir = os.path.abspath(a.out) if a.out else os.path.join(ROOT, "results", dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    dirs = {m: os.path.join(out_dir, m) if a.models else out_dir for m in models}
    for m, d in dirs.items():
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "run.json"), "w", encoding="utf-8") as f:
            json.dump({"suite": a.suite or "v1", "model": m, "snapshot": a.snapshot, "interleaved": bool(a.models),
                       "started": dt.datetime.now().isoformat()}, f)
    results = {m: [] for m in models}
    if a.resume:
        for m, d in dirs.items():
            p = os.path.join(d, "results.json")
            if os.path.exists(p):
                with open(p, encoding="utf-8") as f:
                    results[m] = [r for r in json.load(f)["results"] if r["verdict"] != "env-crash"]
    for i, t in enumerate(chosen):
        k = i % len(models)
        for m in models[k:] + models[:k]:  # rotate who goes first
            if any(r["id"] == t["id"] for r in results[m]):
                continue
            if a.spec:  # per task: the setting lives in the app process
                shell(f"am broadcast -a com.phoneuse.DEBUG_PROBE --es mode spec-{a.spec}")
            r = run_task(t, dirs[m], not a.no_video, a.snapshot, m)
            if r["verdict"] == "env-crash" and a.snapshot:  # a fresh snapshot gives the task one more fair start
                print("   Android crashed under the task; running it again from the snapshot")
                r = run_task(t, dirs[m], not a.no_video, a.snapshot, m)
            results[m].append(r)
            write_report(results[m], dirs[m])
    for m in models:
        s = write_report(results[m], dirs[m])
        print(m, json.dumps(s, indent=1))
        print("report:", os.path.join(dirs[m], "report.md"))


if __name__ == "__main__":
    sys.exit(main())
