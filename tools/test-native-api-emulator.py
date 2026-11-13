"""Validate the guest-only native API variant on its explicitly named test AVD.

Reads only the variant's sanitized evidence file. Never imports browser cookies,
reads another package's storage, or saves raw media/network/logcat data.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "app.bahaplus.tv.nativeapi"
COMPONENT = PACKAGE + "/app.bahaplus.tv.experiment.NativeApiActivity"
EXPECTED_AVD = "BahaNativeApiGuest_API29"
OUTPUT = ROOT / "artifacts/performance/native-api"
sys.stdout.reconfigure(encoding="utf-8")


class GuestTest:
    def __init__(self, serial, port):
        if serial != "emulator-5592" or port != 5071:
            raise ValueError("This test is restricted to the dedicated native API emulator.")
        self.prefix = [str(ROOT / ".tools/android-sdk/platform-tools/adb.exe"),
                       "-P", str(port), "-s", serial]
        names = [self.adb("shell", "getprop", key).strip() for key in
                 ("ro.kernel.qemu.avd_name", "ro.boot.qemu.avd_name")]
        console_name = self.adb("emu", "avd", "name").splitlines()
        names.extend(console_name[:1])
        if EXPECTED_AVD not in names:
            raise RuntimeError("Unexpected AVD identity; no mutation performed.")
        if self.adb("shell", "getprop", "sys.boot_completed").strip() != "1":
            raise RuntimeError("Guest AVD has not completed boot.")
        OUTPUT.mkdir(parents=True, exist_ok=True)

    def adb(self, *args, binary=False):
        result = subprocess.run(self.prefix + list(args), capture_output=True,
                                timeout=30, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def key(self, name):
        self.adb("shell", "input", "keyevent", "KEYCODE_" + name)

    def read(self):
        # The activity rewrites its small JSON file on the UI thread; retry a
        # read that overlaps that write rather than treating it as a player error.
        for attempt in range(3):
            raw = self.adb("exec-out", "run-as", PACKAGE, "cat", "files/native-api-evidence.json")
            try:
                value = json.loads(raw)
                break
            except json.JSONDecodeError:
                if attempt == 2:
                    raise
                time.sleep(.1)
        if value.get("phase") == "error":
            self.save("failed-session", value)
            raise RuntimeError("Native API stopped: " + str(value.get("events", [])[-1]))
        return value

    def save(self, label, value):
        (OUTPUT / (label + ".json")).write_text(
            json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

    def screenshot(self, label):
        (OUTPUT / (label + ".png")).write_bytes(
            self.adb("exec-out", "screencap", "-p", binary=True))

    def wait(self, predicate, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = self.read()
            if predicate(value):
                return value
            time.sleep(.5)
        raise RuntimeError("Timed out waiting for native playback evidence.")

    def start(self, video):
        self.adb("shell", "am", "force-stop", PACKAGE)
        print(self.adb("shell", "am", "start", "-W", "-n", COMPONENT,
                       "--es", "video_id", video, "--ez", "auto_prepare", "true"), flush=True)

    def enter_content(self):
        deadline = time.monotonic() + 180
        last = None
        consent = False
        skipped = False
        while time.monotonic() < deadline:
            try:
                value = self.read()
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                time.sleep(.5)
                continue
            progress = (value["phase"], int(value.get("realAdSeconds", 0) // 5) * 5)
            if progress != last:
                print(json.dumps({"phase": progress[0], "realAdSeconds": progress[1]},
                                 ensure_ascii=False), flush=True)
                last = progress
            if value["phase"] == "consent" and not consent:
                time.sleep(.7)
                self.key("DPAD_CENTER")
                consent = True
            if value["phase"] == "advertisement" and value["realAdSeconds"] >= 30.2 and not skipped:
                self.screenshot("real-advertisement")
                self.key("DPAD_RIGHT")
                self.key("DPAD_CENTER")
                skipped = True
            if value["phase"] == "content" and value.get("playing") and value["positionSeconds"] > 2:
                self.save("content-entry", value)
                self.screenshot("native-content")
                return value
            time.sleep(.5)
        raise RuntimeError("Did not reach native content within 180 seconds.")


def full_episode(test, entry, minimum_duration=0):
    """Observe from the beginning to natural EOF without any content controls."""
    duration = entry["durationSeconds"]
    assert 60 <= duration <= 7200, "Unexpected full-episode duration."
    assert duration >= minimum_duration, "Selected playlist is shorter than the requested test duration."
    assert entry["positionSeconds"] <= 5 and entry["rate"] == 1
    started_at = time.monotonic()
    deadline = started_at + duration + 180
    event_keys = set()
    events = []
    samples = []
    memory_samples = []
    last_bucket = -1
    next_memory = 0
    last_position = entry["positionSeconds"]
    last_time = started_at
    ended = None
    result = entry
    while time.monotonic() < deadline:
        result = test.read()
        now = time.monotonic()
        assert result["phase"] == "content", "Content session changed unexpectedly."
        assert result["videoId"] == entry["videoId"] and result["rate"] == 1
        assert result["positionSeconds"] >= last_position - .5, "Playback moved backwards."
        assert result["positionSeconds"] - last_position <= now - last_time + 2, "Unexpected playback jump."
        last_position = result["positionSeconds"]
        last_time = now
        for event in result["events"]:
            key = json.dumps(event, sort_keys=True)
            if key not in event_keys:
                event_keys.add(key)
                events.append(event)
        assert not any(e["event"] in ("seek-completed", "speed") for e in events), "Content controls were used."
        sample = {key: result[key] for key in (
            "positionSeconds", "durationSeconds", "playing", "rate",
            "realWatchSeconds", "progressAccepted", "videoWidth", "videoHeight")}
        sample["observerElapsedSeconds"] = round(now - started_at, 3)
        samples.append(sample)
        bucket = int(result["positionSeconds"] // 30) * 30
        if bucket != last_bucket:
            print(json.dumps({"full_episode": True, "positionSeconds": round(result["positionSeconds"], 1),
                              "durationSeconds": duration, "realWatchSeconds": round(result["realWatchSeconds"], 1),
                              "progressAccepted": result["progressAccepted"],
                              "wallSeconds": round(now - started_at, 1)}, ensure_ascii=False), flush=True)
            test.save("continuous-samples", samples)
            test.save("continuous-events", events)
            last_bucket = bucket
        if result["positionSeconds"] >= next_memory:
            pid = test.adb("shell", "pidof", PACKAGE).strip()
            assert pid.isdigit(), "Experiment process exited."
            memory = test.adb("shell", "dumpsys", "meminfo", pid)
            webviews = re.search(r"\bWebViews:\s+(\d+)", memory)
            pss = re.search(r"TOTAL PSS:\s+(\d+)", memory) or re.search(
                r"^\s*TOTAL\s+(\d+)", memory, re.MULTILINE)
            assert webviews and int(webviews[1]) == 0
            memory_samples.append({"positionSeconds": result["positionSeconds"],
                                   "pssKiB": int(pss[1]) if pss else None, "webviews": int(webviews[1])})
            test.save("memory-samples", memory_samples)
            next_memory += 60
        ended_events = [e for e in events if e["event"] == "content-ended"]
        if ended_events:
            ended = ended_events[-1]
            final_progress = [e for e in events if e["event"] == "progress-ok" and
                              e["elapsedMs"] >= ended["elapsedMs"] and
                              e["value"] >= duration - 1]
            if final_progress:
                break
        time.sleep(1)
    else:
        test.save("incomplete-session", result)
        raise RuntimeError("Natural episode end and its accepted final progress were not observed.")
    assert ended is not None and not result["playing"]
    assert abs(result["positionSeconds"] - duration) <= .5
    assert result["realWatchSeconds"] >= duration - 5, "Actual watch coverage was incomplete."
    content_started = next(e for e in events if e["event"] == "content-playing")
    wall_seconds = (ended["elapsedMs"] - content_started["elapsedMs"]) / 1000
    assert wall_seconds >= duration - 2, "Episode did not run at real-time speed."
    assert result["progressAccepted"] >= int(duration // 60)
    assert result["videoWidth"] > 0 and result["videoHeight"] > 0
    pid = test.adb("shell", "pidof", PACKAGE).strip()
    assert pid.isdigit()
    crash_log = test.adb("logcat", "-d", "-b", "crash", "--pid=" + pid)
    crash_count = len(re.findall(r"FATAL EXCEPTION|Fatal signal", crash_log))
    assert crash_count == 0
    renderers = [line for line in test.adb("shell", "ps", "-A", "-o", "NAME").splitlines()
                 if "webview" in line and "sandboxed_process" in line]
    content_events = [e for e in events if e["elapsedMs"] >= content_started["elapsedMs"]]
    summary = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "video_id": entry["videoId"], "full_episode_natural_end": True,
        "minimum_duration_required_seconds": minimum_duration,
        "playback_rate": 1, "seek_or_speed_controls_used": False,
        "duration_seconds": duration, "position_seconds": result["positionSeconds"],
        "real_watch_seconds": result["realWatchSeconds"], "content_wall_seconds": wall_seconds,
        "real_ad_seconds": result["realAdSeconds"], "progress_accepted": result["progressAccepted"],
        "final_progress_after_natural_end_accepted": True,
        "decoded_size": [result["videoWidth"], result["videoHeight"]],
        "sampled_webviews_max": max(s["webviews"] for s in memory_samples),
        "webview_renderer_rows": len(renderers), "crash_count": crash_count,
        "buffering_transitions_after_first_play": sum(
            e["event"] == "media-state" and e["value"] == 2 for e in content_events),
        "memory_samples": memory_samples, "account_history_sync_verified": False,
        "limitations": ["独立访客会话，不证明账号观看历史或跨端续播。",
                        "32 位 x86 模拟器，未验证目标 ARM 电视、主观音画同步或全部广告类型。"],
    }
    test.save("continuous-samples", samples)
    test.save("continuous-events", events)
    test.save("final-session", result)
    test.save("summary", summary)
    test.screenshot("natural-end")
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


def main():
    global OUTPUT
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5592")
    parser.add_argument("--port", type=int, default=5071)
    parser.add_argument("--video", default="40190")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--full-episode", action="store_true",
                        help="Run at 1x from the beginning to natural EOF without content controls.")
    parser.add_argument("--minimum-duration-seconds", type=float, default=0,
                        help="Reject a full-episode sample shorter than this duration.")
    parser.add_argument("--output-name", help="Keep a second sample's evidence in a separate subdirectory.")
    args = parser.parse_args()
    if not re.fullmatch(r"\d{1,10}", args.video):
        raise ValueError("Invalid public video id.")
    if not 0 <= args.minimum_duration_seconds <= 7200:
        raise ValueError("Invalid minimum episode duration.")
    if args.minimum_duration_seconds and not args.full_episode:
        raise ValueError("A minimum duration requires --full-episode.")
    if args.output_name:
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.output_name):
            raise ValueError("Invalid evidence directory name.")
        OUTPUT = OUTPUT / args.output_name
    test = GuestTest(args.serial, args.port)
    apk = ROOT / "artifacts/BahaPlusTV-native-api-debug.apk"
    if args.install:
        print(test.adb("install", "-r", str(apk)), flush=True)
    environment = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "avd": EXPECTED_AVD, "package": PACKAGE,
        "android_api": test.adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "abi": test.adb("shell", "getprop", "ro.product.cpu.abi").strip(),
        "webview": test.adb("shell", "dumpsys", "webviewupdate"),
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "session": "fresh-guest", "user_account_imported": False,
    }
    test.save("environment", environment)
    test.start(args.video)
    entry = test.enter_content()
    if args.full_episode:
        full_episode(test, entry, args.minimum_duration_seconds)
        return
    advanced = test.wait(lambda x: x["positionSeconds"] >= entry["positionSeconds"] + 5)
    if advanced["videoWidth"] <= 0 or advanced["videoHeight"] <= 0:
        raise AssertionError("No decoded video size observed.")

    test.key("MEDIA_PLAY_PAUSE")
    paused = test.wait(lambda x: not x["playing"])
    time.sleep(2)
    still_paused = test.read()
    assert abs(still_paused["positionSeconds"] - paused["positionSeconds"]) < .5, "Pause did not hold."
    test.save("pause", still_paused)
    test.key("MEDIA_PLAY_PAUSE")
    resumed = test.wait(lambda x: x["playing"] and x["positionSeconds"] >= paused["positionSeconds"] + 2)

    before_seek = resumed
    # The content controls focus starts on Play/Pause; disabled ad skip is omitted.
    test.key("DPAD_RIGHT")
    test.key("DPAD_CENTER")
    sought = test.wait(lambda x: any(e["event"] == "seek-completed" for e in x["events"]))
    assert sought["positionSeconds"] >= before_seek["positionSeconds"] + 8
    assert sought["realWatchSeconds"] - before_seek["realWatchSeconds"] < 5
    test.save("seek", sought)
    test.key("DPAD_RIGHT")
    test.key("DPAD_CENTER")
    speed = test.wait(lambda x: x["rate"] == 1.5)
    test.save("speed", speed)

    test.key("HOME")
    background = test.wait(lambda x: not x["playing"])
    time.sleep(2)
    background2 = test.read()
    assert abs(background2["positionSeconds"] - background["positionSeconds"]) < .5
    test.save("background", background2)
    test.adb("shell", "am", "start", "-W", "-n", COMPONENT, "-f", "0x20000000")
    time.sleep(1)
    assert not test.read()["playing"], "Returning to foreground unexpectedly resumed playback."
    test.key("MEDIA_PLAY_PAUSE")
    foreground = test.wait(lambda x: x["playing"])
    progress_names = ("progress-ok", "progress-guest")
    progress_before_periodic = sum(e["event"] in progress_names for e in foreground["events"])

    deadline = time.monotonic() + 110
    progress = -1
    while time.monotonic() < deadline:
        result = test.read()
        bucket = int(result["realWatchSeconds"] // 5) * 5
        if bucket != progress:
            print(json.dumps({"realWatchSeconds": bucket, "positionSeconds": round(result["positionSeconds"], 1),
                              "progressAccepted": result["progressAccepted"],
                              "guestProgress": result["guestProgress"]}), flush=True)
            progress = bucket
        progress_events = [e for e in result["events"] if e["event"] in progress_names]
        if result["realWatchSeconds"] >= 65 and len(progress_events) > progress_before_periodic:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Periodic official progress reporting was not observed.")
    pid = test.adb("shell", "pidof", PACKAGE).strip()
    assert pid.isdigit()
    memory = test.adb("shell", "dumpsys", "meminfo", pid)
    webviews = re.search(r"\bWebViews:\s+(\d+)", memory)
    assert webviews and int(webviews[1]) == 0, "Runtime did not prove zero WebViews."
    renderer_rows = [line for line in test.adb("shell", "ps", "-A", "-o", "NAME").splitlines()
                     if "webview" in line and "sandboxed_process" in line]
    crash_log = test.adb("logcat", "-d", "-b", "crash", "--pid=" + pid)
    crash_count = len(re.findall(r"FATAL EXCEPTION|Fatal signal", crash_log))
    assert crash_count == 0
    total_pss = re.search(r"TOTAL PSS:\s+(\d+)", memory) or re.search(r"^\s*TOTAL\s+(\d+)", memory, re.MULTILINE)
    total_rss = re.search(r"TOTAL RSS:\s+(\d+)", memory)
    test.save("final-session", result)
    test.screenshot("native-final")
    summary = {
        "video_id": args.video, "native_content_played": True,
        "decoded_size": [result["videoWidth"], result["videoHeight"]],
        "real_ad_seconds": result["realAdSeconds"],
        "real_watch_seconds": result["realWatchSeconds"],
        "position_seconds": result["positionSeconds"],
        "pause_resume": True, "seek_without_counting_jump_as_watch_time": True,
        "speed_1_5": True, "background_paused_and_foreground_requires_play": True,
        "runtime_webviews": int(webviews[1]), "webview_renderer_rows": len(renderer_rows),
        "app_pss_kib": int(total_pss[1]) if total_pss else None,
        "app_rss_kib": int(total_rss[1]) if total_rss else None,
        "progress_accepted": result["progressAccepted"],
        "guest_progress_response": result["guestProgress"],
        "account_history_sync_verified": False, "crash_count": crash_count,
        "limitations": ["访客实验，不证明登录账号的跨端观看进度同步。",
                        "只实现官方配置中的视频广告；未验证其他广告 SDK、家长验证、目标 ARM 电视或整集播放。"],
    }
    test.save("summary", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
