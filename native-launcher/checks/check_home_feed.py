"""Run unchanged production shelf composition and models on the host JVM.

Only Android string resource identifiers are stubbed. This verifies selection,
ordering, empty states and stable identity; Compose focus and input need device QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"


def extract(filename, start, end):
    source = (SOURCES / filename).read_text()
    assert source.count(start) == 1, (filename, start)
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


sections = {
    "home_section_models": extract("HomeSections.kt", "internal enum class HomeSectionKind", "/** One document owns"),
    "shelf_composition": extract("HomeFeed.kt", "internal sealed interface HomeTileItem", "internal data class HomeFeedRequest"),
    "game_model": extract("MainActivity.kt", "data class Game(", "private data class PortablePreviewDecisionSession"),
    "movie_model": extract("MovieLibraryStore.kt", "internal data class MovieSource", "/** User collection"),
    "app_model": extract("AppArtwork.kt", "data class TvApp(", "/** Call off the UI thread."),
}
production = (SOURCES / "GameGrouping.kt").read_text().split("import java.util.Locale", 1)[1].replace("Locale.ROOT", "java.util.Locale.ROOT") + "\n" + "\n".join(sections.values())
resources = sorted(set(re.findall(r"R\.string\.(\w+)", production)))
resource_stub = "internal object R { object string {\n" + "\n".join(
    f"const val {name} = {number}" for number, name in enumerate(resources)
) + "\n} }\n"

CHECKS = r'''package tv.orbit.shield

private fun game(id: String, title: String = id) = Game(id, title, "snes", "emulator", "component",
    listOf("$id.sfc"), "tree", "cover/$id", "scene/$id", "Adventure", "1992", "Studio", "1")
private fun List<HomeShelfContent>.row(id: String) = single { it.shelf == id }
private fun HomeShelfContent.games() = items.filterIsInstance<HomeTileItem.GameItem>().map { it.game }
private fun HomeShelfContent.apps() = items.filterIsInstance<HomeTileItem.AppItem>().map { it.app }
private fun builtins() = listOf(
    HomeSection("recent", HomeSectionKind.RECENT),
    HomeSection("favorites", HomeSectionKind.FAVORITES),
    HomeSection("games", HomeSectionKind.GAMES),
    HomeSection("apps", HomeSectionKind.APPS)
)

fun main() {
    var count = 0
    fun pass(name: String) { println("PASS|$name"); count++ }
    val newest = game("newest", "New adventure")
    val tieB = game("tie-b", "First by title")
    val unplayed = game("unplayed", "Never launched")
    val tieA = game("tie-a", "Last by title")
    val invalid = game("invalid", "Negative timestamp")
    val zero = game("zero", "Zero timestamp")
    val games = listOf(unplayed, tieB, newest, tieA, invalid, zero)
    val smartTube = TvApp("SmartTube", "org.smarttube.stable", "TvMain", 10L, 11L)
    val zona = TvApp("Zona", "mobi.zona", "Main", 20L, 21L)
    val explorer = TvApp("Explorer", "com.explorer", "Explorer", 30L, 31L)
    val apps = listOf(explorer, zona, smartTube)
    val video = HomeSection("video", HomeSectionKind.CUSTOM, "Video", listOf(homeAppKey(smartTube.packageName), homeAppKey(zona.packageName)))
    val favorites = listOf(homeGameKey(tieA.id), homeAppKey(zona.packageName), homeGameKey(unplayed.id), homeGameKey("removed-game"))
    val options = HomeSectionsOptions(listOf(video) + builtins(), favorites)
    val played = mapOf("newest" to 900L, "tie-b" to 200L, "tie-a" to 200L,
        "zero" to 0L, "invalid" to -4L, "removed-game" to 2000L)
    val rows = homeShelfContents(options, games, apps, played)
    check(rows.map { it.shelf } == listOf("video", "recent", "favorites", "games", "apps"))
    check(rows.all { it.keys.isNotEmpty() })
    check(rows.row("video").section === video)
    pass("A populated home follows the saved section order and retains its custom section model")

    val film = SavedMovie("tt123", "A movie", "2024", null)
    val mediaSection = HomeSection("media-custom", HomeSectionKind.CUSTOM, "Media", listOf(homeMovieKey(film.id)))
    val mediaOptions = HomeSectionsOptions(listOf(mediaSection) + builtins(), listOf(homeMovieKey(film.id)))
    val mediaRows = homeShelfContents(mediaOptions, games, apps, played, movies = listOf(film, film), moviePlayed = mapOf(film.id to 1000L))
    check(mediaRows.row("media-custom").keys == listOf(homeMovieKey(film.id)))
    check(mediaRows.row("favorites").keys == listOf(homeMovieKey(film.id)))
    check(mediaRows.row("recent").keys.first() == homeMovieKey(film.id))
    check(mediaRows.row("recent").keys.count { it == homeMovieKey(film.id) } == 1)
    check(homeShelfContents(mediaOptions, games, apps, played, movies = listOf(film)).none { it.shelf == "recent" && homeMovieKey(film.id) in it.keys })
    pass("Movie tiles preserve identity in custom shelves, favorites and mixed recents")

    val nativeGame = TvApp("Native game", "example.native.game", isGame = true)
    val withNative = homeShelfContents(options, games, apps + nativeGame, played)
    check(homeAppKey(nativeGame.packageName) in withNative.row("games").keys)
    check(homeAppKey(nativeGame.packageName) in withNative.row("apps").keys)
    val hiddenNative = homeShelfContents(options, games, apps + nativeGame, played, includeAndroidGames = false)
    check(homeAppKey(nativeGame.packageName) !in hiddenNative.row("games").keys)
    check(homeAppKey(nativeGame.packageName) in hiddenNative.row("apps").keys)
    pass("Android games appear in games with their app identity and respect the library preference")

    val recent = rows.row("recent").games()
    check(recent == listOf(newest, tieA, tieB))
    check(recent.all { (played[it.id] ?: 0L) > 0L })
    pass("Recent includes positive known play records, newest first with stable id tie breaking")

    val mixedKeys = listOf(homeAppKey(zona.packageName), homeGameKey(tieB.id), homeAppKey(smartTube.packageName), homeGameKey(tieA.id))
    val mixed = HomeSection("custom-mixed", HomeSectionKind.CUSTOM, "My shelf", mixedKeys + mixedKeys.take(2) + listOf("app:missing", "game:missing", "unknown"))
    val mixedOptions = HomeSectionsOptions(listOf(mixed), emptyList())
    val mixedRow = homeShelfContents(mixedOptions, games, apps, played).single()
    check(mixedRow.keys == mixedKeys)
    check(mixedRow.items.map { it.key } == mixedKeys)
    check((mixedRow.items[0] as HomeTileItem.AppItem).app === zona)
    check((mixedRow.items[1] as HomeTileItem.GameItem).game === tieB)
    check((mixedRow.items[2] as HomeTileItem.AppItem).app === smartTube)
    check((mixedRow.items[3] as HomeTileItem.GameItem).game === tieA)
    pass("Custom sections interleave games and apps in saved order, deduplicate references and omit unresolved keys")

    val favoriteRow = rows.row("favorites")
    check(favoriteRow.keys == favorites.take(3))
    check(favoriteRow.games() == listOf(tieA, unplayed))
    check(favoriteRow.apps() == listOf(zona))
    val duplicateFavorites = homeShelfContents(options.copy(favorites = favorites + favorites), games, apps, played)
    check(duplicateFavorites.row("favorites").items == favoriteRow.items)
    pass("Shared favorites retain their own mixed insertion order and discard duplicate visible references")

    check(rows.row("games").games() == games)
    check(rows.row("games").games().indices.all { rows.row("games").games()[it] === games[it] })
    check(rows.row("apps").apps() == apps)
    check(rows.row("apps").apps().indices.all { rows.row("apps").apps()[it] === apps[it] })
    check(rows.row("video").apps() == listOf(smartTube, zona))
    check(rows.row("video").games().isEmpty() && rows.row("games").apps().isEmpty())
    pass("All games and apps preserve source order and objects while custom membership orders them independently")

    check(homeShelfContents(options, emptyList(), emptyList(), played).isEmpty())
    val gameOnly = homeShelfContents(options.copy(favorites = emptyList()), games, emptyList(), emptyMap())
    check(gameOnly.map { it.shelf } == listOf("games"))
    val appOnly = homeShelfContents(HomeSectionsOptions(builtins(), emptyList()), emptyList(), apps, played)
    check(appOnly.map { it.shelf } == listOf("apps"))
    check(homeShelfContents(mixedOptions, emptyList(), emptyList(), played).isEmpty())
    pass("Empty rows disappear without displaying stale favorites, missing members or stale play records")

    val reordered = options.copy(sections = listOf(options.sections.last(), video.copy(visible = false), options.sections[1]))
    val chosen = homeShelfContents(reordered, games, apps, played)
    check(chosen.map { it.shelf } == listOf("apps", "recent"))
    check(homeShelfContents(reordered.copy(sections = reordered.sections.map { it.copy(visible = false) }), games, apps, played).isEmpty())
    pass("Explicit section order and visibility are honored, including every section hidden")

    val customOnly = homeShelfContents(mixedOptions, games, apps, played)
    check(customOnly.map { it.shelf } == listOf("custom-mixed"))
    check(homeShelfContents(HomeSectionsOptions(emptyList(), favorites), games, apps, played).isEmpty())
    val hiddenApps = options.copy(sections = options.sections.map { if (it.kind == HomeSectionKind.APPS) it.copy(visible = false) else it })
    check(homeShelfContents(hiddenApps, games, apps + TvApp("New app", "new.app"), played).none { it.shelf == "apps" })
    pass("Removed sections are never appended and app refresh cannot resurrect the hidden all-apps row")

    val beforeItems = mixed.items.toList()
    val beforeFavorites = options.favorites.toList()
    val withoutZona = apps.filterNot { it.packageName == zona.packageName }
    val missingCustom = homeShelfContents(mixedOptions, games, withoutZona, played).single()
    check(missingCustom.keys == mixedKeys.filterNot { it == homeAppKey(zona.packageName) })
    val missingFavorite = homeShelfContents(options, games, withoutZona, played).row("favorites")
    check(missingFavorite.keys == listOf(homeGameKey(tieA.id), homeGameKey(unplayed.id)))
    check(mixed.items == beforeItems && options.favorites == beforeFavorites)
    val restoredZona = zona.copy(name = "Zona updated", versionCode = 55L)
    val installed = withoutZona + restoredZona
    val restoredCustom = homeShelfContents(mixedOptions, games, installed, played).single()
    check(restoredCustom.keys == mixedKeys)
    check((restoredCustom.items[0] as HomeTileItem.AppItem).app === restoredZona)
    check(homeShelfContents(options, games, installed, played).row("favorites").keys == favorites.take(3))
    pass("Uninstall and reinstall filter and restore members at their saved positions without changing preferences")

    val newerAppRecord = smartTube.copy(name = "Second activity", activityName = "AlternateMain")
    val duplicateInputs = homeShelfContents(options,
        games + listOf(tieA.copy(title = "Duplicate record"), unplayed),
        listOf(explorer, zona, smartTube, newerAppRecord, explorer), played)
    check(duplicateInputs.row("apps").apps() == apps)
    check(duplicateInputs.row("video").apps() == listOf(smartTube, zona))
    check(duplicateInputs.row("games").games() == games)
    check(duplicateInputs.row("recent").games() == recent)
    check(duplicateInputs.all { it.keys.size == it.keys.distinct().size })
    pass("Duplicate source records keep the first game id or app package and cannot create duplicate lazy keys")

    val sharedIdGame = game(smartTube.packageName)
    val collisionKeys = listOf(homeAppKey(smartTube.packageName), homeGameKey(sharedIdGame.id))
    val collisionOptions = HomeSectionsOptions(listOf(
        HomeSection("favorites", HomeSectionKind.FAVORITES),
        HomeSection("both", HomeSectionKind.CUSTOM, "Both", collisionKeys)
    ), collisionKeys)
    val namespaces = homeShelfContents(collisionOptions, listOf(sharedIdGame), listOf(smartTube), emptyMap())
    check(namespaces.all { it.keys == collisionKeys && it.items.size == 2 })
    check(namespaces.all { it.items[0] is HomeTileItem.AppItem && it.items[1] is HomeTileItem.GameItem })
    pass("Identical raw game ids and app packages stay distinct in the same favorites and custom sections")

    val largeLibrary = (0 until 70).map { game("game-$it") }
    val largeOptions = HomeSectionsOptions(builtins(), largeLibrary.map { homeGameKey(it.id) })
    val largeRows = homeShelfContents(largeOptions, largeLibrary, apps, largeLibrary.associate { it.id to 100L })
    check(largeRows.row("games").games().size == 70)
    check(largeRows.row("favorites").games().size == 70)
    check(largeRows.row("recent").games().size == 70)
    check(largeRows.row("games").games() == largeLibrary)
    pass("The complete library remains reachable without arbitrary row truncation")

    val mutableGames = games.toMutableList(); val beforeGames = mutableGames.toList()
    val mutableApps = apps.toMutableList(); val beforeApps = mutableApps.toList()
    val mutableSections = options.sections.toMutableList(); val beforeSections = mutableSections.toList()
    val mutableFavorites = favorites.toMutableList(); val beforeSharedFavorites = mutableFavorites.toList()
    val mutablePlayed = played.toMutableMap(); val beforePlayed = mutablePlayed.toMap()
    val mutableOptions = HomeSectionsOptions(mutableSections, mutableFavorites)
    val first = homeShelfContents(mutableOptions, mutableGames, mutableApps, mutablePlayed)
    val second = homeShelfContents(mutableOptions, mutableGames, mutableApps, mutablePlayed)
    check(first == second && first == rows)
    check(mutableGames == beforeGames && mutableApps == beforeApps && mutableSections == beforeSections)
    check(mutableFavorites == beforeSharedFavorites && mutablePlayed == beforePlayed)
    pass("Composing rows is repeatable and never mutates caller-owned content, section or preference collections")

    val version1 = game("chrono1", "Chrono Trigger").copy(tree = "first", files = listOf("first.smc"))
    val version2 = game("chrono2", "Chrono Trigger").copy(tree = "second", files = listOf("second.smc"))
    val otherPlatform = game("chrono3", "Chrono Trigger").copy(platform = "nds")
    val group = listOf(version1, version2, otherPlatform)
    check(groupedGames(group, true) == listOf(version1, otherPlatform))
    check(groupedGames(group, false) == group)
    val versions = gameVersions(version1, group.groupBy(::gameGroupKey), true)
    check(versions.map { it.game.tree } == listOf("first", "second"))
    check(gameVersions(version2, group.groupBy(::gameGroupKey), true).first().game == version2)
    check(gameVersions(version1, group.groupBy(::gameGroupKey), false).size == 1)
    val grouped = homeShelfContents(options, group, emptyList(), emptyMap()).row("games").games()
    check(grouped == listOf(version1, otherPlatform))
    check(homeShelfContents(options, group, emptyList(), emptyMap(), groupVersions = false).row("games").games() == group)
    pass("Same-title versions group only by platform, retain file source and can be shown separately")

    val anchor = HomeFeedAnchor("custom-mixed", 3, homeAppKey(zona.packageName))
    check(anchor.shelf == mixed.id && anchor.index == 3 && anchor.key == homeAppKey(zona.packageName))
    check(HomeFeedAnchor(null, 0, null).shelf == null)
    pass("Focus anchors accept stable custom section ids and an initially unknown shelf")
    println("TOTAL|$count")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
classpath = str(compiler[1])
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {str((SOURCES / name).relative_to(ROOT)): hashlib.sha256((SOURCES / name).read_bytes()).hexdigest()
                     for name in ["HomeFeed.kt", "HomeSections.kt", "MainActivity.kt", "AppArtwork.kt", "MovieLibraryStore.kt"]},
    "extractedSha256": {name: hashlib.sha256(value.encode()).hexdigest() for name, value in sections.items()},
    "notCovered": ["Compose layout, focus restoration and directional/shoulder input", "Android preference persistence and app discovery"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-home-feed-") as directory:
    folder = Path(directory)
    contents = {"Production.kt": "package tv.orbit.shield\n" + resource_stub + production, "Checks.kt": CHECKS}
    for name, content in contents.items():
        (folder / name).write_text(content)
    output_classes = folder / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath,
        "-d", str(output_classes), *[str(folder / name) for name in contents]], capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{output_classes}:{classpath}", "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 17

output = ROOT / ".reports/sections21/feed-checks.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
