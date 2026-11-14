"""Run host regressions against the production policy extracted from the VLC patch.

These deterministic tests cover policy decisions and modeled event sequences,
not Android lifecycle or LibVLC callback integration.
"""
# Copyright (C) 2026 GOSTEX contributors. GPL-2.0-or-later.
from pathlib import Path
import re
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT


PATCH_DIR = ROOT / "native-launcher/vlc-patch/3.7.2"
POLICY_PATH = "application/vlc-android/src/org/videolan/vlc/gui/video/GostexTrackPolicy.kt"
TESTS = ("TrackPolicyTest.kt", "TrackRaceSequenceTest.kt", "ViewIntentPolicyTest.kt")


def extract_policy(patch: str) -> str:
    header = f"diff --git a/{POLICY_PATH} b/{POLICY_PATH}\n"
    if patch.count(header) != 1:
        raise ValueError("Expected exactly one production GostexTrackPolicy.kt patch")
    section = patch.split(header, 1)[1].split("\ndiff --git ", 1)[0]
    lines = section.splitlines()
    if "--- /dev/null" not in lines or f"+++ b/{POLICY_PATH}" not in lines:
        raise ValueError("Policy must be a wholly added source file")
    hunks = [index for index, line in enumerate(lines) if line.startswith("@@")]
    if len(hunks) != 1:
        raise ValueError("Expected one complete policy source hunk")
    hunk = hunks[0]
    match = re.fullmatch(r"@@ -0,0 \+1,(\d+) @@", lines[hunk])
    body = lines[hunk + 1:]
    if not match or len(body) != int(match[1]) or any(not line.startswith("+") for line in body):
        raise ValueError("Policy patch is incomplete or has unexpected context")
    return "\n".join(line[1:] for line in body) + "\n"


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    print(result.stdout, end="")


def main() -> None:
    policy = extract_policy((PATCH_DIR / "gostex-vlc.patch").read_text())
    compiler_classpath = ":".join(map(str, COMPILER))
    stdlib = str(COMPILER[1])
    with tempfile.TemporaryDirectory(prefix="gostex-vlc-policy-") as temporary:
        folder = Path(temporary)
        source = folder / "GostexTrackPolicy.kt"
        source.write_text(policy)
        for filename in TESTS:
            test = PATCH_DIR / filename
            classes = folder / test.stem
            run([str(JAVA), "-cp", compiler_classpath,
                 "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                 "-classpath", stdlib, "-d", str(classes), str(source), str(test)])
            run([str(JAVA), "-cp", f"{classes}:{stdlib}",
                 f"org.videolan.vlc.gui.video.{test.stem}Kt"])
    print("PASS: all three host tests use production policy from gostex-vlc.patch")


if __name__ == "__main__":
    main()
