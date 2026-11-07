"""Run production section migration and mutation code against typed preference boundaries.

Uses the cached Kotlin compiler and Gradle JSON parser. Android listener delivery,
disk persistence and TV focus/navigation require separate device verification.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/HomeSections.kt"
HOME = SOURCE.with_name("HomeOptions.kt")
VIDEO = SOURCE.with_name("VideoOptions.kt")
PREFERENCE = SOURCE.with_name("PreferenceReads.kt")
DEFAULTS = ROOT / "native-launcher/app/src/main/res/values/app_categories.xml"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
GRADLE = ROOT / ".tools/native/gradle/gradle-9.8.2/lib"
source = SOURCE.read_text()
production = source[source.index("internal enum class HomeSectionKind"):source.index("@Composable internal fun rememberHomeSections")]
home = HOME.read_text()
home_production = home[home.index("internal enum class HomeOrder"):home.index("/** Selection changes")]
video = VIDEO.read_text()
video_production = video[video.index("internal data class VideoOptions"):video.index("@Composable internal fun rememberVideoOptions")]
preference = PREFERENCE.read_text()
preference_production = preference[preference.index("/** A stale value"):]
defaults = [entry.text for entry in ET.parse(DEFAULTS).findall(".//string-array[@name='video_app_packages']/item")]
strings = sorted(set(re.findall(r"R\.string\.(\w+)", production + home_production)))

BOUNDARY = r'''package android.content
interface SharedPreferences {
    fun contains(key: String): Boolean
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putStringSet(key: String, value: Set<String>): Editor
        fun apply()
    }
}
abstract class Resources { abstract fun getStringArray(id: Int): Array<String> }
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract val resources: Resources
    abstract fun getString(id: Int): String
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''
JSON_BOUNDARY = r'''package org.json
private fun unwrap(value: Any?): Any? = when (value) {
    is JSONArray -> value.values
    is JSONObject -> value.values
    else -> value
}
class JSONArray {
    internal val values: MutableList<Any?>
    constructor() { values = mutableListOf() }
    constructor(text: String?) { values = (groovy.json.JsonSlurper().parseText(text) as List<*>).toMutableList() }
    constructor(items: Collection<*>) { values = items.map(::unwrap).toMutableList() }
    fun length() = values.size
    fun opt(index: Int) = values.getOrNull(index)
    fun optJSONObject(index: Int): JSONObject? = (opt(index) as? Map<*, *>)?.let(::JSONObject)
    fun put(value: Any?): JSONArray { values.add(unwrap(value)); return this }
    override fun toString(): String = groovy.json.JsonOutput.toJson(values)
}
class JSONObject {
    internal val values: MutableMap<String, Any?>
    constructor() { values = mutableMapOf() }
    @Suppress("UNCHECKED_CAST")
    constructor(text: String) { values = (groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>).toMutableMap() }
    @Suppress("UNCHECKED_CAST")
    constructor(items: Map<*, *>) { values = (items as Map<String, Any?>).toMutableMap() }
    fun opt(key: String): Any? = values[key]
    fun optJSONArray(key: String): JSONArray? = (opt(key) as? List<*>)?.let(::JSONArray)
    fun put(key: String, value: Any?): JSONObject { values[key] = unwrap(value); return this }
    override fun toString(): String = groovy.json.JsonOutput.toJson(values)
}
'''
CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.Resources
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object R {
    object array { const val video_app_packages = 1000 }
    object string { RESOURCE_CONSTANTS }
}
private val defaults = DEFAULT_ARRAY
private const val KEY = "home.sections.v1"
private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    var writes = 0
    val touched = mutableListOf<String>()
    override fun contains(key: String) = values.containsKey(key)
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = (values[key] ?: default) as Set<String>?
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, value: Set<String>): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { writes++; touched.addAll(pending.keys); values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override val resources = object : Resources() {
        override fun getStringArray(id: Int): Array<String> { check(id == R.array.video_app_packages); return defaults.copyOf() }
    }
    override fun getString(id: Int): String { check(id == R.string.home_shelf_video); return "Видео" }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return prefs
    }
}
private fun section(context: Context, id: String) = SectionPreferences.read(context).sections.first { it.id == id }

fun main() {
    var count = 0
    fun pass(name: String) { println("PASS|$name"); count++ }
    val prefs = TypedPreferences(); val context = TestContext(prefs)
    val initial = SectionPreferences.read(context)
    check(initial.sections.map { it.id } == listOf("video", "recent", "favorites", "games", "apps"))
    check(initial.sections.first().kind == HomeSectionKind.CUSTOM && initial.sections.first().name == "Видео")
    check(initial.sections.first().items.toSet() == defaults.map(::homeAppKey).toSet())
    check(initial.sections.all { it.visible } && initial.favorites.isEmpty() && prefs.writes == 0)
    pass("Missing document yields read-only legacy defaults; Video is an ordinary named custom section")

    prefs.values.putAll(mapOf("home.shelves.order" to "[\"apps\",\"video\",\"games\",\"favorites\",\"recent\"]",
        "home.shelves.visible" to setOf("video", "games"), "video.visible" to setOf("future.package", "org.videolan.vlc", "mobi.zona"),
        "video.order" to "[\"mobi.zona\",\"removed.hidden.package\",\"future.package\",\"org.videolan.vlc\"]",
        "favorites" to setOf("batman", "removed-game", "game:literal-id"), "language" to "es", "clock.showDate" to false))
    val before = prefs.values.toMap()
    val legacy = SectionPreferences.read(context)
    check(legacy.sections.map { it.id } == listOf("apps", "video", "games", "favorites", "recent"))
    check(legacy.sections.filter { it.visible }.map { it.id } == listOf("video", "games"))
    check(legacy.sections[1].items == listOf("app:mobi.zona", "app:future.package", "app:org.videolan.vlc"))
    check(legacy.favorites.toSet() == setOf("game:batman", "game:removed-game", "game:game:literal-id"))
    check(prefs.values == before && prefs.writes == 0)
    SectionPreferences.ensureMigrated(context)
    check(SectionPreferences.read(TestContext(prefs)) == legacy && prefs.writes == 1)
    check(prefs.values.filterKeys { it != KEY } == before)
    pass("One atomic write migrates saved section order/hidden rows/video priorities/unknown packages/exact favorite ids without altering legacy keys")

    prefs.values["favorites"] = setOf("new-legacy-id")
    prefs.values["home.shelves.visible"] = emptySet<String>()
    prefs.values["video.visible"] = emptySet<String>()
    SectionPreferences.ensureMigrated(context)
    check(SectionPreferences.read(TestContext(prefs)) == legacy && prefs.writes == 1)
    pass("After initialization the canonical document is the sole source; later legacy changes cannot overwrite user sections")

    val emptyPrefs = TypedPreferences(mutableMapOf("home.shelves.visible" to emptySet<String>(), "video.visible" to emptySet<String>()))
    val emptyContext = TestContext(emptyPrefs)
    SectionPreferences.ensureMigrated(emptyContext)
    check(SectionPreferences.read(emptyContext).sections.none { it.visible })
    check(section(emptyContext, "video").items.isEmpty())
    emptyPrefs.values[KEY] = """{"version":1,"sections":[],"favorites":[]}"""
    check(SectionPreferences.read(emptyContext) == HomeSectionsOptions(emptyList(), emptyList()))
    SectionPreferences.ensureMigrated(emptyContext)
    check(SectionPreferences.read(emptyContext).sections.isEmpty())
    pass("Intentionally empty membership, all-hidden rows, and an explicit empty canonical section list stay empty")

    val corrupt = listOf<Any?>(true, 7, "{", "[]", "null", """{"version":2,"sections":[],"favorites":[]}""",
        """{"version":1,"sections":null,"favorites":[]}""", """{"version":1,"sections":[]}""")
    for (value in corrupt) {
        emptyPrefs.values[KEY] = value
        val writes = emptyPrefs.writes
        val fallback = SectionPreferences.read(emptyContext)
        check(fallback.sections.map { it.id } == listOf("video", "recent", "favorites", "games", "apps"))
        check(fallback.favorites.isEmpty() && fallback.sections.first().items.isEmpty())
        SectionPreferences.ensureMigrated(emptyContext)
        check(emptyPrefs.values[KEY] == value && emptyPrefs.writes == writes)
    }
    pass("Malformed, mistyped and future documents use safe defaults without being rewritten or importing stale legacy state")

    for (version in listOf("2", "1.5", "0", "-1")) {
        val document = """{"version":$version,"sections":[{"id":"future","kind":"new-feature","name":"Keep me"}],"favorites":["future:entry"],"extra":"retain"}"""
        emptyPrefs.values[KEY] = document
        val beforeWrites = emptyPrefs.writes
        check(SectionPreferences.create(emptyContext, "New section") == null)
        check(!SectionPreferences.setVisible(emptyContext, "apps", false))
        check(!SectionPreferences.toggleFavorite(emptyContext, "app:new"))
        check(emptyPrefs.values[KEY] == document && emptyPrefs.writes == beforeWrites)
    }
    pass("Unsupported numeric schema versions reject mutations, preserving every byte instead of replacing future data with defaults")

    emptyPrefs.values[KEY] = """{"version":1,"sections":[
      {"id":"mine","kind":"custom","name":"  Movies   and games  ","items":["app:gone","game:gone","app:gone",42,"bad:tag","app:"],"visible":false},
      {"id":"mine","kind":"custom","name":"duplicate"},{"id":"broken","kind":"unknown"},
      {"id":"favorite","kind":"favorites","items":["app:ignored"]},{"id":"favorite2","kind":"favorites"},
      {"id":"blank","kind":"custom","name":"  "},42],"favorites":["app:gone","game:gone","app:gone",false,"bad",""]}"""
    val parsed = SectionPreferences.read(emptyContext)
    check(parsed.sections.size == 2 && parsed.sections.first().name == "Movies and games")
    check(!parsed.sections.first().visible && parsed.sections.first().items == listOf("app:gone", "game:gone"))
    check(parsed.sections.last().items.isEmpty() && parsed.favorites == listOf("app:gone", "game:gone"))
    pass("Parser removes malformed entries/duplicate ids/types/items while retaining unknown installed content and stable mixed-item order")

    val custom = requireNotNull(SectionPreferences.create(context, "  My   section  "))
    val second = requireNotNull(SectionPreferences.create(context, "My section"))
    check(custom != second && section(context, custom).name == "My section")
    check(SectionPreferences.create(context, " ") == null && SectionPreferences.create(context, "line\nbreak") == null)
    check(SectionPreferences.create(context, "x".repeat(65)) == null)
    check(SectionPreferences.create(context, "🎮".repeat(64)) != null)
    check(SectionPreferences.create(context, "🎮".repeat(65)) == null)
    check(SectionPreferences.rename(context, "video", "Cinema"))
    check(section(context, "video").name == "Cinema")
    check(!SectionPreferences.rename(context, "games", "Not a custom section"))
    check(!SectionPreferences.rename(context, custom, ""))
    pass("Custom sections use stable unique ids and validated Unicode names; migrated Video can be renamed like any custom section")

    val selected = mutableListOf("app:one", "game:one", "app:future.package", "app:one", "bad:entry")
    check(SectionPreferences.setItems(context, custom, selected))
    selected.clear()
    check(section(context, custom).items == listOf("app:one", "game:one", "app:future.package"))
    check(!SectionPreferences.setItems(context, "games", listOf("app:one")))
    check(SectionPreferences.setItems(context, second, listOf("app:two", "game:one")))
    check(SectionPreferences.setMembership(context, "game:one", setOf(custom, second, "unknown-section")) == false)
    check(section(context, custom).items == listOf("app:one", "game:one", "app:future.package"))
    check(SectionPreferences.setMembership(context, "game:one", setOf(second)))
    check(section(context, custom).items == listOf("app:one", "app:future.package"))
    check(section(context, second).items == listOf("app:two", "game:one"))
    check(SectionPreferences.setMembership(context, "app:one", setOf(custom, second)))
    check(section(context, second).items == listOf("app:two", "game:one", "app:one"))
    check(!SectionPreferences.setMembership(context, "bad", setOf(custom)))
    pass("Membership changes accept games/apps, preserve unknown entries and retained positions, append new members, and detach mutable inputs")

    check(SectionPreferences.prioritize(context, second, "app:one"))
    check(section(context, second).items == listOf("app:one", "app:two", "game:one"))
    check(!SectionPreferences.prioritize(context, second, "app:absent"))
    check(!SectionPreferences.prioritize(context, second, "app:one"))
    check(SectionPreferences.setItems(context, custom, emptyList()))
    check(section(TestContext(prefs), custom).items.isEmpty())
    pass("Prioritization is stable and cannot add absent items; explicitly clearing a custom section persists")

    val favoritesBefore = SectionPreferences.read(context).favorites
    check(SectionPreferences.toggleFavorite(context, "app:org.videolan.vlc"))
    check(SectionPreferences.toggleFavorite(context, "game:new-game"))
    check(SectionPreferences.read(context).favorites == favoritesBefore + listOf("app:org.videolan.vlc", "game:new-game"))
    check(SectionPreferences.toggleFavorite(context, "game:batman"))
    check("game:batman" !in SectionPreferences.read(context).favorites)
    check("game:removed-game" in SectionPreferences.read(context).favorites)
    check(!SectionPreferences.toggleFavorite(context, "app:") && !SectionPreferences.toggleFavorite(context, "untyped"))
    check(SectionPreferences.read(context) == SectionPreferences.read(TestContext(prefs)))
    pass("A shared ordered favorite list accepts both content types and preserves stale favorite ids across new contexts")

    val orderPrefs = TypedPreferences(mutableMapOf(KEY to """{"version":1,"sections":[{"id":"mine","kind":"custom","name":"My list","items":["app:keep"],"visible":false}],"favorites":["game:same","app:same","game:unavailable","app:last"]}"""))
    val orderContext = TestContext(orderPrefs)
    val sectionsBeforeReorder = SectionPreferences.read(orderContext).sections
    check(SectionPreferences.moveFavorite(orderContext, "app:same", -1))
    check(SectionPreferences.read(orderContext).favorites == listOf("app:same", "game:same", "game:unavailable", "app:last"))
    check(SectionPreferences.prioritizeFavorite(orderContext, "game:unavailable"))
    check(SectionPreferences.read(orderContext).favorites == listOf("game:unavailable", "app:same", "game:same", "app:last"))
    check(SectionPreferences.placeFavoriteLast(orderContext, "game:unavailable"))
    check(SectionPreferences.read(orderContext).favorites == listOf("app:same", "game:same", "app:last", "game:unavailable"))
    check(SectionPreferences.moveFavorite(orderContext, "app:same", Int.MAX_VALUE))
    check(SectionPreferences.read(orderContext).favorites == listOf("game:same", "app:same", "app:last", "game:unavailable"))
    check(SectionPreferences.moveFavorite(orderContext, "app:same", Int.MIN_VALUE))
    check(SectionPreferences.read(orderContext).favorites == listOf("app:same", "game:same", "app:last", "game:unavailable"))
    val reloaded = TestContext(TypedPreferences(orderPrefs.values.toMutableMap()))
    check(SectionPreferences.read(reloaded) == SectionPreferences.read(orderContext))
    check(SectionPreferences.read(reloaded).sections == sectionsBeforeReorder)
    check(orderPrefs.touched.all { it == KEY })
    pass("Favorite movement preserves mixed app/game identity, unavailable references and section state; exact order survives serialized preference reload")

    val boundedWrites = orderPrefs.writes
    check(!SectionPreferences.moveFavorite(orderContext, "app:same", -1))
    check(!SectionPreferences.prioritizeFavorite(orderContext, "app:same"))
    check(!SectionPreferences.moveFavorite(orderContext, "game:unavailable", 1))
    check(!SectionPreferences.placeFavoriteLast(orderContext, "game:unavailable"))
    check(!SectionPreferences.moveFavorite(orderContext, "game:same", 0))
    check(!SectionPreferences.moveFavorite(orderContext, "app:missing", 1))
    check(!SectionPreferences.prioritizeFavorite(orderContext, "app:missing"))
    check(!SectionPreferences.placeFavoriteLast(orderContext, "app:missing"))
    check(orderPrefs.writes == boundedWrites)
    val noFavoritesPrefs = TypedPreferences(mutableMapOf(KEY to """{"version":1,"sections":[],"favorites":[]}"""))
    val noFavoritesContext = TestContext(noFavoritesPrefs)
    check(!SectionPreferences.moveFavorite(noFavoritesContext, "game:none", 1))
    check(!SectionPreferences.prioritizeFavorite(noFavoritesContext, "game:none"))
    check(!SectionPreferences.placeFavoriteLast(noFavoritesContext, "game:none"))
    check(noFavoritesPrefs.writes == 0)
    SectionPreferences.toggleFavorite(noFavoritesContext, "game:only")
    val oneFavoriteWrites = noFavoritesPrefs.writes
    check(!SectionPreferences.moveFavorite(noFavoritesContext, "game:only", 1))
    check(!SectionPreferences.prioritizeFavorite(noFavoritesContext, "game:only"))
    check(!SectionPreferences.placeFavoriteLast(noFavoritesContext, "game:only"))
    check(noFavoritesPrefs.writes == oneFavoriteWrites)
    pass("Boundary, zero-offset, missing, empty and single-favorite movement never writes or invents favorite membership")

    val dialogSnapshot = SectionPreferences.read(orderContext).favorites
    val selectedFromDialog = dialogSnapshot[1]
    check(SectionPreferences.toggleFavorite(orderContext, "app:last"))
    check(SectionPreferences.toggleFavorite(orderContext, "app:new-while-dialog-open"))
    check(SectionPreferences.placeFavoriteLast(orderContext, selectedFromDialog))
    check(SectionPreferences.read(orderContext).favorites == listOf("app:same", "game:unavailable", "app:new-while-dialog-open", "game:same"))
    check(SectionPreferences.toggleFavorite(orderContext, selectedFromDialog))
    val removedWhileOpen = SectionPreferences.read(orderContext)
    val beforeStaleAction = orderPrefs.writes
    check(!SectionPreferences.moveFavorite(orderContext, selectedFromDialog, -1))
    check(!SectionPreferences.prioritizeFavorite(orderContext, selectedFromDialog))
    check(!SectionPreferences.placeFavoriteLast(orderContext, selectedFromDialog))
    check(SectionPreferences.read(orderContext) == removedWhileOpen && orderPrefs.writes == beforeStaleAction)
    pass("Actions from a stale dialog resolve the current list by key, retaining later additions and removals without resurrecting a removed selection")

    val priorOrder = SectionPreferences.read(context).sections.map { it.id }
    val first = priorOrder.first()
    check(!SectionPreferences.move(context, first, -1))
    check(SectionPreferences.move(context, first, 1))
    check(SectionPreferences.read(context).sections.map { it.id } == listOf(priorOrder[1], first) + priorOrder.drop(2))
    check(SectionPreferences.setVisible(context, "apps", false) || !section(context, "apps").visible)
    check(!section(context, "apps").visible)
    val beforeUnknown = prefs.writes
    check(!SectionPreferences.move(context, "unknown", 1) && !SectionPreferences.setVisible(context, "unknown", false))
    check(prefs.writes == beforeUnknown)
    pass("All sections can be reordered/hidden; boundary and unknown-id actions do not write or reset other options")

    val favoritesSaved = SectionPreferences.read(context).favorites
    check(SectionPreferences.delete(context, "video"))
    check(SectionPreferences.read(context).sections.none { it.id == "video" })
    SectionPreferences.ensureMigrated(context)
    check(SectionPreferences.read(context).sections.none { it.id == "video" })
    check(SectionPreferences.read(context).favorites == favoritesSaved)
    check(!SectionPreferences.delete(context, "games") && !SectionPreferences.delete(context, "unknown"))
    check(prefs.touched.all { it == KEY })
    pass("Deleting ordinary Video is permanent, never deletes favorites/apps/games, and every new write touches only the canonical document")

    val limitPrefs = TypedPreferences(mutableMapOf(KEY to """{"version":1,"sections":[],"favorites":[]}"""))
    val limitContext = TestContext(limitPrefs)
    repeat(64) { check(SectionPreferences.create(limitContext, "Section $it") != null) }
    val limitBefore = limitPrefs.values.toMap()
    check(SectionPreferences.create(limitContext, "Overflow") == null)
    check(limitPrefs.values == limitBefore && SectionPreferences.read(limitContext).sections.size == 64)
    pass("A bounded section count rejects overflow without truncating or losing prior user sections")

    val mediaPrefs = TypedPreferences(mutableMapOf(KEY to """{"version":1,"sections":[],"favorites":[]}"""))
    val mediaContext = TestContext(mediaPrefs)
    val mediaId = checkNotNull(SectionPreferences.create(mediaContext, "My media"))
    check(SectionPreferences.setMembership(mediaContext, homeMovieKey("tt123"), setOf(mediaId)))
    check(SectionPreferences.toggleFavorite(mediaContext, homeMovieKey("tt123")))
    val mediaReload = SectionPreferences.read(TestContext(mediaPrefs))
    check(mediaReload.sections.single().items == listOf("movie:tt123"))
    check(mediaReload.favorites == listOf("movie:tt123"))
    check(!SectionPreferences.setMembership(mediaContext, "movie:", setOf(mediaId)))
    pass("Media membership and favorites survive document reload while malformed keys are rejected")

    check(listOf(null, KEY).all(SectionPreferences::changes))
    check(listOf("favorites", "home.shelves.visible", "video.visible", "language", "home.selectedGame").none(SectionPreferences::changes))
    pass("Reactive invalidation watches only the canonical key/clear, ignoring obsolete and unrelated sources")
    println("TOTAL|$count")
}
'''.replace("RESOURCE_CONSTANTS", "; ".join(f"const val {name} = {index}" for index, name in enumerate(strings))) \
    .replace("DEFAULT_ARRAY", "arrayOf(" + ", ".join(json.dumps(item) for item in defaults) + ")")


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
libraries = [compiler[1], GRADLE / "groovy-4.0.33.jar", GRADLE / "groovy-json-4.0.33.jar"]
classpath = ":".join(map(str, libraries))
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [SOURCE, HOME, VIDEO, PREFERENCE]},
    "notCovered": ["Android JSON parser differences", "Android disk persistence/cold process restart", "Compose observer delivery and focus after section mutations"],
    "passed": False,
}
# Check the actual observer has balanced lifecycle and migrates after subscription; this
# structural guard complements behavioral tests without pretending to execute Compose.
observer = source[source.index("@Composable internal fun rememberHomeSections"):]
report["observerStructure"] = (
    observer.index("registerOnSharedPreferenceChangeListener(listener)") < observer.index("SectionPreferences.ensureMigrated(context)")
    and "onDispose { saved.unregisterOnSharedPreferenceChangeListener(listener) }" in observer
)
with tempfile.TemporaryDirectory(prefix="gostex-home-sections-") as folder:
    temporary = Path(folder)
    contents = {
        "Boundary.kt": BOUNDARY,
        "JsonBoundary.kt": JSON_BOUNDARY,
        "Production.kt": "package tv.orbit.shield\nimport android.content.Context\nimport android.content.SharedPreferences\nimport org.json.JSONArray\nimport org.json.JSONObject\nimport java.util.Locale\nimport java.util.UUID\n"
            + production + "\n" + home_production + "\n" + video_production + "\n" + preference_production,
        "SectionChecks.kt": CHECKS,
    }
    for name, value in contents.items():
        (temporary / name).write_text(value)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath,
        "-d", str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{classpath}", "tv.orbit.shield.SectionChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 19 and report["observerStructure"]

output = ROOT / ".reports/home-sections-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(report)
    raise SystemExit(1)
