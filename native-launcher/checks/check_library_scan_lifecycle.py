"""Reproduce Android stop-before-dispatch cancellation with the production launch policy.

This checks coroutine cleanup deterministically; real Activity transitions need device QA.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/MainActivity.kt"
source = MAIN.read_text()
policy = re.search(r"importJob = importScope\.launch\(start = CoroutineStart\.(\w+)\)", source)
assert policy, "Library scan must enter its cleanup region before lifecycle cancellation"
CHECKS = r'''
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
class QueueDispatcher : CoroutineDispatcher() {
 val pending = java.util.ArrayDeque<Runnable>()
 override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
 fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
}
fun scenario(start: CoroutineStart): Triple<Boolean, Boolean, Boolean> {
 val main = QueueDispatcher(); val io = QueueDispatcher()
 val scope = CoroutineScope(SupervisorJob() + main)
 var scanning = true; var entered = false; var finished = false
 val job = scope.launch(start = start) {
  entered = true
  try { withContext(io) { yield() } }
  finally { scanning = false; finished = true }
 }
 job.cancel()
 repeat(4) { io.drain(); main.drain() }
 check(job.isCompleted)
 scope.cancel()
 return Triple(scanning, entered, finished)
}
fun main() {
 val old = scenario(CoroutineStart.DEFAULT)
 check(old == Triple(true, false, false)); println("PASS: old queued launch reproduces permanent scanning flag: $old")
 val fixed = scenario(CoroutineStart.__PRODUCTION_POLICY__)
 check(fixed == Triple(false, true, true)); println("PASS: undispatched launch runs cleanup after immediate cancellation: $fixed")
}
'''
CHECKS = CHECKS.replace("__PRODUCTION_POLICY__", policy.group(1))
cache = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob("*/*.jar"))
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.21"), jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.21"), jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
java = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
runtime = ":".join(map(str, [compiler[1], compiler[4]]))
report = {"policy": policy.group(1), "sourceSha256": hashlib.sha256(MAIN.read_bytes()).hexdigest(), "scope": __doc__}
with tempfile.TemporaryDirectory(prefix="gostex-library-cancel-") as directory:
    folder = Path(directory)
    test = folder / "CancelBeforeStart.kt"; test.write_text(CHECKS)
    result = subprocess.run([str(java), "-cp", ":".join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"), str(test)], capture_output=True, text=True)
    report.update({"compileCode": result.returncode, "compileOutput": result.stderr})
    if result.returncode == 0:
        result = subprocess.run([str(java), "-cp", str(folder / "classes") + ":" + runtime, "CancelBeforeStartKt"], capture_output=True, text=True)
        report.update({"runCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
out = ROOT / ".reports/library24"; out.mkdir(exist_ok=True)
(out / "cancel-before-start.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
raise SystemExit(result.returncode)
