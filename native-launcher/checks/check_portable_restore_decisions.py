"""Check explicit restore bulk-choice mapping with synthetic conflicts."""
import subprocess
import tempfile
from pathlib import Path
from kotlin_toolchain import ROOT, JAVA, COMPILER

source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/PortableRestoreDecisionGroups.kt'
model='''package tv.orbit.shield
internal data class PortableRestoreConflict(val kind:Kind,val key:String) { enum class Kind { GAME,ROOT,COVER,COVER_CHOICE,HOME,SETTING } }
internal enum class PortableRestoreChoice { KEEP_CURRENT,USE_BACKUP }
'''
checks=r'''package tv.orbit.shield
fun main() {
 var n=0; fun ok(x:Boolean,s:String){check(x){s};n++}
 val game=PortableRestoreConflict(PortableRestoreConflict.Kind.GAME,"g")
 val gameCover=PortableRestoreConflict(PortableRestoreConflict.Kind.COVER,"g")
 val gameChoice=PortableRestoreConflict(PortableRestoreConflict.Kind.COVER_CHOICE,"g")
 val otherCover=PortableRestoreConflict(PortableRestoreConflict.Kind.COVER,"h")
 val home=PortableRestoreConflict(PortableRestoreConflict.Kind.HOME,"home")
 val lang=PortableRestoreConflict(PortableRestoreConflict.Kind.SETTING,"language")
 val clock=PortableRestoreConflict(PortableRestoreConflict.Kind.SETTING,"clock")
 val root=PortableRestoreConflict(PortableRestoreConflict.Kind.ROOT,"PS1")
 val conflicts=setOf(game,gameCover,gameChoice,otherCover,home,lang,clock,root)
 val groups=PortableRestoreDecisionGroups.groups(conflicts)
 ok(groups==PortableRestoreDecisionGroup.entries.toSet(),"three groups")
 ok(PortableRestoreDecisionGroups.decisions(conflicts,emptyMap()).isEmpty(),"nothing selected")
 val choices=mapOf(PortableRestoreDecisionGroup.GAMES_AND_COVERS to PortableRestoreChoice.KEEP_CURRENT,PortableRestoreDecisionGroup.HOME to PortableRestoreChoice.USE_BACKUP,PortableRestoreDecisionGroup.SETTINGS to PortableRestoreChoice.USE_BACKUP)
 val result=PortableRestoreDecisionGroups.decisions(conflicts,choices)
 ok(result[game]==PortableRestoreChoice.KEEP_CURRENT && result[otherCover]==PortableRestoreChoice.KEEP_CURRENT,"game group")
 ok(gameCover !in result && gameChoice !in result,"dependent covers resolved by game")
 ok(result[home]==PortableRestoreChoice.USE_BACKUP && result[lang]==PortableRestoreChoice.USE_BACKUP && result[clock]==PortableRestoreChoice.USE_BACKUP,"home and settings")
 ok(root !in result,"root remains blocked")
 ok(runCatching{PortableRestoreDecisionGroups.decisions(setOf(home),mapOf(PortableRestoreDecisionGroup.SETTINGS to PortableRestoreChoice.KEEP_CURRENT))}.isFailure,"stale group refused")
 ok(PortableRestoreDecisionGroups.groups(setOf(root)).isEmpty(),"root no choice")
 println("PASS: $n restore decision group checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
 folder=Path(tmp);(folder/'Models.kt').write_text(model);(folder/'Checks.kt').write_text(checks)
 cp=str(COMPILER[1]);subprocess.run([str(JAVA),'-cp',':'.join(map(str,COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(source),str(folder/'Models.kt'),str(folder/'Checks.kt')],check=True)
 subprocess.run([str(JAVA),'-cp',str(folder/'classes')+':'+cp,'tv.orbit.shield.ChecksKt'],check=True)
