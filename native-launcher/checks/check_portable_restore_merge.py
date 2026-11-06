"""Verify conflict-aware portable restore preparation without Android or user data."""
import subprocess
import tempfile
from pathlib import Path
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
main = (source / 'MainActivity.kt').read_text()
game = main[main.index('data class Game('):main.index('private data class PortablePreviewDecisionSession')]
home = (source / 'HomeSections.kt').read_text()
home = home[home.index('internal enum class HomeSectionKind'):home.index('internal fun homeGameKey')]
models = '''package tv.orbit.shield
''' + game + home + '''
internal enum class LibrarySource { FILES, PORTABLE_SNAPSHOT }
internal data class LibraryRootRecord(val platform:String, val uri:String, val source:LibrarySource)
internal data class ManagedCover(val manual:String = "")
internal data class PortableLiveLibrary(val games:List<Game>,val home:HomeSectionsOptions,val settings:Map<String,Any>,val covers:Map<String,ManagedCover>,val coverChoices:Map<String,String>)
internal data class PortableSnapshot(val home:HomeSectionsOptions,val settings:Map<String,Any>)
internal data class PortableRestoreRoot(val platform:String,val treeUri:String)
internal data class PortableRestorePlan(val snapshot:PortableSnapshot,val games:List<Game>,val roots:List<PortableRestoreRoot>,val covers:Map<String,ManagedCover>,val coverChoices:Map<String,String>)
'''
checks = r'''package tv.orbit.shield
fun main() {
    var n=0
    fun ok(x:Boolean,s:String) { check(x){s}; n++ }
    fun game(id:String, tree:String) = Game(id,id,"PS1","emu","",listOf("$tree/rom"),tree,"","","","","","")
    val home=HomeSectionsOptions(listOf(HomeSection("apps",HomeSectionKind.APPS)),listOf("app:test"))
    val live=PortableLiveLibrary(listOf(game("a","local")),home,mapOf("language" to "ru"),mapOf("a" to ManagedCover("one")),mapOf("a" to "manual"))
    val plan=PortableRestorePlan(PortableSnapshot(home,mapOf("language" to "ru","clock.visible" to true)),listOf(game("b","usb")),listOf(PortableRestoreRoot("PS1","usb")),mapOf("b" to ManagedCover("two")),mapOf("b" to "auto"))
    val merged=PortableRestoreMerge.prepare(live,emptyList(),plan)
    ok(merged.ready && merged.games.map{it.id}==listOf("a","b"),"retain and append games")
    ok(merged.roots.single().source==LibrarySource.PORTABLE_SNAPSHOT && merged.roots.single().uri=="usb","portable root")
    ok(merged.settings["clock.visible"]==true && merged.settings["language"]=="ru","settings merge")
    ok(merged.covers.size==2 && merged.choices.size==2 && merged.home==home,"retain covers, choices and home")
    val repeat=PortableRestoreMerge.prepare(live.copy(games=listOf(game("b","usb"))),listOf(LibraryRootRecord("PS1","usb",LibrarySource.PORTABLE_SNAPSHOT)),plan)
    ok(repeat.conflicts.isEmpty() && repeat.games.size==1 && repeat.roots.size==1,"idempotent equivalent import")
    val bad=plan.copy(snapshot=PortableSnapshot(home.copy(favorites=listOf("game:b")),mapOf("language" to "es")), games=listOf(game("a","usb")), roots=listOf(PortableRestoreRoot("PS1","local")), covers=mapOf("a" to ManagedCover("other")), coverChoices=mapOf("a" to "original"))
    val clash=PortableRestoreMerge.prepare(live,listOf(LibraryRootRecord("PS1","local",LibrarySource.FILES)),bad)
    ok(clash.conflicts.map{it.kind}.toSet()==PortableRestoreConflict.Kind.entries.toSet(),"all conflicting categories detected")
    ok(!clash.ready && clash.games.single()==live.games.single() && clash.covers==live.covers && clash.choices==live.coverChoices && clash.settings==live.settings && clash.home==null,"no conflicting value silently replaces live state")
    ok(runCatching { PortableRestoreMerge.prepare(live.copy(games=live.games+live.games),emptyList(),plan) }.isFailure,"duplicate existing game refused")
    ok(runCatching { PortableRestoreMerge.prepare(live,emptyList(),plan.copy(games=plan.games+plan.games)) }.isFailure,"duplicate restored game refused")
    ok(runCatching { PortableRestoreMerge.prepare(live,listOf(LibraryRootRecord("PS1","x",LibrarySource.FILES),LibraryRootRecord("PS1","x",LibrarySource.FILES)),plan) }.isFailure,"duplicate root refused")
    val sameRootBad=bad.copy(roots=listOf(PortableRestoreRoot("PS1","usb")))
    val draft=PortableRestoreMerge.prepare(live,emptyList(),sameRootBad)
    val gameKey=PortableRestoreConflict(PortableRestoreConflict.Kind.GAME,"a")
    val homeKey=PortableRestoreConflict(PortableRestoreConflict.Kind.HOME,"home.sections.v1")
    val languageKey=PortableRestoreConflict(PortableRestoreConflict.Kind.SETTING,"language")
    val allBackup=mapOf(gameKey to PortableRestoreChoice.USE_BACKUP, homeKey to PortableRestoreChoice.USE_BACKUP, languageKey to PortableRestoreChoice.USE_BACKUP)
    val restored=resolvePortableRestore(live,emptyList(),sameRootBad,allBackup)
    ok(restored.ready && restored.games.single().tree=="usb" && restored.covers.getValue("a").manual=="other" && restored.choices["a"]=="original", "backup game and cover resolve together")
    ok(restored.home==sameRootBad.snapshot.home && restored.settings["language"]=="es", "explicit backup preferences")
    val allCurrent=allBackup.mapValues { PortableRestoreChoice.KEEP_CURRENT }
    val kept=resolvePortableRestore(live,emptyList(),sameRootBad,allCurrent)
    ok(kept.ready && kept.games==live.games && kept.covers==live.covers && kept.choices==live.coverChoices && kept.home==live.home && kept.settings==live.settings,"current choices retained")
    val partial=resolvePortableRestore(live,emptyList(),sameRootBad,mapOf(homeKey to PortableRestoreChoice.KEEP_CURRENT))
    ok(!partial.ready && homeKey !in partial.conflicts && gameKey in partial.conflicts,"partial choices remain blocked")
    ok(runCatching { resolvePortableRestore(live,emptyList(),sameRootBad,mapOf(PortableRestoreConflict(PortableRestoreConflict.Kind.SETTING,"stale") to PortableRestoreChoice.USE_BACKUP)) }.isFailure,"stale decision rejected")
    ok(runCatching { resolvePortableRestore(live,emptyList(),sameRootBad,mapOf(PortableRestoreConflict(PortableRestoreConflict.Kind.COVER,"a") to PortableRestoreChoice.USE_BACKUP)) }.isFailure,"conflicting game cover cannot diverge")
    ok(runCatching { resolvePortableRestore(live,listOf(LibraryRootRecord("PS1","local",LibrarySource.FILES)),bad,mapOf(PortableRestoreConflict(PortableRestoreConflict.Kind.ROOT,"PS1") to PortableRestoreChoice.USE_BACKUP)) }.isFailure,"root mode cannot silently replace existing root")
    val noCover=sameRootBad.copy(covers=emptyMap(),coverChoices=emptyMap())
    val clean=resolvePortableRestore(live,emptyList(),noCover,allBackup)
    ok(clean.ready && "a" !in clean.covers && "a" !in clean.choices,"backup without cover removes old game cover only after explicit game choice")
    ok(draft.conflicts.isNotEmpty() && live.games.single().tree=="local", "planning remains side effect free")
    println("PASS: $n portable merge checks")
}'''
with tempfile.TemporaryDirectory() as temp:
    folder=Path(temp)
    (folder/'Models.kt').write_text(models)
    (folder/'Checks.kt').write_text(checks)
    runtime=str(COMPILER[1])
    subprocess.run([str(JAVA),'-cp',':'.join(map(str,COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',runtime,'-d',str(folder/'classes'),str(source/'PortableRestoreMerge.kt'),str(folder/'Models.kt'),str(folder/'Checks.kt')],check=True)
    subprocess.run([str(JAVA),'-cp',str(folder/'classes')+':'+runtime,'tv.orbit.shield.ChecksKt'],check=True)
