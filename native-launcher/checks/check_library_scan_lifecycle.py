"""Exercise the production scan launch policy and refresh queue on a host JVM.

This checks coroutine cleanup deterministically; real Activity transitions need device QA.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

MAIN = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/MainActivity.kt"
source = MAIN.read_text()
policy = re.search(r"importJob = importScope\.launch\(start = CoroutineStart\.(\w+)\)", source)
assert policy, "Library scan must enter its cleanup region before lifecycle cancellation"
stop = source.split("override fun onStop()", 1)[1].split("override fun onDestroy()", 1)[0]
validation = source.split("private val gameFilePicker", 1)[1].split("fun chooseFolder", 1)[0]
refresh = source.split("fun refreshLibrary(manual:", 1)[1].split("private val picker", 1)[0]
queue = re.search(r"if \(!libraryVisible \|\| scanning \|\| validatingLibraryFile\).*?manualRefreshPending = false",
                  refresh, re.S)
stop_queue = re.search(r"if \(!validatingLibraryFile\)\s*\{\s*refreshPending = false\s*manualRefreshPending = false\s*\}", stop)
drain = re.search(r"private fun drainPendingLibraryRefresh\(\) \{[^{}]*(?:\{[^{}]*\}[^{}]*)?\}", source)
assert queue and stop_queue and drain, "Missing production validation gate or pending refresh queue"
assert re.search(r"finally\s*\{[^{}]*scanning = false\s*drainPendingLibraryRefresh\(\)", refresh)
assert re.search(r"launch\(start = CoroutineStart\.UNDISPATCHED\)\s*\{\s*validatingLibraryFile = true", validation)
assert re.search(r"validatingLibraryFile = false\s*drainPendingLibraryRefresh\(\)", validation)
assert re.search(r"if \(returnedFromStop\) refreshLibrary\(\)", source)
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
object PortableMaintenance { var running = false }
class RefreshQueue {
 var libraryVisible = false
 var scanning = false
 var validatingLibraryFile = true
 var refreshPending = false
 var manualRefreshPending = false
 val importRoot = Job()
 var isDestroyed = false
 val scans = mutableListOf<Boolean>()

 fun refreshLibrary(manual: Boolean = false) {
  __QUEUE__
  scans += reportManualResult
  scanning = true
 }
 __DRAIN__
 fun resume() {
  val returnedFromStop = !libraryVisible
  libraryVisible = true
  if (returnedFromStop) refreshLibrary()
 }
 fun stop() {
  libraryVisible = false
  __STOP_QUEUE__
 }
 fun finishValidation(imported: Boolean) {
  if (imported) refreshLibrary()
  validatingLibraryFile = false
  drainPendingLibraryRefresh()
 }
 fun finishScan() {
  scanning = false
  drainPendingLibraryRefresh()
 }
}
fun checkRefreshQueue() {
 val imported = RefreshQueue()
 imported.resume()
 imported.refreshLibrary(manual = true)
 imported.stop()
 imported.resume()
 check(imported.scans.isEmpty())
 imported.finishValidation(imported = true)
 imported.finishScan()
 check(imported.scans == listOf(true))
 println("PASS: stop/resume plus import and manual refresh coalesce into one manual scan")

 val rejected = RefreshQueue()
 rejected.resume()
 rejected.stop()
 rejected.finishValidation(imported = false)
 check(rejected.scans.isEmpty())
 rejected.resume()
 rejected.finishScan()
 check(rejected.scans == listOf(false))
 println("PASS: rejected file finishes in background and queued refresh runs on resume")

 for (blockDrain in listOf<(RefreshQueue) -> Unit>(
  { it.importRoot.cancel() }, { it.isDestroyed = true }, { PortableMaintenance.running = true }
 )) {
  val canceled = RefreshQueue()
  canceled.resume()
  blockDrain(canceled)
  canceled.finishValidation(imported = false)
  check(canceled.scans.isEmpty())
  PortableMaintenance.running = false
 }
 println("PASS: canceled scope, destroyed activity and maintenance block queued refresh")
}
fun main() {
 val old = scenario(CoroutineStart.DEFAULT)
 check(old == Triple(true, false, false)); println("PASS: old queued launch reproduces permanent scanning flag: $old")
 val fixed = scenario(CoroutineStart.__PRODUCTION_POLICY__)
 check(fixed == Triple(false, true, true)); println("PASS: undispatched launch runs cleanup after immediate cancellation: $fixed")
 checkRefreshQueue()
}
'''
CHECKS = CHECKS.replace("__PRODUCTION_POLICY__", policy.group(1))
CHECKS = CHECKS.replace("__QUEUE__", queue.group(0)).replace("__STOP_QUEUE__", stop_queue.group(0))
CHECKS = CHECKS.replace("__DRAIN__", drain.group(0))
runtime = os.pathsep.join(map(str, [COMPILER[1], COMPILER[4]]))
report = {"policy": policy.group(1), "sourceSha256": hashlib.sha256(MAIN.read_bytes()).hexdigest(), "scope": __doc__}
with tempfile.TemporaryDirectory(prefix="gostex-library-cancel-") as directory:
    folder = Path(directory)
    test = folder / "CancelBeforeStart.kt"; test.write_text(CHECKS)
    result = subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"), str(test)], capture_output=True, text=True)
    report.update({"compileCode": result.returncode, "compileOutput": result.stderr})
    if result.returncode == 0:
        result = subprocess.run([str(JAVA), "-cp", str(folder / "classes") + os.pathsep + runtime, "CancelBeforeStartKt"], capture_output=True, text=True)
        report.update({"runCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
out = ROOT / ".reports/library24"; out.mkdir(exist_ok=True)
(out / "cancel-before-start.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
raise SystemExit(result.returncode)
