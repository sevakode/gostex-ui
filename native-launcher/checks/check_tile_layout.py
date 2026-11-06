"""Exercise production tile geometry on the JVM; Compose rendering requires device QA."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TileLayout.kt'

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
stubs = '''package androidx.compose.ui.unit
@JvmInline value class Dp(val value: Float) : Comparable<Dp> {
 override fun compareTo(other: Dp) = value.compareTo(other.value)
 operator fun plus(other: Dp) = Dp(value + other.value)
 operator fun minus(other: Dp) = Dp(value - other.value)
 operator fun times(other: Int) = Dp(value * other)
 operator fun div(other: Int) = Dp(value / other)
 operator fun div(other: Dp) = value / other.value
}
val Int.dp get() = Dp(toFloat())
val Int.sp get() = toFloat()
'''
checks = '''package tv.orbit.shield
import androidx.compose.ui.unit.*
internal enum class TileSize(val columns: Int) { LARGE(4), BALANCED(5), COMPACT(6) }
fun main() {
  val sizes = TileSize.entries
  check(sizes.map { TileLayout.columns(360.dp, it) } == listOf(1, 2, 2))
  check(sizes.map { TileLayout.columns(1920.dp, it) } == listOf(4, 5, 6))
  for (width in 0..2000) {
    val cardWidths = sizes.map { TileLayout.width(width.dp, it).value }
    check(cardWidths[0] >= cardWidths[1] && cardWidths[1] >= cardWidths[2])
  }
  for (size in sizes) {
    var last = 0
    for (width in 0..2000) {
      val n = TileLayout.columns(width.dp, size)
      check(n in 1..size.columns && n >= last)
      check(TileLayout.width(width.dp, size).value >= 1f)
      if (width >= 84 && n > 1) check(TileLayout.width(width.dp, size).value >= when (size) {
        TileSize.LARGE -> 160f; TileSize.BALANCED -> 128f; TileSize.COMPACT -> 96f
      })
      last = n
    }
    check(TileLayout.columns(960.dp, size) == size.columns)
  }
  println("PASS: compact and TV widths, saved size ordering, minimums and monotonic column counts")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-tile-layout-') as td:
    folder = Path(td)
    (folder / 'Unit.kt').write_text(stubs)
    (folder / 'TileLayout.kt').write_text(SOURCE.read_text())
    (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        *map(str, [folder / 'Unit.kt', folder / 'TileLayout.kt', folder / 'Checks.kt'])], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
