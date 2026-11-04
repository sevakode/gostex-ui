"""Check production extension parsing/selection and appearance preference compatibility.

Host parser boundary uses Gradle's Groovy JSON parser; real Android bitmap decoding,
rendering, bounded LRU memory and focus are verified separately on the device.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
ASSETS = ROOT / 'native-launcher/app/src/main/assets'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
GRADLE = ROOT / '.tools/native/gradle/gradle-9.8.2/lib'
source = (KOTLIN / 'CoverExtensions.kt').read_text()
production = source[source.index('internal data class CoverExtension('):source.index('/** One small immutable index')]
production += source[source.index('internal data class ResolvedCoverAsset'):]
appearance = (KOTLIN / 'AppearanceOptions.kt').read_text()
preferences = appearance[appearance.index('internal enum class CoverStyle'):appearance.index('internal val LocalCoverStyle')]
preferences += appearance[appearance.index('internal object AppearancePreferences'):appearance.index('/** One listener')]
string_ids = [node.attrib['name'] for node in ET.parse(ROOT / 'native-launcher/app/src/main/res/values/appearance.xml').findall('string')]

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
    }
}
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''
JSON_BOUNDARY = '''package org.json
class JSONObject {
    private val values: Map<*, *>
    constructor(text: String) { values = groovy.json.JsonSlurper().parseText(text) as Map<*, *> }
    private constructor(items: Map<*, *>) { values = items }
    fun opt(key: String): Any? = values[key]
    fun optJSONObject(key: String): JSONObject? = (values[key] as? Map<*, *>)?.let(::JSONObject)
    fun length() = values.size
    fun keys(): MutableIterator<String> = values.keys.map { it as String }.toMutableList().iterator()
}
'''
CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return prefs
    }
}
fun main() = runBlocking {
    var count = 0
    fun pass(name: String) { println("PASS|$name"); count++ }
    val original = "media/original.png"
    val image = "media/generated-covers/extended.webp"
    val valid = linkedMapOf<String, Any?>("image" to image, "sourceName" to "libretro-thumbnails",
        "sourceUrl" to "https://github.com/libretro-thumbnails/repo/blob/hash/Named_Boxarts/Game.png",
        "sourceSha256" to "a".repeat(64), "generationCredit" to "AI extension by GOSTEX", "generatedAt" to "2026-11-03")
    fun manifest(items: Map<String, Any?>, version: Any? = 1): String = groovy.json.JsonOutput.toJson(mapOf("version" to version, "extensions" to items))
    val entry = CoverExtensionCatalog.parse(manifest(mapOf(original to valid))).getValue(original)
    check(entry.original == original && entry.image == image && entry.generationCredit == valid["generationCredit"])
    check(entry.sourceName == valid["sourceName"] && entry.sourceUrl == valid["sourceUrl"] && entry.sourceSha256 == valid["sourceSha256"])
    pass("Valid source-keyed extension preserves distinct original provenance and generation credit")

    for (bad in listOf("", "{", "[]", "null", manifest(mapOf(original to valid), 2), manifest(mapOf(original to valid), "1"), " ".repeat(524289))) {
        check(CoverExtensionCatalog.parse(bad).isEmpty())
    }
    check(CoverExtensionCatalog.parse(manifest((0..512).associate { "media/$it.png" to valid })).isEmpty())
    pass("Malformed, unsupported and oversized manifests return an empty index")

    val invalidPaths = listOf("../media/a.png", "/media/a.png", "media/../a.png", "media//a.png", "media/./a.png", "https://host/a.png", "media/a.svg", "media/a.png?x", "media/é.png")
    for (path in invalidPaths) {
        check(!CoverExtensionCatalog.isImagePath(path))
        check(CoverExtensionCatalog.parse(manifest(mapOf(path to valid))).isEmpty())
        check(CoverExtensionCatalog.parse(manifest(mapOf(original to (valid + ("image" to path))))).isEmpty())
    }
    check(CoverExtensionCatalog.parse(manifest(mapOf(original to (valid + ("image" to original))))).isEmpty())
    pass("Traversal, remote URLs, unsupported formats and self-replacing paths cannot resolve")

    val invalidFields = listOf("sourceName" to " ", "sourceName" to 4, "sourceUrl" to "http://example.com/a", "sourceUrl" to "https://user@host/a", "sourceUrl" to "none",
        "sourceSha256" to "short", "generationCredit" to "", "generationCredit" to true, "generatedAt" to "2026-02-30")
    for ((key, value) in invalidFields) {
        val parsed = CoverExtensionCatalog.parse(manifest(mapOf(original to valid, "media/invalid.png" to (valid + (key to value)))))
        check(parsed.keys == setOf(original))
    }
    for (key in valid.keys) check(CoverExtensionCatalog.parse(manifest(mapOf(original to (valid - key)))).isEmpty())
    pass("Invalid provenance or credit drops only that entry; required metadata is never fabricated")

    val loads = mutableListOf<String>(); var lookups = 0
    suspend fun lookup(path: String): CoverExtension? { lookups++; return entry.takeIf { path == original } }
    val generated = resolveCoverAsset(original, true, ::lookup) { path -> loads += path; "decoded:$path" }
    check(generated.generated && generated.value == "decoded:$image" && loads == listOf(image) && lookups == 1)
    pass("Valid generated art loads directly without decoding the original unnecessarily")

    loads.clear()
    val broken = resolveCoverAsset(original, true, ::lookup) { path -> loads += path; if (path == image) null else "original" }
    check(!broken.generated && broken.value == "original" && loads == listOf(image, original))
    loads.clear()
    val missing = resolveCoverAsset("media/another.png", true, ::lookup) { path -> loads += path; "another" }
    check(!missing.generated && missing.value == "another" && loads == listOf("media/another.png"))
    pass("Corrupt generated image and missing mapping both fall back to the exact requested original")

    loads.clear(); lookups = 0
    val plain = resolveCoverAsset(original, false, ::lookup) { path -> loads += path; "original" }
    check(!plain.generated && plain.value == "original" && loads == listOf(original) && lookups == 0)
    val none = resolveCoverAsset<String>(original, true, ::lookup) { null }
    check(none.value == null && !none.generated)
    pass("Original styles skip the index; a missing original remains empty rather than borrowing another game's art")

    val stopped = runCatching { resolveCoverAsset<String>(original, true, ::lookup) { throw CancellationException("removed card") } }
    check(stopped.exceptionOrNull() is CancellationException)
    pass("Leaving a card propagates coroutine cancellation instead of doing fallback work")

    val prefs = TypedPreferences(); val context = TestContext(prefs)
    check(AppearancePreferences.read(context).style == CoverStyle.GENERATED && prefs.values.isEmpty())
    for (choice in CoverStyle.entries) {
        prefs.values["appearance.coverStyle"] = choice.id
        check(AppearancePreferences.read(context).style == choice && prefs.values["appearance.coverStyle"] == choice.id)
    }
    for (bad in listOf(true, 1, "unknown", "")) {
        prefs.values["appearance.coverStyle"] = bad
        check(AppearancePreferences.read(context).style == CoverStyle.GENERATED && prefs.values["appearance.coverStyle"] == bad)
    }
    pass("Existing ambient/plain/crop choices survive; only missing or invalid values use generated default without writes")

    prefs.values["art.program"] = "mixed"
    AppearancePreferences.setCoverStyle(context, CoverStyle.GENERATED)
    check(AppearancePreferences.read(TestContext(prefs)).style == CoverStyle.GENERATED)
    check(prefs.values["art.program"] == "mixed" && prefs.values.keys == setOf("art.program", "appearance.coverStyle"))
    pass("New appearance choice round-trips through orbit and preserves unrelated settings")

    val beforeBadge = prefs.values.toMap()
    check(AppearancePreferences.read(context).favoriteBadgeVisible)
    check(prefs.values == beforeBadge)
    for (bad in listOf("false", "true", 0, 1, emptySet<String>())) {
        prefs.values["appearance.favoriteBadgeVisible"] = bad
        check(AppearancePreferences.read(context).favoriteBadgeVisible)
        check(prefs.values["appearance.favoriteBadgeVisible"] == bad)
        check(AppearancePreferences.read(context).style == CoverStyle.GENERATED)
    }
    pass("Missing or stale favorite visibility uses a visible default without overwriting saved values")

    prefs.values["home.sections.v1"] = "preserved favorite membership"
    for (visible in listOf(false, true, false)) {
        AppearancePreferences.setFavoriteBadgeVisible(context, visible)
        check(AppearancePreferences.read(TestContext(prefs)).favoriteBadgeVisible == visible)
        check(prefs.values["appearance.favoriteBadgeVisible"] == visible)
        check(prefs.values["appearance.coverStyle"] == CoverStyle.GENERATED.id)
        check(prefs.values["art.program"] == "mixed")
        check(prefs.values["home.sections.v1"] == "preserved favorite membership")
    }
    pass("Favorite visibility persists both choices across readers without changing favorites, artwork, or cover style")

    val beforeTileSize = prefs.values.toMap()
    check(AppearancePreferences.read(context).tileSize == TileSize.LARGE)
    check(AppearancePreferences.read(context).tileSize.columns == 4)
    check(prefs.values == beforeTileSize)
    pass("Existing installations keep four cards per row without creating or migrating a size preference")

    for (bad in listOf(true, 5, "unknown", "", "BALANCED", emptySet<String>())) {
        prefs.values["appearance.tileSize"] = bad
        val options = AppearancePreferences.read(context)
        check(options.tileSize == TileSize.LARGE)
        check(options.style == CoverStyle.GENERATED && !options.favoriteBadgeVisible)
        check(prefs.values == beforeTileSize + ("appearance.tileSize" to bad))
    }
    pass("Unknown or mistyped card sizes use the safe size without overwriting saved values or other appearance choices")

    for (size in listOf(TileSize.BALANCED, TileSize.COMPACT, TileSize.LARGE, TileSize.BALANCED)) {
        AppearancePreferences.setTileSize(context, size)
        val options = AppearancePreferences.read(TestContext(prefs))
        check(options.tileSize == size)
        check(options.style == CoverStyle.GENERATED && !options.favoriteBadgeVisible)
        check(prefs.values == beforeTileSize + ("appearance.tileSize" to size.id))
    }
    pass("All card sizes persist across readers and preserve favorites, artwork, cover style, and badge visibility")
    println("TOTAL|$count")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))


compiler = [jar('org.jetbrains.kotlin', artifact, version) for artifact, version in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
libraries = [compiler[1], compiler[-2], GRADLE / 'groovy-4.0.33.jar', GRADLE / 'groovy-json-4.0.33.jar']
classpath = ':'.join(map(str, libraries))
paths = [KOTLIN / 'CoverExtensions.kt', KOTLIN / 'AppearanceOptions.kt', KOTLIN / 'Artwork.kt', ASSETS / 'cover-extensions.json']
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__,
          'sourceSha256': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths},
          'notCovered': ['Android bitmap decoding', 'Compose aspect-ratio rendering, focus, and live/cold preferences', 'Runtime LRU memory use'], 'passed': False}
with tempfile.TemporaryDirectory(prefix='gostex-cover-extensions-') as folder:
    temporary = Path(folder)
    contents = {
        'Boundary.kt': BOUNDARY, 'JsonBoundary.kt': JSON_BOUNDARY,
        'CoverExtensions.kt': 'package tv.orbit.shield\nimport org.json.JSONObject\nimport java.net.URI\nimport java.time.LocalDate\n' + production,
        'AppearanceOptions.kt': 'package tv.orbit.shield\nimport android.content.Context\nimport android.content.SharedPreferences\n' + preferences,
        'Resources.kt': 'package tv.orbit.shield\ninternal object R { object string { ' + ' '.join(f'const val {name} = {i};' for i, name in enumerate(string_ids)) + ' } }',
        'CoverChecks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                               '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes),
                               *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.CoverChecksKt'], capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 15

# Catalog-source integrity is independent of the parser boundary, including original bytes.
manifest = json.loads((ASSETS / 'cover-extensions.json').read_text())
sources = json.loads((ASSETS / 'media-sources.json').read_text())
asset_errors = []
for original, entry in manifest['extensions'].items():
    source_meta = sources.get(original, {})
    for entry_key, source_key in [('sourceName', 'sourceName'), ('sourceUrl', 'url'), ('sourceSha256', 'sha256')]:
        if entry.get(entry_key) != source_meta.get(source_key): asset_errors.append(f'{original}: mismatch {entry_key}')
    original_file = ASSETS / original
    if not original_file.is_file() or hashlib.sha256(original_file.read_bytes()).hexdigest() != entry['sourceSha256']:
        asset_errors.append(f'{original}: original missing or hash mismatch')
    if not (ASSETS / entry['image']).is_file(): asset_errors.append(f'{original}: generated image missing')
report['assetIntegrity'] = {'entries': len(manifest['extensions']), 'errors': asset_errors}
report['passed'] = report['passed'] and not asset_errors
output = ROOT / '.reports/cover-extensions-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'assets': len(manifest['extensions']), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
