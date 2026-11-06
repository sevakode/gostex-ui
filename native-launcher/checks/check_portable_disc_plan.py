"""Exercise actual PS1 descriptor parsing when planning portable file sets."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import java.util.concurrent.CancellationException
fun main() {
    var n = 0
    fun ok(v: Boolean, name: String) { check(v) { name }; n++ }
    fun cue(ref: String) = "FILE \"$ref\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00\n"
    val content = mapOf("Set.m3u" to "Disc 1/game.cue\nDisc 2.chd\n", "Disc 1/game.cue" to cue("../Shared/t.bin"),
        "Shared/t.bin" to "", "Disc 2.chd" to "", "Other.chd" to "")
    val files = content.keys.map { DiscImport.File(it, "id:$it") }
    fun plan(selected: List<String>, listing: List<DiscImport.File> = files, provider: Boolean = true) =
        PortableDiscPlanner.build(listing, selected, { content.getValue(it.path) }, providerSupported = provider)
    val p = plan(listOf("Set.m3u", "Other.chd"))
    ok(p.selected.getValue("Set.m3u").map { it.path } == listOf("Set.m3u", "Disc 1/game.cue", "Shared/t.bin", "Disc 2.chd"), "playlist includes descriptor and all tracks")
    ok(p.selected.getValue("Other.chd").map { it.path } == listOf("Other.chd"), "single-image game")
    ok(p.files.map { it.path } == listOf("Set.m3u", "Disc 1/game.cue", "Shared/t.bin", "Disc 2.chd", "Other.chd"), "complete stable copy list")
    val both = plan(listOf("Set.m3u", "Disc 1/game.cue"))
    ok(both.selected.getValue("Disc 1/game.cue").map { it.path } == listOf("Disc 1/game.cue", "Shared/t.bin"), "selected consumed cue remains addressable")
    ok(both.files.size == 4, "shared files deduplicated")
    ok(plan(emptyList()).files.isEmpty(), "empty selection")
    ok(runCatching { plan(listOf("Set.m3u"), files.filterNot { it.path == "Shared/t.bin" }) }.isFailure, "missing track blocks complete plan")
    ok(runCatching { plan(listOf("Set.m3u"), files + DiscImport.File("Shared/t.bin", "another")) }.isFailure, "duplicate path blocked")
    ok(runCatching { plan(listOf("Set.m3u", "missing.chd")) }.isFailure, "one bad selection blocks plan")
    ok(runCatching { plan(listOf("Set.m3u", "Set.m3u")) }.isFailure, "duplicate selection rejected")
    ok(runCatching { plan(listOf("Set.m3u"), provider = false) }.isFailure, "unsupported provider blocked")
    val alias = files.map { if (it.path == "Shared/t.bin") it.copy(id = "id:Disc 2.chd") else it }
    ok(runCatching { plan(listOf("Set.m3u"), alias) }.isFailure, "provider alias between different paths rejected")
    ok(runCatching { PortableDiscPlanner.build(files, listOf("Set.m3u"), { content.getValue(it.path) }, { throw CancellationException() }) }.exceptionOrNull() is CancellationException, "cancellation propagated")
    val unrelated = files + DiscImport.File("unrelated.cue", "unrelated")
    ok(PortableDiscPlanner.build(unrelated, listOf("Set.m3u"), { if (it.path == "unrelated.cue") error("must not read") else content.getValue(it.path) }).files.size == 4, "unrelated descriptors are not read")
    val defaultScan = DiscImport.inspect(files, { content.getValue(it.path) })
    ok(defaultScan.dependencies.isEmpty(), "normal scan does not build extra maps")
    val broken = listOf(DiscImport.File("g.cue", "g"), DiscImport.File("track.bin", "t"))
    ok(runCatching { PortableDiscPlanner.build(broken, listOf("g.cue"), { cue("../track.bin") }) }.isFailure, "root escape blocked")
    println("PASS: $n portable disc plan checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in ['DiscImport.kt', 'PortablePaths.kt', 'PortableDiscPlan.kt'])), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
