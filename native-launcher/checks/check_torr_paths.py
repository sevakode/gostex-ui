"""Exercise production torrent path validation without Android or filesystem writes."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
CACHE=ROOT/'.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA=ROOT/'.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
def jar(group,artifact,version):return next((CACHE/group/artifact/version).glob('*/*.jar'))
compiler=[jar('org.jetbrains.kotlin',a,v) for a,v in [('kotlin-compiler-embeddable','2.4.21'),('kotlin-stdlib','2.4.21'),('kotlin-script-runtime','2.4.21'),('kotlin-reflect','1.6.10')]]+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),jar('org.jetbrains','annotations','13.0')]
code='''package tv.orbit.shield
fun main() {
    check(TorrDownloadPaths.components("Game/disc.cue") == listOf("Game", "disc.cue"))
    check(TorrDownloadPaths.components("Игра/Диск 1.bin") == listOf("Игра", "Диск 1.bin"))
    for (bad in listOf("", "/root", "a/", "a//b", "../a", "a/../b", "a/./b", "a\\\\b", "a/b?c", "a/x.", "a/x ", "a/" + "я".repeat(128))) {
        check(runCatching { TorrDownloadPaths.components(bad) }.isFailure) { bad }
    }
    check(runCatching { TorrDownloadPaths.components((1..33).joinToString("/") { "x" }) }.isFailure)
    println("PASS: original nested and Unicode names; traversal, invalid and oversized paths rejected")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-path-test-') as td:
 p=Path(td); (p/'Checks.kt').write_text(code); cp=str(compiler[1]); source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadPaths.kt'
 a=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(p/'classes'),str(source),str(p/'Checks.kt')],capture_output=True,text=True)
 if a.returncode:raise SystemExit(a.stderr)
 b=subprocess.run([str(JAVA),'-cp',f'{p/"classes"}:{cp}','tv.orbit.shield.ChecksKt'],capture_output=True,text=True)
 print(b.stdout,end='')
 if b.returncode:raise SystemExit(b.stderr)
