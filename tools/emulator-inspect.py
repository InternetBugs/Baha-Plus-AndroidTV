"""Capture emulator evidence and send remote keys without touch automation.

Commands: capture LABEL, stats LABEL, keys KEY..., launch, info. All ADB calls are bound to
an explicit serial to avoid acting on a physical device connected later.
"""
from pathlib import Path
import argparse
from datetime import datetime
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / ".tools/android-sdk/platform-tools/adb.exe"
OUTPUT = ROOT / "artifacts/emulator"


def run(serial, *arguments, binary=False):
    result = subprocess.run(
        [str(ADB), "-s", serial, *arguments],
        capture_output=True, timeout=45, check=True,
    )
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def capture(serial, label, screenshot_only=False):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
        raise ValueError("Use a simple capture label")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{label}.png").write_bytes(run(serial, "exec-out", "screencap", "-p", binary=True))
    if screenshot_only:
        print(f"Screenshot: {OUTPUT / (label + '.png')}")
        return
    try:
        run(serial, "shell", "uiautomator", "dump", "--compressed", "/sdcard/baha-tv-ui.xml")
        hierarchy = run(serial, "exec-out", "cat", "/sdcard/baha-tv-ui.xml")
        tree = ET.fromstring(hierarchy)
    except (subprocess.SubprocessError, ET.ParseError) as error:
        failure = {"capture": label, "hierarchy_error": str(error), "screenshot_saved": True}
        (OUTPUT / f"{label}-hierarchy-error.json").write_text(
            json.dumps(failure, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(failure, ensure_ascii=False))
        return
    (OUTPUT / f"{label}.xml").write_text(hierarchy, encoding="utf-8")
    nodes = []
    hidden = set()
    for node in tree.iter("node"):
        bounds = [int(value) for value in re.findall(r"-?\d+", node.get("bounds", ""))]
        if node.get("class") == "android.webkit.WebView" and len(bounds) == 4 and (
            bounds[2] - bounds[0] <= 2 or bounds[3] - bounds[1] <= 2
        ):
            hidden.update(node.iter())
    for node in tree.iter("node"):
        if node in hidden:
            continue
        if node.get("text") or node.get("content-desc") or node.get("focused") == "true":
            nodes.append({key: node.get(key) for key in
                          ("text", "content-desc", "class", "focused", "focusable", "bounds")})
    (OUTPUT / f"{label}.json").write_text(
        json.dumps(nodes, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"capture": label, "focused": [node for node in nodes if node["focused"] == "true"],
                      "texts": [node["text"] for node in nodes if node["text"]]}, ensure_ascii=False, indent=2))


def stats(serial, label):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
        raise ValueError("Use a simple stats label")
    pid = run(serial, "shell", "pidof", "app.bahaplus.tv").strip()
    if not re.fullmatch(r"\d+", pid):
        raise RuntimeError("Expected one running app process")
    memory = run(serial, "shell", "dumpsys", "meminfo", "app.bahaplus.tv")
    # Do not persist raw logcat: WebView/media logs may contain session URLs.
    crashes = run(serial, "logcat", "-b", "crash", "-d", f"--pid={pid}")
    main_log = run(serial, "logcat", "-d", f"--pid={pid}")
    system_log = run(serial, "logcat", "-b", "system", "-d")
    pss = re.search(r"TOTAL PSS:\s+(\d+)", memory)
    rss = re.search(r"TOTAL RSS:\s+(\d+)", memory)
    result = {
        "captured_at": datetime.now().astimezone().isoformat(),
        "serial": serial,
        "pid": int(pid),
        "main_process_pss_kib": int(pss[1]) if pss else None,
        "main_process_rss_kib": int(rss[1]) if rss else None,
        "crash_buffer_nonempty_for_current_pid": any(
            line.strip() and not line.startswith("---------") for line in crashes.splitlines()),
        "java_fatal_exception_count": crashes.count("FATAL EXCEPTION"),
        "native_fatal_signal_count": main_log.count("Fatal signal"),
        "app_anr_count_in_retained_system_log": system_log.count("ANR in app.bahaplus.tv"),
        "scope": "Current process and retained log buffers only; excludes renderer/decoder memory.",
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{label}-memory.txt").write_text(memory, encoding="utf-8")
    (OUTPUT / f"{label}-stats.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    commands = parser.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture")
    capture_parser.add_argument("label")
    capture_parser.add_argument("--screenshot-only", action="store_true")
    commands.add_parser("stats").add_argument("label")
    commands.add_parser("keys").add_argument("keys", nargs="+")
    commands.add_parser("launch")
    commands.add_parser("info")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This helper operates on emulator serials only")
    if args.command == "capture":
        capture(args.serial, args.label, args.screenshot_only)
    elif args.command == "stats":
        stats(args.serial, args.label)
    elif args.command == "keys":
        for key in args.keys:
            if not re.fullmatch(r"(KEYCODE_)?[A-Z0-9_]+", key):
                parser.error("Invalid key name")
            run(args.serial, "shell", "input", "keyevent",
                key if key.startswith("KEYCODE_") else f"KEYCODE_{key}")
    elif args.command == "launch":
        print(run(args.serial, "shell", "am", "start", "-W", "-n", "app.bahaplus.tv/.MainActivity"))
    else:
        for property_name in ("ro.build.version.release", "ro.build.version.sdk",
                              "ro.product.cpu.abilist", "ro.build.characteristics"):
            print(f"{property_name}: {run(args.serial, 'shell', 'getprop', property_name).strip()}")
        print(run(args.serial, "shell", "dumpsys", "webviewupdate"))


if __name__ == "__main__":
    main()
