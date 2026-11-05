"""Test bounded extraction only; this does not authenticate or download an APK."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import warnings
import zipfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/LegacyCatalogArchive.kt'
checks = r'''package tv.orbit.shield
import java.io.File
fun main(args: Array<String>) {
 val dir = File(args[0]); val expected = args[1]; val utfDigest = args[2]
 check(LegacyCatalogArchive.extract(File(dir, "valid.apk"), expected) == "synthetic catalog")
 for (name in listOf("missing.apk", "duplicate.apk", "wrong.apk", "oversized.apk", "huge.apk")) {
  check(runCatching { LegacyCatalogArchive.extract(File(dir, name), expected) }.isFailure) { name }
 }
 check(runCatching { LegacyCatalogArchive.extract(File(dir, "invalid-utf.apk"), utfDigest) }.isFailure)
 check(runCatching { LegacyCatalogArchive.extract(File(dir, "valid.apk"), "bad") }.isFailure)
 check(runCatching { LegacyCatalogArchive.extract(File(dir, "valid.apk"), expected) { error("cancel") } }.isFailure)
 if (args.size > 3) check(LegacyCatalogArchive.extract(File(args[3])).isNotBlank())
 println("PASS: bounded historical catalog extraction and digest validation")
}
'''

with tempfile.TemporaryDirectory(prefix='gostex-catalog-archive-') as temp:
    directory = Path(temp)
    def archive(name, entries):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(directory / name, 'w', zipfile.ZIP_DEFLATED) as output:
                for key, value in entries:
                    output.writestr(key, value)
    target = 'assets/catalog.json'
    payload = b'synthetic catalog'
    invalid_utf = b'\xff\xfe'
    archive('valid.apk', [(target, payload), ('assets/other.txt', b'synthetic')])
    archive('missing.apk', [('assets/other.txt', payload)])
    archive('duplicate.apk', [(target, payload), (target, payload)])
    archive('wrong.apk', [(target, b'changed catalog')])
    archive('oversized.apk', [(target, b'x' * (1024 * 1024 + 1))])
    archive('invalid-utf.apk', [(target, invalid_utf)])
    with (directory / 'huge.apk').open('wb') as output:
        output.truncate(64 * 1024 * 1024 + 1)
    (directory / 'Checks.kt').write_text(checks)
    cp = ':'.join(map(str, COMPILER))
    subprocess.run([str(JAVA), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', str(COMPILER[1]), '-d', str(directory / 'classes'), str(source), str(directory / 'Checks.kt')], check=True)
    command = [str(JAVA), '-cp', str(directory / 'classes') + ':' + str(COMPILER[1]),
               'tv.orbit.shield.ChecksKt', str(directory), hashlib.sha256(payload).hexdigest(), hashlib.sha256(invalid_utf).hexdigest()]
    historical = ROOT / '.downloads/gostex/android/0.33.4/gostex-android-arm64-0.33.4.apk'
    if historical.is_file(): command.append(str(historical))
    subprocess.run(command, check=True)
