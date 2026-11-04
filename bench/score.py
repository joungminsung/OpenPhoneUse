"""Scores graded benchmark runs (run.py --suite v2) and compares them.

    python bench/score.py packets RUN_DIR [RUN_DIR ...] --out JUDGE_DIR   # blind packets for the judge
    python bench/score.py report RUN_DIR [RUN_DIR ...] [--judge JUDGE_DIR] [--out FILE]

Judge items need a person or a judge model. 'packets' writes one Markdown file per task in JUDGE_DIR holding every
run's answer under a neutral label (run A, run B, ...) with its screenshots, plus key.json mapping the labels back to
run directories, so the judge cannot tell which model it is scoring. The judge writes JUDGE_DIR/scores.json:

    {"<task id>": {"A": {"<item id>": 0.0-1.0, "_note": "why"}, "B": {...}}}

An item id there may also be an automatic one, which overrides it (e.g. a reply that claims a tile the screenshot
does not show). 'report' merges the scores and writes a Markdown comparison.
"""
import argparse
import json
import os
import random
import shutil
import string
import sys


def load_run(d):
    with open(os.path.join(d, "results.json"), encoding="utf-8") as f:
        results = json.load(f)["results"]
    meta = {}
    if os.path.exists(os.path.join(d, "run.json")):
        with open(os.path.join(d, "run.json"), encoding="utf-8") as f:
            meta = json.load(f)
    return meta, {r["id"]: r for r in results}


def packets(dirs, out):
    os.makedirs(out, exist_ok=True)
    runs = [load_run(d) for d in dirs]
    labels = list(string.ascii_uppercase[:len(dirs)])
    order = list(range(len(dirs)))
    key = {}
    task_ids = []
    for _, res in runs:
        task_ids += [t for t in res if t not in task_ids]
    for tid in task_ids:
        random.shuffle(order)  # a fresh label order per task, so no label means one model throughout
        key[tid] = {labels[i]: dirs[j] for i, j in enumerate(order)}
        lines = []
        for i, j in enumerate(order):
            r = runs[j][1].get(tid)
            if not r or not r.get("rubric"):
                continue
            if not lines:
                lines += [f"# {tid}", "", f"Prompt: {r['prompt']}", "", "## Judge items", ""]
                lines += [f"- `{it['id']}` ({it['points']} pt): {it['rule']}" for it in r["rubric"] if it["kind"] == "judge"]
                lines += ["", "Automatic items (already scored; override only if the evidence shows the score is wrong):", ""]
                lines += [f"- `{it['id']}` ({it['kind']}, {it['points']} pt)" for it in r["rubric"] if it["kind"] != "judge"]
                lines.append("")
            lab = labels[i]
            lines += [f"## Run {lab}", "", f"Timed out: {r.get('timed_out')} · wall {r['wall_s']}s · {r['model_turns']} model turns", ""]
            lines += ["Automatic scores: " + ", ".join(f"{it['id']}={it['score']}" for it in r["rubric"] if it["kind"] != "judge"), ""]
            for cmd, fact in (r.get("facts") or {}).items():
                lines += [f"Fact `{cmd}`:", "", "```", fact, "```", ""]
            lines += ["Reply:", "", "```", (r.get("reply") or "(no reply)")[:4000], "```", ""]
            shots = [f"{tid}.png"] + (r.get("evidence") or [])
            for s in shots:
                src = os.path.join(key[tid][lab], s)
                if os.path.exists(src):
                    dst = f"{lab}.{s}"
                    shutil.copy(src, os.path.join(out, dst))
                    lines.append(f"Screenshot: {os.path.join(out, dst)}" + ("  (final screen)" if s == f"{tid}.png" else "  (evidence after the run)"))
            lines.append("")
        if lines:
            with open(os.path.join(out, f"{tid}.md"), "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
    with open(os.path.join(out, "key.json"), "w", encoding="utf-8") as f:
        json.dump(key, f, indent=1)
    print("packets:", out)


def task_score(r, judged):
    earned = total = 0.0
    pending = 0
    for it in r["rubric"]:
        s = judged.get(it["id"], it["score"])
        total += it["points"]
        if s is None:
            pending += 1
            continue
        earned += it["points"] * max(0.0, min(1.0, float(s)))
    return (100.0 * earned / total if total else 0.0), pending


def report(dirs, judge, out):
    runs = [load_run(d) for d in dirs]
    scores, key = {}, {}
    if judge:
        with open(os.path.join(judge, "scores.json"), encoding="utf-8") as f:
            scores = json.load(f)
        with open(os.path.join(judge, "key.json"), encoding="utf-8") as f:
            key = json.load(f)

    def judged_for(tid, d):
        for lab, dd in key.get(tid, {}).items():
            if os.path.abspath(dd) == os.path.abspath(d):
                return scores.get(tid, {}).get(lab, {})
        return {}

    names = [m.get("model") or os.path.basename(d) for (m, _), d in zip(runs, dirs)]
    task_ids = []
    for _, res in runs:
        task_ids += [t for t, r in res.items() if t not in task_ids and r.get("rubric")]
    lines = ["# OpenPhoneUse graded benchmark", "", "| Task | Diff. | " + " | ".join(names) + " |",
             "|---|---|" + "---|" * len(names)]
    sums = [[] for _ in dirs]
    walls = [[] for _ in dirs]
    turns = [[] for _ in dirs]
    by_cat = {}
    notes = []
    for tid in task_ids:
        cells, diff = [], ""
        for i, ((_, res), d) in enumerate(zip(runs, dirs)):
            r = res.get(tid)
            if not r:
                cells.append("—")
                continue
            diff = r["difficulty"]
            j = judged_for(tid, d)
            sc, pending = task_score(r, j)
            sums[i].append(sc)
            walls[i].append(r["wall_s"])
            turns[i].append(r["model_turns"])
            by_cat.setdefault(r["category"], [[] for _ in dirs])[i].append(sc)
            cells.append(f"{sc:.0f}" + (" (timeout)" if r.get("timed_out") else "") + (f" [{pending} unjudged]" if pending else "")
                         + f" · {r['wall_s']:.0f}s/{r['model_turns']}t")
            if j.get("_note"):
                notes.append(f"- **{tid} / {names[i]}**: {j['_note']}")
        lines.append(f"| {tid} | {diff} | " + " | ".join(cells) + " |")
    avg = lambda xs: sum(xs) / len(xs) if xs else 0.0
    lines.append("| **Average** | | " + " | ".join(f"**{avg(s):.1f}**" for s in sums) + " |")
    lines += ["", "| Category | " + " | ".join(names) + " |", "|---|" + "---|" * len(names)]
    for cat, cols in by_cat.items():
        lines.append(f"| {cat} | " + " | ".join(f"{avg(c):.1f}" for c in cols) + " |")
    lines += ["", "| | " + " | ".join(names) + " |", "|---|" + "---|" * len(names),
              "| Avg time per task (s) | " + " | ".join(f"{avg(w):.0f}" for w in walls) + " |",
              "| Avg model turns per task | " + " | ".join(f"{avg(t):.1f}" for t in turns) + " |"]
    if notes:
        lines += ["", "## Judge notes", ""] + notes
    text = "\n".join(lines)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(text)
    print(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["packets", "report"])
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--judge")
    ap.add_argument("--out")
    a = ap.parse_args()
    if a.cmd == "packets":
        if not a.out:
            sys.exit("--out JUDGE_DIR is required")
        packets(a.dirs, a.out)
    else:
        report(a.dirs, a.judge, a.out)


if __name__ == "__main__":
    main()
