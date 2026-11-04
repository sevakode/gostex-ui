"""Compile the production offline search and check multilingual and library-boundary cases."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/GameSearch.kt"
MAIN = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/MainActivity.kt"
CATALOG = ROOT / "native-launcher/app/src/main/assets/catalog.json"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"


def quoted(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


model_text = MAIN.read_text()
model = model_text[model_text.index("data class Game("):model_text.index("class MainActivity :")]
games = json.loads(CATALOG.read_text())
rows = []
for game in games:
    fields = [quoted(game[field]) for field in ("id", "title", "platform", "emulator", "component")]
    fields.append("listOf(" + ",".join(quoted(file) for file in game["files"]) + ")")
    fields += [quoted(game.get(field, "")) for field in ("tree", "cover", "scene", "genre", "year", "developer", "players")]
    rows.append("Game(" + ",".join(fields) + ")")

CHECKS = r'''
package tv.orbit.shield
import java.util.Locale

private var checked = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }
    checked++
    println("PASS|$name")
}
private fun game(id: String, title: String, platform: String = "SNES", developer: String = "") =
    Game(id, title, platform, "emulator", "component", emptyList(), "", "", "", "", "", developer, "")
private fun List<Game>.ids() = map { it.id }

fun main() {
    val games = listOf(
        game("first", "Chrono Trigger", developer = "Square"),
        game("second", "Pokémon: Edición Amarilla", "Game Boy", "Game Freak"),
        game("third", "Ёжик — Звёздный путь", "Mega Drive", "Союз"),
        game("fourth", "Spider-Man / Venom", "SNES", "Acclaim"),
        game("fifth", "Chrono Cross", "PlayStation", "Square"),
        game("empty", "", "", ""),
    )
    val index = GameSearchIndex(games, mapOf("Game Boy" to "Портативная консоль"))
    verify("Empty and whitespace queries retain every game and source order",
        listOf("", "  \n\t ", "— + : /", "🎮").all { index.filter(it) == games })
    verify("Case-insensitive partial title search does not change source order",
        index.filter("CHRO").ids() == listOf("first", "fifth"))
    verify("Every query token must match and query word order is irrelevant",
        index.filter("trigger chrono").ids() == listOf("first") && index.filter("chrono absent").isEmpty())
    verify("One query combines title, platform and developer fields",
        index.filter("chrono play square").ids() == listOf("fifth"))
    verify("Fields cannot accidentally concatenate into a new word",
        GameSearchIndex(listOf(game("one", "Alpha", "Beta"))).filter("alphabeta").isEmpty())
    verify("Spanish accents and decomposed accents search the same title",
        listOf("POKEMON EDICION", "poke\u0301mon", "edición amarilla").all { index.filter(it).ids() == listOf("second") })
    verify("Russian case and Ё/Е normalize consistently",
        index.filter("ежик звездный").ids() == listOf("third") && index.filter("ЁЖИК союз").ids() == listOf("third"))
    verify("Hyphens, slashes, colon and repeated whitespace act as word boundaries",
        listOf("spider man", "SPIDER—MAN", "venom / spider", "spider\t \n man").all { index.filter(it).ids() == listOf("fourth") })
    verify("Repeated query tokens do not require duplicate metadata words",
        index.filter("chrono chrono").ids() == listOf("first", "fifth"))
    verify("Localized platform labels and stable platform identifiers are both searchable",
        index.filter("портативная").ids() == listOf("second") && index.filter("game boy").ids() == listOf("second"))
    verify("Empty metadata cannot turn a nonempty query into a match",
        index.filter("anything").isEmpty() && "empty" !in index.filter("snes").ids())
    verify("Search never guesses spelling, transliteration, franchise or abbreviation aliases",
        listOf("chromo", "pokemom", "покемон", "smw").all { index.filter(it).isEmpty() })

    val unrelated = game("paths", "Example", "Example platform", "Example studio").copy(
        files = listOf("/private/DONOTINDEX/Archive.rom", "content://provider/tree/secret%2Farchive/document/opaque"),
        tree = "hidden-tree", cover = "cover-filename", scene = "scene-filename", description = "Unrelated franchise")
    verify("Paths, document IDs, image filenames and descriptions are not search metadata",
        listOf("donotindex", "archive", "provider", "secret", "opaque", "hidden", "cover", "scene", "franchise")
            .all { GameSearchIndex(listOf(unrelated)).filter(it).isEmpty() })

    val beforeLocale = Locale.getDefault()
    try {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        verify("Device locale cannot alter English I or Spanish matching",
            GameSearchIndex(listOf(game("i", "Indiana Jones"))).filter("INDIANA").ids() == listOf("i") &&
                index.filter("EDICION").ids() == listOf("second"))
    } finally { Locale.setDefault(beforeLocale) }

    val unicode = GameSearchIndex(listOf(game("unicode", "１２３ Café 𐐀")))
    verify("Compatibility characters and supplementary Unicode letters survive normalization",
        unicode.filter("123 cafe 𐐨").ids() == listOf("unicode"))

    val changing = games.toMutableList()
    val labels = mutableMapOf("SNES" to "Super Nintendo")
    val frozen = GameSearchIndex(changing, labels)
    changing.clear()
    labels["SNES"] = "Changed"
    verify("Index is a stable library and label snapshot until explicitly rebuilt",
        frozen.filter("") == games && frozen.filter("super").ids() == listOf("first", "fourth") &&
            frozen.filter("changed").isEmpty())
    verify("A rebuilt index uses newly added and removed games",
        GameSearchIndex(listOf(game("new", "New entry"))).filter("").ids() == listOf("new"))

    val duplicateTitles = listOf(game("one", "Shared"), game("two", "Shared", "NES"))
    verify("Identical titles on different platforms preserve separate game identities",
        GameSearchIndex(duplicateTitles).filter("shared").ids() == listOf("one", "two"))
    verify("Empty library is safe for both empty and nonempty input",
        GameSearchIndex(emptyList()).filter("").isEmpty() && GameSearchIndex(emptyList()).filter("x").isEmpty())

    val catalog = listOf(CATALOG_ROWS)
    val catalogIndex = GameSearchIndex(catalog)
    for (entry in catalog) {
        verify("Bundled title is searchable without losing its ID: ${entry.id}",
            catalogIndex.filter(entry.title).any { it.id == entry.id })
    }
    verify("Bundled platform searches retain exact catalog ordering",
        catalogIndex.filter("SNES").ids() == catalog.filter { it.platform == "SNES" }.ids())
    println("COUNT|$checked")
}
'''.replace("CATALOG_ROWS", ",\n".join(rows))


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
      jar("org.jetbrains", "annotations", "13.0")]
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": "Unmodified production GameSearch.kt compiled with the production Game model and real bundled catalog; deterministic JVM checks.",
    "source": str(SOURCE),
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "catalogSha256": hashlib.sha256(CATALOG.read_bytes()).hexdigest(),
    "catalogGames": len(games),
    "notCovered": ["Android TV focus and keyboard", "Compose remembering and rebuilding the index", "Device rendering and persistence"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-library-search-") as folder:
    temporary = Path(folder)
    model_file = temporary / "Game.kt"
    checks_file = temporary / "LibrarySearchChecks.kt"
    model_file.write_text("package tv.orbit.shield\n" + model)
    checks_file.write_text(CHECKS)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(model_file), str(checks_file)],
        capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.LibrarySearchChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 20 + len(games)

output = ROOT / ".reports/library-search-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
