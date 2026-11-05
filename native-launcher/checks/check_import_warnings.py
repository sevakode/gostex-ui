"""Exercise bounded diagnostics and root-failure priority without Android."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/ImportWarnings.kt'
checks = r'''package tv.orbit.shield
fun main() {
 val warnings = ImportWarnings(limit = 3, maxCodePoints = 6)
 check(warnings.snapshot() == (emptyList<String>() to 0))
 var evaluated = 0
 repeat(10000) { warnings.add { evaluated++; "file$it" } }
 check(evaluated == 3)
 check(warnings.snapshot().second == 9997)
 warnings.add(rootFailure = true) { "USB disconnected" }
 val (shown, omitted) = warnings.snapshot()
 check(shown == listOf("USB di…", "file0", "file1") && omitted == 9998)
 val unicode = ImportWarnings(limit = 1, maxCodePoints = 2)
 unicode.add { "🎮🎮abc" }
 check(unicode.snapshot().first.single() == "🎮🎮…")
 check(runCatching { ImportWarnings(limit = 0) }.isFailure)
 println("PASS: bounded lazy warnings, exact count, root priority and Unicode")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-import-warnings-') as temp:
    folder = Path(temp)
    (folder / 'Checks.kt').write_text(checks)
    output = folder / 'classes'
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-classpath', str(COMPILER[1]), '-d', str(output), str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(output) + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
