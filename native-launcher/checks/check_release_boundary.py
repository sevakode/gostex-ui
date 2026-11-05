"""Check the built release variant does not expose the experimental agent provider.

Run after :app:assembleRelease or :app:compileReleaseKotlin and
:app:processReleaseManifest. This is not a general APK privacy audit.
"""
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "app/build/intermediates"
manifest = BUILD / "merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
classes = BUILD / "built_in_kotlinc/release/compileReleaseKotlin/classes"
if not manifest.is_file() or not classes.is_dir():
    raise SystemExit("Build the release manifest and Kotlin classes before checking the boundary")

android = "{http://schemas.android.com/apk/res/android}"
providers = ET.parse(manifest).findall("./application/provider")
if any("AgentStateProvider" in provider.get(android + "name", "") for provider in providers):
    raise SystemExit("Experimental agent provider is present in the release manifest")
if any(classes.rglob("AgentStateProvider*.class")):
    raise SystemExit("Experimental agent provider was compiled into release classes")
print("Release manifest and Kotlin classes exclude the experimental agent provider")
