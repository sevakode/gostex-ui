"""Check fresh and legacy defaults independently of Android package metadata."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/RemoteInstallPolicy.kt'
checks = '''package tv.orbit.shield
fun main() {
 check(legacyRemoteDefault(100, 100) == false)
 check(legacyRemoteDefault(100, 101) == true)
 check(legacyRemoteDefault(0, 100) == null)
 check(legacyRemoteDefault(100, 99) == null)
 check(legacyRemoteDefault(-1, -1) == null)
 println("PASS: fresh install stays off; legacy update keeps historical default")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-remote-policy-') as temp:
    folder = Path(temp)
    (folder / 'Checks.kt').write_text(checks)
    output = folder / 'classes'
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-classpath', str(COMPILER[1]), '-d', str(output), str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(output) + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
