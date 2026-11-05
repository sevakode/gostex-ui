"""Compile production snapshot reconciliation and library visibility preferences.

Storage-provider reads, empty-screen focus and SAF picker flow need device QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

SOURCES = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
main = (SOURCES / "MainActivity.kt").read_text()
model = main[main.index("data class Game("):main.index("class MainActivity")]
snapshot = (SOURCES / "LibrarySnapshot.kt").read_text().removeprefix("package tv.orbit.shield\n")
snapshot_imports = "\n".join(line for line in snapshot.splitlines() if line.startswith("import ")) + "\n"
snapshot = "\n".join(line for line in snapshot.splitlines() if not line.startswith("import ")) + "\n"
options = (SOURCES / "LibraryOptions.kt").read_text()
preferences = options[options.index("internal enum class NdsEmulatorChoice"):options.index("internal val LocalLibraryOptions")]
preferences += options[options.index("internal object LibraryPreferences"):options.index("/** One root listener")]

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String?): String?
    fun edit(): Editor
    interface Editor { fun putBoolean(key: String, value: Boolean): Editor; fun putString(key: String, value: String): Editor; fun apply() }
}
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''

CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences
private class TestPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>("home.sections.v1" to "unchanged", "libraryRoots" to "unchanged")
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    override fun edit() = object : SharedPreferences.Editor {
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { values[key] = value; return this }
        override fun putString(key: String, value: String): SharedPreferences.Editor { values[key] = value; return this }
        override fun apply() { }
    }
}
private class TestContext(val preferences: TestPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return preferences
    }
}
private fun game(id: String, tree: String, vararg files: String) = Game(id, id, "SNES", "emulator", "component", files.toList(), tree,
    "cover", "scene", "genre", "year", "developer", "players")
fun main() {
    var checked = 0
    fun verify(name: String, condition: Boolean) { check(condition) { name }; checked++; println("PASS|$name") }
    val original = game("favorite-game", "a", "original.sfc", "translation.sfc")
    val other = game("other", "b", "other.sfc")
    val previous = listOf(original, other)
    verify("An empty accessible folder clears its cached games", libraryRootSnapshot("a", emptyList(), previous).isEmpty())
    verify("An unavailable folder keeps only its own cached games", libraryRootSnapshot("a", null, previous) == listOf(original))
    val oneVariant = original.copy(files = listOf("translation.sfc"))
    verify("A successful scan replaces removed variants without changing game identity", libraryRootSnapshot("a", listOf(oneVariant), previous) == listOf(oneVariant))
    verify("Removed roots do not leak cached entries into the current library",
        listOf("a").flatMap { libraryRootSnapshot(it, emptyList(), previous) }.isEmpty())
    verify("A new root unavailable before any successful scan has no fabricated entries", libraryRootSnapshot("new", null, previous).isEmpty())
    val merged = mergeImportedVariants(listOf(original.copy(files = listOf("original.sfc")), oneVariant, oneVariant))
    verify("Known variants produce one stable card with unique existing files", merged == listOf(original))
    val crossRoot = mergeImportedVariants(listOf(oneVariant, original.copy(tree = "b", files = listOf("elsewhere.sfc"))))
    verify("Legacy distinct-root variants retain separate cards with their own grants",
        crossRoot.size == 2 && crossRoot.map { it.id }.distinct().size == 2 && crossRoot[0] == oneVariant && crossRoot[1].tree == "b" && crossRoot[1].files == listOf("elsewhere.sfc"))
    val legacy = original.copy(tree = "primary:Games/SNES")
    val canonicalRoot = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FSNES"
    val parentRoot = "content://com.android.externalstorage.documents/tree/primary%3AGames"
    val canonical = catalogImportEntry(legacy, "translation.sfc", canonicalRoot)
    val secondary = catalogImportEntry(legacy, "original.sfc", parentRoot)
    val oldRaw = "/storage/emulated/0/Games/SNES/A+B.sfc"
    val oldDocument = "content://com.android.externalstorage.documents/document/primary%3AGames%2FSNES%2FA%2BB.sfc"
    val retained = legacy.copy(tree = canonicalRoot, files = listOf(oldRaw), description = "kept description")
    val retainedIndex = previousImportIndex(listOf(retained), canonicalRoot, "SNES")
    verify("A previous card retains its ID and metadata when its file is observed without a bundled catalog",
        retainedIndex[libraryFileKey(oldDocument)] == retained && retainedIndex[libraryFileKey(oldDocument)]?.description == "kept description")
    verify("Equivalent encoded roots resolve the same persisted identity",
        previousImportIndex(listOf(retained), canonicalRoot.replace("%3A", "%3a"), "SNES")[libraryFileKey(oldRaw)] == retained)
    verify("Other sources or platforms never inherit a persisted card",
        previousImportIndex(listOf(retained), parentRoot, "SNES").isEmpty() && previousImportIndex(listOf(retained), canonicalRoot, "NES").isEmpty())
    verify("A conflicting previous identity aborts the scan instead of replacing stored cards",
        runCatching { previousImportIndex(listOf(retained, retained.copy(id = "other-id")), canonicalRoot, "SNES") }.isFailure)
    val retainedVariants = retained.copy(files = listOf(oldRaw, "other.sfc"))
    val variantIndex = previousImportIndex(listOf(retainedVariants), canonicalRoot, "SNES")
    verify("Every existing variant resolves to its own preserved card",
        variantIndex[libraryFileKey(oldRaw)] == retainedVariants && variantIndex[libraryFileKey("other.sfc")] == retainedVariants)
    val rescannedVariants = listOf(oldRaw, "other.sfc").map { path ->
        variantIndex.getValue(libraryFileKey(path)).copy(files = listOf(path), tree = canonicalRoot)
    }
    verify("Rescanning multiple variants keeps the original single card, identity and metadata",
        mergeImportedVariants(rescannedVariants) == listOf(retainedVariants))
    verify("A missing variant is not restored from the previous snapshot during a successful scan",
        mergeImportedVariants(rescannedVariants.take(1)) == listOf(retained.copy(files = listOf(oldRaw))))
    verify("Original catalog roots retain favorite/history identity", canonical.id == legacy.id && canonical.tree == canonicalRoot)
    verify("Other grants get stable identities before merging", secondary.id != canonical.id && secondary.tree == parentRoot && secondary.files == listOf("original.sfc"))
    verify("Removing or reordering roots never renames imported known variants",
        mergeImportedVariants(listOf(secondary)) == listOf(secondary) && mergeImportedVariants(listOf(secondary, canonical)).map { it.id } == listOf(secondary.id, canonical.id))
    val unavailablePrimary = libraryRootSnapshot(canonicalRoot, null, listOf(canonical))
    verify("Offline primary cannot suppress a working variant in another root",
        mergeImportedVariants(unavailablePrimary + secondary).toSet() == setOf(canonical, secondary))
    verify("Equivalent percent encoding preserves the original root identity",
        catalogImportEntry(legacy, "translation.sfc", canonicalRoot.replace("%3A", "%3a").replace("%2F", "%2f")).id == legacy.id)
    val sharedDocument = oneVariant.copy(tree = "second-grant")
    verify("Overlapping grants for one identical document do not duplicate cards",
        mergeImportedVariants(listOf(oneVariant, sharedDocument)) == listOf(oneVariant))
    verify("Entries without files cannot create phantom cards", mergeImportedVariants(listOf(original.copy(files = emptyList()))).isEmpty())
    val rawGame = game("folder-game", canonicalRoot, "/storage/emulated/0/Games/SNES/A+B.sfc")
    val singleUri = "content://com.android.externalstorage.documents/document/primary%3AGames%2FSNES%2FA%2BB.sfc"
    val singleGame = game("single-game", singleUri, singleUri)
    verify("Document and folder grants deduplicate even when old catalog IDs differ", mergeImportedVariants(listOf(rawGame, singleGame)) == listOf(rawGame))
    verify("First selected source keeps its existing identity when folder is added later", mergeImportedVariants(listOf(singleGame, rawGame)) == listOf(singleGame))
    verify("Removing one source leaves the other source launch URI intact", mergeImportedVariants(listOf(singleGame)) == listOf(singleGame))
    verify("URI encoding and literal plus preserve document identity", libraryFileKey(singleUri) == libraryFileKey(rawGame.files.single()) && libraryFileKey(singleUri.replace("%2B", "+")) == libraryFileKey(singleUri))
    verify("Filename case and distinct ROM documents remain separate", libraryFileKey(singleUri.replace("A%2BB", "a%2Bb")) != libraryFileKey(singleUri))
    verify("Same document can belong to separately selected platforms", mergeImportedVariants(listOf(rawGame, singleGame.copy(platform="PSP"))).size == 2)
    val preferences = TestPreferences(); val context = TestContext(preferences)
    verify("Existing installations keep the Library tab by default", LibraryPreferences.read(context).tabVisible)
    LibraryPreferences.setTabVisible(context, false)
    verify("Hidden Library persists across a fresh context", !LibraryPreferences.read(TestContext(preferences)).tabVisible)
    LibraryPreferences.setTabVisible(context, true)
    verify("Reenabling the tab preserves roots and custom home sections", LibraryPreferences.read(context).tabVisible && preferences.values["libraryRoots"] == "unchanged" && preferences.values["home.sections.v1"] == "unchanged")
    preferences.values["library.tabVisible"] = "invalid"
    verify("Malformed preferences fall back safely", LibraryPreferences.read(context).tabVisible)
    verify("Unrelated preference changes do not refresh tab state",
        LibraryPreferences.changesOptions(null) && LibraryPreferences.changesOptions("library.tabVisible") && !LibraryPreferences.changesOptions("libraryRoots") && !LibraryPreferences.changesOptions("played:game"))
    val beforeChoice = preferences.values.toMap()
    verify("Standalone melonDS stays the default without silently rewriting library preferences",
        LibraryPreferences.read(context).ndsEmulator == NdsEmulatorChoice.MELON_DS && preferences.values == beforeChoice)
    for (choice in NdsEmulatorChoice.entries) {
        LibraryPreferences.setNdsEmulator(context, choice)
        verify("Nintendo DS choice ${choice.key} survives a fresh reader without changing roots, Home or tab visibility",
            LibraryPreferences.read(TestContext(preferences)).ndsEmulator == choice &&
                preferences.values.filterKeys { it != "library.ndsEmulator" } == beforeChoice)
    }
    for (bad in listOf<Any?>(null, true, 12, 1L, "", "unknown", "RETROARCH", setOf("melonds"))) {
        preferences.values["library.ndsEmulator"] = bad
        val stored = preferences.values.toMap()
        verify("Malformed Nintendo DS choice $bad safely falls back without altering storage",
            LibraryPreferences.read(context).ndsEmulator == NdsEmulatorChoice.MELON_DS && preferences.values == stored)
    }
    verify("Only exact Nintendo DS preference or full clear invalidates the live settings",
        LibraryPreferences.changesOptions("library.ndsEmulator") && !LibraryPreferences.changesOptions("library.ndsEmulatorExtra"))
    println("COUNT|$checked")
}
'''

compiler = COMPILER
report = {"timestamp": datetime.now(timezone.utc).isoformat(), "scope": __doc__, "sourceSha256": hashlib.sha256((model + snapshot + preferences).encode()).hexdigest()}
with tempfile.TemporaryDirectory(prefix="gostex-library-snapshot-") as folder:
    folder = Path(folder)
    (folder / "Boundary.kt").write_text(BOUNDARY)
    (folder / "Production.kt").write_text("package tv.orbit.shield\nimport android.content.Context\n" + snapshot_imports + model + snapshot + preferences)
    (folder / "Checks.kt").write_text(CHECKS)
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(compiler[1]), "-d", str(folder / "classes"), *map(str, folder.glob("*.kt"))], capture_output=True, text=True)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    report["passed"] = False
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", str(folder / "classes") + ":" + str(compiler[1]), "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report["run"] = {"code": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["passed"] = run.returncode == 0
out = ROOT / ".reports/library24"; out.mkdir(parents=True, exist_ok=True)
(out / "snapshot-checks.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": report.get("run", {}).get("stdout", "").count("PASS|"), "errors": report.get("run", report["compile"]).get("stderr", "")}))
raise SystemExit(0 if report["passed"] else 1)
