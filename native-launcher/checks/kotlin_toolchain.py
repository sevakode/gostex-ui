"""Locate the Kotlin compiler already downloaded by the Gradle build."""
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
local_gradle = ROOT / ".tools/native/gradle-cache"
gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or (local_gradle if local_gradle.is_dir() else Path.home() / ".gradle"))
CACHE = gradle_home / "caches/modules-2/files-2.1"
local_jdks = sorted((ROOT / ".tools/native/jdk").glob("*/Contents/Home"))
java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else (local_jdks[0] if local_jdks else None)
if java_home is None:
    raise RuntimeError("Set JAVA_HOME to the JDK used by Gradle")
JAVA = java_home / "bin/java"


def jar(group: str, artifact: str, version: str) -> Path:
    matches = sorted(p for p in (CACHE / group / artifact / version).glob("*/*.jar")
                     if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
    if not matches:
        raise RuntimeError(f"Missing {group}:{artifact}:{version}; run the Gradle build first ({CACHE})")
    return matches[0]


COMPILER = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    jar("org.jetbrains", "annotations", "13.0"),
]
