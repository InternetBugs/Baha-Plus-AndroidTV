"""Offline source/resource checks. This does not replace Android compilation."""
from pathlib import Path
import json
import subprocess
import xml.etree.ElementTree as ET
import hashlib

root = Path(__file__).resolve().parent.parent
resources = root / "app/src/main/res"
for path in [root / "app/src/main/AndroidManifest.xml", *resources.rglob("*.xml")]:
    ET.parse(path)
mapping = json.loads((root / "app/src/main/assets/data/search-traditional.json").read_text(encoding="utf-8"))
assert mapping["动"] == "動" and mapping["画"] == "畫"
for path in (root / "app/src/main/assets/adapters").glob("*.js"):
    subprocess.run(["node", "--check", str(path)], check=True, cwd=root)
assert (root / "app/src/main/assets/LICENSE").is_file(), "APK must carry upstream MIT license"
assert (root / "app/src/main/assets/licenses/Apache-2.0.txt").is_file(), "APK must carry Apache 2.0 license"
assert (root / "app/src/main/assets/NOTICE").read_bytes() == (root / "NOTICE").read_bytes(), "Packaged attribution notice must match"
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml")
android = "{http://schemas.android.com/apk/res/android}"
assert any(x.get(android + "name") == "android.intent.category.LEANBACK_LAUNCHER" for x in manifest.iter("category"))
assert any(x.get(android + "name") == "android.hardware.touchscreen" and x.get(android + "required") == "false" for x in manifest.iter("uses-feature"))
assert manifest.find("application").get(android + "allowBackup") == "false"
wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
print("XML, JSON, JavaScript syntax, TV manifest, license and Gradle Wrapper checksum checks passed.")
