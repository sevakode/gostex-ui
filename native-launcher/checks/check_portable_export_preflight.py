"""Verify 3DS installed-title preflight without Android or user data."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/PortableExportPreflight.kt'
checks = r'''package tv.orbit.shield
fun main() {
    fun item(platform: String, vararg names: String) = PortableExportItem(platform, names.toList())
    PortableExportPreflight.validate(emptyList())
    PortableExportPreflight.validate(listOf(item("Nintendo 3DS", "game.3ds", "game.cia"), item("PSP", "something.app")))
    val mixed = listOf(item("Nintendo 3DS", "00000000.APP", "00000001.app"), item("Nintendo 3DS", "game.3ds"),
        item("Nintendo 3DS", "00000002.app"), item("PSP", "other.app"))
    val error = runCatching { PortableExportPreflight.validate(mixed) }.exceptionOrNull()
    check(error is PortableInstalled3dsUnsupported && error.gameCount == 2)
    check(runCatching { PortableExportPreflight.validate(listOf(item("Nintendo 3DS", "file.app.backup"))) }.isSuccess)
    println("PASS: installed 3DS preflight rejects unsupported titles without counting variants twice")
}'''
with tempfile.TemporaryDirectory() as temp:
    test = Path(temp) / 'Checks.kt'
    test.write_text(checks)
    output = Path(temp) / 'classes'
    runtime = str(COMPILER[1])
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(output), str(source), str(test)], check=True)
    subprocess.run([str(JAVA), '-cp', str(output) + ':' + runtime, 'tv.orbit.shield.ChecksKt'], check=True)
