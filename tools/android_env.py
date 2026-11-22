"""Resolve the Android SDK, adb and connected emulators from the host installation.

The repository keeps no SDK or AVD copies; helpers use whatever SDK ANDROID_HOME
(or ANDROID_SDK_ROOT) points at, falling back to adb on PATH.
"""
import os
import re
import shutil
import subprocess
from pathlib import Path


def adb():
    executable = "adb.exe" if os.name == "nt" else "adb"
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            candidate = Path(value) / "platform-tools" / executable
            if candidate.is_file():
                return str(candidate)
    found = shutil.which("adb")
    if found:
        return found
    raise RuntimeError("adb not found. Set ANDROID_HOME to the installed Android SDK.")


def sdk_root():
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value and Path(value).is_dir():
            return Path(value)
    raise RuntimeError("Set ANDROID_HOME to the installed Android SDK.")


def connected_emulators(adb_path, port=5037):
    result = subprocess.run([adb_path, "-P", str(port), "devices"],
                            capture_output=True, timeout=30, check=True, text=True)
    serials = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) == 2 and re.fullmatch(r"emulator-\d+", fields[0]) and fields[1] == "device":
            serials.append(fields[0])
    return serials
