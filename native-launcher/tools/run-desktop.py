#!/usr/bin/env python3
"""Run/build the opt-in desktop modules with local or installed Gradle + JDK 21."""
from pathlib import Path
import os
import shutil
import subprocess
import sys

project = Path(__file__).resolve().parents[1]
workspace = project.parent
env = os.environ.copy()
bundled = workspace / '.tools/native'
jdks = list((bundled / 'jdk').glob('*/Contents/Home'))
if not env.get('JAVA_HOME') and jdks:
    env['JAVA_HOME'] = str(jdks[0])
local_gradle = bundled / 'gradle/gradle-9.8.2/bin/gradle'
gradle = str(local_gradle) if local_gradle.exists() else shutil.which('gradle')
if not gradle:
    sys.exit('Gradle is required. Install Gradle and JDK 21 or use the project bundled tools.')
if local_gradle.exists():
    env['GRADLE_USER_HOME'] = str(bundled / 'gradle-cache')
args = sys.argv[1:] or [':desktop:run']
sys.exit(subprocess.call([gradle, '--no-daemon', '-Pgostex.desktop=true', *args], cwd=project, env=env))
