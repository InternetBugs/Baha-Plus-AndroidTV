"""Collect scoped performance evidence from an explicitly identified test AVD.

Never reads app storage, cookies, media URLs or raw logcat into evidence files.
Renderer/codec totals belong to the isolated emulator, not necessarily only the app.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / ".tools/android-sdk/platform-tools/adb.exe"
PACKAGE = "app.bahaplus.tv"
OUTPUT = ROOT / "artifacts/performance"
sys.stdout.reconfigure(encoding="utf-8")


class Probe:
    def __init__(self, serial, port, expected_avd):
        if not (re.fullmatch(r"emulator-\d+", serial) or
                re.fullmatch(r"127\.0\.0\.1:\d+", serial)):
            raise ValueError("Only an explicitly selected local emulator is allowed")
        if not 1024 <= port <= 65535:
            raise ValueError("Invalid ADB server port")
        self.prefix = [str(ADB), "-P", str(port), "-s", serial]
        self.serial = serial
        actual = self.run("shell", "getprop", "ro.kernel.qemu.avd_name").strip()
        if not actual:
            actual = self.run("shell", "getprop", "ro.boot.qemu.avd_name").strip()
        if not expected_avd or actual != expected_avd:
            raise RuntimeError(f"AVD identity mismatch: {actual!r}")
        self.avd = actual
        self.hz = int(self.run("shell", "getconf", "CLK_TCK").strip())
        OUTPUT.mkdir(parents=True, exist_ok=True)

    def run(self, *args, binary=False, timeout=45):
        result = subprocess.run(self.prefix + list(args), capture_output=True,
                                timeout=timeout, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def save(self, label, data):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", label):
            raise ValueError("Use a simple evidence label")
        path = OUTPUT / f"{label}.json"
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

    def processes(self):
        result = []
        for line in self.run("shell", "ps", "-A", "-o", "PID,PPID,NAME").splitlines()[1:]:
            fields = line.split(None, 2)
            if len(fields) != 3:
                continue
            pid, parent, name = fields
            if name == PACKAGE:
                role = "app"
            elif "webview" in name and "sandboxed_process" in name:
                role = "renderer"
            elif name in ("media.codec", "media.swcodec", "media.extractor", "mediaserver"):
                role = "shared_media"
            else:
                continue
            result.append(dict(pid=int(pid), parent=int(parent), name=name, role=role))
        return result

    def cpu(self, processes):
        paths = [f"/proc/{p['pid']}/stat" for p in processes]
        output = self.run("shell", "cat", "/proc/uptime", *paths)
        lines = output.splitlines()
        ticks = {}
        for line in lines[1:]:
            match = re.match(r"(\d+) \(.*\) (.*)", line)
            if match:
                fields = match[2].split()
                ticks[int(match[1])] = int(fields[11]) + int(fields[12])
        return float(lines[0].split()[0]), ticks

    def sample(self):
        processes = self.processes()
        uptime, ticks = self.cpu(processes)
        for process in processes:
            memory = self.run("shell", "dumpsys", "meminfo", str(process["pid"]))
            for field, pattern in (
                ("pss_kib", r"TOTAL PSS:\s+(\d+)"),
                ("rss_kib", r"TOTAL RSS:\s+(\d+)"),
                ("views", r"\bViews:\s+(\d+)"),
                ("activities", r"\bActivities:\s+(\d+)"),
                ("webviews", r"\bWebViews:\s+(\d+)"),
                ("native_alloc_kib", r"Native Heap\s+(?:\d+\s+){6}(\d+)"),
            ):
                match = re.search(pattern, memory)
                process[field] = int(match[1]) if match else None
            process["cpu_ticks"] = ticks.get(process["pid"])
        meminfo = self.run("shell", "cat", "/proc/meminfo")
        available = re.search(r"MemAvailable:\s+(\d+)", meminfo)
        return dict(at=datetime.now().astimezone().isoformat(), uptime_s=uptime,
                    available_kib=int(available[1]) if available else None,
                    processes=processes)

    def gfx(self, reset=False):
        output = self.run("shell", "dumpsys", "gfxinfo", PACKAGE,
                          "reset" if reset else "framestats")
        result = {}
        for key, pattern in (
            ("frames", r"Total frames rendered:\s+(\d+)"),
            ("janky_frames", r"Janky frames:\s+(\d+)"),
            ("janky_percent", r"Janky frames:\s+\d+\s+\(([\d.]+)%"),
            ("p50_ms", r"50th percentile:\s+(\d+)ms"),
            ("p90_ms", r"90th percentile:\s+(\d+)ms"),
            ("p95_ms", r"95th percentile:\s+(\d+)ms"),
            ("p99_ms", r"99th percentile:\s+(\d+)ms"),
        ):
            match = re.search(pattern, output)
            result[key] = float(match[1]) if match else None
        if result["frames"] == 0:
            for key in ("janky_percent", "p50_ms", "p90_ms", "p95_ms", "p99_ms"):
                result[key] = None
        return result, output

    def capture(self, label, screenshot_only=False):
        self.save(label, {"at": datetime.now().astimezone().isoformat(), "avd": self.avd})
        (OUTPUT / f"{label}.png").write_bytes(
            self.run("exec-out", "screencap", "-p", binary=True))
        if screenshot_only:
            return {"screenshot": str(OUTPUT / f"{label}.png")}
        self.run("shell", "uiautomator", "dump", "--compressed", "/sdcard/perf-ui.xml")
        root = ET.fromstring(self.run("exec-out", "cat", "/sdcard/perf-ui.xml"))
        nodes = [{k: node.get(k) for k in
                  ("text", "content-desc", "focused", "bounds", "class")}
                 for node in root.iter("node")
                 if node.get("text") or node.get("content-desc") or node.get("focused") == "true"]
        self.save(label, nodes)
        return {"texts": [n["text"] for n in nodes if n["text"]],
                "focused": [n for n in nodes if n["focused"] == "true"]}

    def measure(self, label, seconds, interval):
        samples = []
        self.gfx(reset=True)
        start = time.monotonic()
        while True:
            sample = self.sample()
            samples.append(sample)
            self.save(label + "-samples", samples)
            main = [p for p in sample["processes"] if p["role"] == "app"]
            print(json.dumps({"sample": len(samples), "elapsed_s": round(time.monotonic() - start, 1),
                              "app_pss_kib": main[0]["pss_kib"] if main else None}),
                  flush=True)
            remaining = seconds - (time.monotonic() - start)
            if remaining <= 0:
                break
            time.sleep(min(interval, remaining))
        role_summary = {}
        for role in ("app", "renderer", "shared_media"):
            values = [sum(p["pss_kib"] or 0 for p in s["processes"] if p["role"] == role)
                      for s in samples]
            cpu_percent = []
            for previous, current in zip(samples, samples[1:]):
                old = {p["pid"]: p for p in previous["processes"] if p["role"] == role}
                delta = sum(p["cpu_ticks"] - old[p["pid"]]["cpu_ticks"]
                            for p in current["processes"]
                            if p["role"] == role and p["pid"] in old
                            and p["cpu_ticks"] is not None
                            and old[p["pid"]]["cpu_ticks"] is not None)
                duration = current["uptime_s"] - previous["uptime_s"]
                if duration > 0:
                    cpu_percent.append(100 * delta / self.hz / duration)
            role_summary[role] = dict(
                pss_median_mib=round(statistics.median(values) / 1024, 2),
                pss_peak_mib=round(max(values) / 1024, 2),
                cpu_mean_one_core_percent=round(statistics.mean(cpu_percent), 2)
                if cpu_percent else None)
        gfx, raw = self.gfx()
        (OUTPUT / f"{label}-gfx.txt").write_text(raw, encoding="utf-8")
        result = dict(avd=self.avd, serial=self.serial, elapsed_s=round(time.monotonic() - start, 2),
                      sample_count=len(samples), roles=role_summary, gfx=gfx,
                      app_pids=sorted({p["pid"] for s in samples for p in s["processes"]
                                       if p["role"] == "app"}),
                      scope="All WebView renderers and shared media processes on this test AVD. "
                            "CPU 100% means one fully used virtual core. UI gfx excludes video frames.")
        self.save(label, result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--expected-avd", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    capture = commands.add_parser("capture")
    capture.add_argument("label")
    capture.add_argument("--screenshot-only", action="store_true")
    measure = commands.add_parser("measure")
    measure.add_argument("label")
    measure.add_argument("--seconds", type=int, default=60)
    measure.add_argument("--interval", type=int, default=10)
    commands.add_parser("snapshot").add_argument("label")
    commands.add_parser("keys").add_argument("keys", nargs="+")
    commands.add_parser("gfx").add_argument("label")
    commands.add_parser("reset-gfx")
    commands.add_parser("info").add_argument("label")
    commands.add_parser("errors").add_argument("label")
    scroll = commands.add_parser("scroll")
    scroll.add_argument("label")
    startups = commands.add_parser("startups")
    startups.add_argument("label")
    startups.add_argument("--count", type=int, default=5)
    args = parser.parse_args()
    probe = Probe(args.serial, args.adb_port, args.expected_avd)
    if args.command == "measure":
        if args.seconds < 1 or args.interval < 1:
            parser.error("Measurement duration and interval must be positive")
        result = probe.measure(args.label, args.seconds, args.interval)
    elif args.command == "capture":
        result = probe.capture(args.label, args.screenshot_only)
    elif args.command == "snapshot":
        result = probe.sample()
        probe.save(args.label, result)
    elif args.command == "keys":
        for key in args.keys:
            if not re.fullmatch(r"[A-Z0-9_]+", key):
                parser.error("Invalid key")
            probe.run("shell", "input", "keyevent", "KEYCODE_" + key)
        result = {"sent": args.keys}
    elif args.command == "reset-gfx":
        result, _ = probe.gfx(reset=True)
    elif args.command == "info":
        properties = ("ro.build.version.release", "ro.build.version.sdk",
                      "ro.product.cpu.abilist", "ro.build.characteristics",
                      "ro.hardware.egl", "ro.kernel.qemu.gles")
        result = {"avd": probe.avd, "serial": probe.serial,
                  "captured_at": datetime.now().astimezone().isoformat(),
                  "properties": {key: probe.run("shell", "getprop", key).strip()
                                 for key in properties},
                  "display": probe.run("shell", "wm", "size") + probe.run("shell", "wm", "density"),
                  "memory": probe.run("shell", "head", "-n", "3", "/proc/meminfo"),
                  "virtual_cores": probe.run("shell", "getconf", "_NPROCESSORS_ONLN").strip(),
                  "webview": probe.run("shell", "dumpsys", "webviewupdate"),
                  "apks": {}}
        for apk in (OUTPUT / "baseline-debug.apk", ROOT / "artifacts/BahaPlusTV-0.1.0-debug.apk"):
            if apk.is_file():
                result["apks"][apk.name] = {
                    "bytes": apk.stat().st_size,
                    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
        probe.save(args.label, result)
    elif args.command == "errors":
        crash = probe.run("logcat", "-b", "crash", "-d")
        main_pid = probe.run("shell", "pidof", PACKAGE).strip()
        if not re.fullmatch(r"\d+", main_pid):
            raise RuntimeError("Expected a running app")
        app_log = probe.run("logcat", "-d", "--pid=" + main_pid)
        system = probe.run("logcat", "-b", "system", "-d")
        result = {"java_app_crashes": crash.count("Process: " + PACKAGE + ","),
                  "native_app_crash_records": crash.count(">>> " + PACKAGE + " <<<"),
                  "native_fatal_signals_current_pid": app_log.count("Fatal signal"),
                  "app_anrs": system.count("ANR in " + PACKAGE),
                  "current_app_pid": int(main_pid),
                  "scope": "Retained emulator crash/system buffers and current app PID; no raw logs saved."}
        probe.save(args.label, result)
    elif args.command == "scroll":
        probe.gfx(reset=True)
        before = probe.sample()
        started = time.monotonic()
        for _ in range(4):
            for direction in ("DPAD_DOWN", "DPAD_UP"):
                for _ in range(6):
                    probe.run("shell", "input", "keyevent", "KEYCODE_" + direction)
                    time.sleep(.2)
        gfx, raw = probe.gfx()
        result = {"key_count": 48, "elapsed_s": round(time.monotonic() - started, 2),
                  "gfx": gfx, "before": before, "after": probe.sample()}
        old_pid = [p["pid"] for p in before["processes"] if p["role"] == "app"]
        new_pid = [p["pid"] for p in result["after"]["processes"] if p["role"] == "app"]
        if len(old_pid) != 1 or old_pid != new_pid:
            raise RuntimeError("The app restarted during scrolling; discard this measurement")
        elapsed = result["after"]["uptime_s"] - before["uptime_s"]
        old_ticks = {p["pid"]: p["cpu_ticks"] for p in before["processes"]}
        result["cpu_one_core_percent"] = {
            role: round(100 * sum(p["cpu_ticks"] - old_ticks[p["pid"]]
                                 for p in result["after"]["processes"]
                                 if p["role"] == role and p["pid"] in old_ticks
                                 and p["cpu_ticks"] is not None
                                 and old_ticks[p["pid"]] is not None) / probe.hz / elapsed, 2)
            for role in ("app", "renderer", "shared_media")}
        probe.save(args.label, result)
        (OUTPUT / f"{args.label}-gfx.txt").write_text(raw, encoding="utf-8")
    elif args.command == "startups":
        launches = []
        for index in range(args.count):
            probe.run("shell", "am", "force-stop", PACKAGE)
            raw = probe.run("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            match = re.search(r"TotalTime:\s+(\d+)", raw)
            if "Status: ok" not in raw or not match:
                raise RuntimeError("Cold launch failed")
            launches.append(int(match[1]))
            print(json.dumps({"launch": index + 1, "activity_ms": launches[-1]}), flush=True)
            time.sleep(6)
        result = dict(activity_ms=launches, median_ms=statistics.median(launches),
                      maximum_ms=max(launches),
                      scope="Force-stopped app, existing disk/WebView caches. Activity time excludes network content.")
        probe.save(args.label, result)
    else:
        result, raw = probe.gfx()
        probe.save(args.label, result)
        (OUTPUT / f"{args.label}.txt").write_text(raw, encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
