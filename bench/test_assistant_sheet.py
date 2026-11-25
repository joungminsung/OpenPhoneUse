"""Check assistant startup twice on an unlocked test device with OpenPhoneUse as its assistant.

Start from Home. Set ANDROID_SERIAL to select a device and ADB if adb is outside PATH.
For the plain input test, select "Talk right away" on a device with microphone permission
denied, accessibility off, or no in-app speech provider:

    python bench/test_assistant_sheet.py

For the voice test, use a quiet test device with an in-app speech provider, microphone
permission and accessibility enabled. Map a long power-button press to the digital assistant
and select "Talk right away":

    python bench/test_assistant_sheet.py --voice
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.phoneuse"


def shell(*args, timeout=12):
    return subprocess.run(
        [os.environ.get("ADB", "adb"), "shell", *args],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    ).stdout.strip()


def hierarchy():
    # uiautomator needs a regular file. Remove it after reading the tree.
    output = shell("""dump=$(mktemp /data/local/tmp/assistant-sheet.XXXXXX) || exit 1
        trap 'rm -f "$dump"' EXIT
        uiautomator dump "$dump" >/dev/null && cat "$dump" """)
    return ET.fromstring(output) if output else None


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else (0, 0, 0, 0)


def editor_matches(text):
    tree = hierarchy()
    if tree is None:
        return False
    root = tree.find("node")
    if root is None:
        return False
    x1, y1, x2, y2 = bounds(root)
    for node in tree.findall(f".//node[@package='{PACKAGE}'][@class='android.widget.EditText']"):
        left, top, right, bottom = bounds(node)
        visible = max(x1, left) < min(x2, right) and max(y1, top) < min(y2, bottom)
        if visible and node.get("focused") == "true" and node.get("text") == text:
            return True
    return False


def recording():
    output = shell("cmd", "appops", "get", PACKAGE, "RECORD_AUDIO")
    return any("RECORD_AUDIO:" in line and "(running)" in line for line in output.splitlines())


def dismissed():
    focus = shell("dumpsys window | grep 'mCurrentFocus=' || true")
    return "Window{" in focus and "VoiceInteractionSession" not in focus


def wait_for(predicate, failure, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.2)
    raise RuntimeError(failure)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--voice", action="store_true", help="check automatic listening and microphone release")
    voice = parser.parse_args().voice
    service = shell("settings", "get", "secure", "voice_interaction_service")
    if service.split("/")[0] != PACKAGE:
        raise RuntimeError("Select OpenPhoneUse as the test device's default assistant first.")

    for session in (1, 2):
        if voice and recording():
            raise RuntimeError("OpenPhoneUse is already recording before the shortcut was pressed.")
        try:
            if voice:
                shell("input", "keyevent", "--duration", "900", "KEYCODE_POWER")
                wait_for(recording, f"Session {session}: the side button did not start microphone recording.")
            else:
                shell("input", "keyevent", "KEYCODE_ASSIST")
                wait_for(lambda: editor_matches(""),
                         f"Session {session}: an empty, focused assistant editor did not appear on screen.")
                sample = f"sheetcheck{session}"
                shell("input", "text", sample)
                wait_for(lambda: editor_matches(sample),
                         f"Session {session}: the assistant editor did not receive the text.")
        finally:
            shell("input", "keyevent", "KEYCODE_HOME")
        if voice:
            wait_for(lambda: not recording(), "The microphone stayed active after Home.", timeout=2)
        wait_for(dismissed, f"Session {session}: Home did not take focus from the assistant sheet.")
        print(f"Session {session} passed.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        sys.exit(str(error))
