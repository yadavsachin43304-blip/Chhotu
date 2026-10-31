#!/usr/bin/env python3
"""Chhotu Master emulator UI driver.

Gets through first-launch screens (agreement, permission guide, system dialogs),
verifies the chat screen opens, and sends one chat message.

Usage: ui_drive.py <package> <out_dir> <message>
Exit code 0 = chat input found and message sent; 2 = chat screen not reached.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = sys.argv[1]
OUT = sys.argv[2]
MESSAGE = sys.argv[3] if len(sys.argv) > 3 else "Namaste Chhotu"
os.makedirs(OUT, exist_ok=True)

# Buttons to press, in priority order (case-insensitive, matched against text or content-desc).
PRESS = [
    "i have read and agree",
    "standard permissions",
    "confirm selection",
    "next",
    "continue",
    "complete",
    "finish",
    "skip",
    "got it",
    "start",
    "while using the app",
    "only this time",
    "allow",
    "ok",
    "close",
    "later",
    "not now",
    "dismiss",
]
NEVER = ["previous", "return to permissions", "root", "debug", "deny", "don't allow", "uninstall", "delete"]
BLOCKING_TEXT = ["i have read and agree", "please wait", "select permission level", "welcome", "feature tour",
                 "basic permissions", "incomplete permissions", "user agreement"]

step = 0
MAX_SAME_TAP = 2  # never tap the same label more than 2 times in a row
last_label = None
same_count = 0


def sh(*args, timeout=60):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except subprocess.TimeoutExpired:
        return ""


def shot(name):
    global step
    step += 1
    path = os.path.join(OUT, f"ui_{step:02d}_{name}.png")
    with open(path, "wb") as f:
        try:
            f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout)
        except subprocess.TimeoutExpired:
            pass


def dump():
    sh("shell", "rm", "-f", "/sdcard/ui.xml")
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=90)
    xml = sh("shell", "cat", "/sdcard/ui.xml")
    if "<hierarchy" not in xml:
        return []
    xml = xml[xml.index("<hierarchy"):]
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    nodes = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        nodes.append({
            "text": (n.get("text") or "").strip(),
            "desc": (n.get("content-desc") or "").strip(),
            "cls": n.get("class") or "",
            "pkg": n.get("package") or "",
            "clickable": n.get("clickable") == "true",
            "enabled": n.get("enabled") == "true",
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
            "area": max(0, x2 - x1) * max(0, y2 - y1),
        })
    return nodes


def focus_pkg():
    out = sh("shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^/ }]+)", out)
    return m.group(1) if m else ""


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def screen_size():
    out = sh("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def log(msg):
    print(f"[ui_drive] {msg}", flush=True)


def find_chat_input(nodes):
    edits = [n for n in nodes if "EditText" in n["cls"] and n["pkg"] == PKG]
    if not edits:
        return None
    texts = " ".join((n["text"] + " " + n["desc"]).lower() for n in nodes)
    if any(b in texts for b in BLOCKING_TEXT):
        return None
    # chat input is usually the lowest EditText on screen
    return sorted(edits, key=lambda n: n["cy"])[-1]


def main():
    global last_label, same_count
    w, h = screen_size()
    log(f"screen {w}x{h}")
    chat = None
    for i in range(45):
        pkg = focus_pkg()
        nodes = dump()
        if i % 3 == 0:
            shot(f"loop{i}")
        if pkg and pkg != PKG and "permissioncontroller" not in pkg and "packageinstaller" not in pkg:
            log(f"foreground is {pkg}, pressing BACK")
            sh("shell", "input", "keyevent", "4")
            time.sleep(2)
            continue
        chat = find_chat_input(nodes)
        if chat:
            log(f"chat input found at {chat['cx']},{chat['cy']}")
            break
        pressed = False
        for word in PRESS:
            for n in nodes:
                label = (n["text"] or n["desc"]).lower()
                if not label or any(bad in label for bad in NEVER):
                    continue
                if label == word or (len(word) > 4 and label.startswith(word)):
                    if label == last_label and same_count >= MAX_SAME_TAP:
                        log(f"skip '{label}' (already tapped {same_count} times in a row)")
                        continue
                    log(f"tap '{label}' at {n['cx']},{n['cy']}")
                    tap(n["cx"], n["cy"])
                    if label == last_label:
                        same_count += 1
                    else:
                        last_label, same_count = label, 1
                    pressed = True
                    break
            if pressed:
                break
        if not pressed:
            texts = [(n["text"] or n["desc"]) for n in nodes if (n["text"] or n["desc"])]
            log(f"no known button; visible: {texts[:25]}")
            joined = " ".join(texts).lower()
            if "please wait" in joined:
                time.sleep(3)
                continue
            # agreement text may need scrolling to enable the button
            sh("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.25)), "300")
            time.sleep(1)
            # icon-only "next" button in the lower right corner of the permission guide
            tap(int(w * 0.88), int(h * 0.93))
        time.sleep(3)

    shot("after_onboarding")
    if not chat:
        log("RESULT: CHAT_NOT_REACHED")
        return 2

    tap(chat["cx"], chat["cy"])
    time.sleep(1)
    sh("shell", "input", "text", MESSAGE.replace(" ", "%s"))
    time.sleep(1)
    shot("typed")
    nodes = dump()
    send = None
    for n in nodes:
        label = (n["desc"] + " " + n["text"]).lower()
        if n["pkg"] == PKG and ("send" in label or "发送" in label):
            send = n
    if send:
        log(f"tap send at {send['cx']},{send['cy']}")
        tap(send["cx"], send["cy"])
    else:
        log("send button not found by label; pressing ENTER")
        sh("shell", "input", "keyevent", "66")
    time.sleep(3)
    shot("sent")
    log("RESULT: CHAT_OPENED_MESSAGE_SENT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
