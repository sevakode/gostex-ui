"""Fault injection for the portable transaction protocol; no real stores touched."""
import hashlib
import os
import subprocess
import tempfile
from pathlib import Path
from kotlin_toolchain import ROOT, JAVA, COMPILER

jar=Path(os.environ.get('PORTABLE_TEST_JSON_JAR', ROOT/'.tools/native/test-json/json-20240303.jar'))
if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest()!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Missing verified org.json test jar')
checks=r'''package tv.orbit.shield
private class Store : PortableTransactionStorage {
    val values=mutableMapOf<String,String?>("games" to "old-games","prefs" to "old-prefs","covers" to null)
    var journal:String?=null
    var step=0; var failAt=-1; var failAfter=false
    private fun op(action:()->Unit) { step++; if (step==failAt && !failAfter) error("crash"); action(); if (step==failAt && failAfter) error("crash") }
    override fun read(slot:String)=values.getValue(slot)
    override fun write(slot:String,value:String?)=op { values[slot]=value }
    override fun readJournal()=journal
    override fun writeJournal(value:String)=op { journal=value }
    override fun clearJournal()=op { journal=null }
}
fun main() {
    var n=0
    fun ok(value:Boolean, message:String) { check(value){message}; n++ }
    val changes=listOf(PortableTransactionChange("games","old-games","new-games"),PortableTransactionChange("prefs","old-prefs","new-prefs"),PortableTransactionChange("covers",null,"new-covers"))
    val before=listOf("old-games","old-prefs",null); val after=listOf("new-games","new-prefs","new-covers")
    fun values(s:Store)=listOf(s.values["games"],s.values["prefs"],s.values["covers"])
    val normal=Store(); PortableRestoreTransaction.begin(normal,changes)
    ok(values(normal)==after && normal.journal==null,"commit")
    ok(!PortableRestoreTransaction.recover(normal),"no journal")
    for (step in 1..6) for (afterOperation in listOf(false,true)) {
        val s=Store(); s.failAt=step; s.failAfter=afterOperation
        runCatching { PortableRestoreTransaction.begin(s,changes) }
        s.failAt=-1; PortableRestoreTransaction.recover(s)
        ok(values(s)==(if (step>5 || (step==5 && afterOperation)) after else before),"crash $step/$afterOperation obeys commit boundary")
        ok(s.journal==null,"recovery cleared journal")
    }
    val interrupted=Store(); interrupted.failAt=4; runCatching { PortableRestoreTransaction.begin(interrupted,changes) }
    interrupted.failAt=interrupted.step+1; interrupted.failAfter=true
    runCatching { PortableRestoreTransaction.recover(interrupted) }
    interrupted.failAt=-1; PortableRestoreTransaction.recover(interrupted)
    ok(values(interrupted)==before && interrupted.journal==null,"recovery itself can be interrupted and retried")
    val changed=Store(); changed.failAt=3; runCatching { PortableRestoreTransaction.begin(changed,changes) }; changed.failAt=-1
    changed.values["prefs"]="unrelated"
    val saved=changed.values.toMap()
    ok(runCatching { PortableRestoreTransaction.recover(changed) }.isFailure && changed.values==saved && changed.journal!=null,"unknown state blocks rollback without writes")
    val committed=Store(); committed.failAt=6; runCatching { PortableRestoreTransaction.begin(committed,changes) }; committed.failAt=-1
    committed.values["prefs"]="unrelated"; val committedValues=committed.values.toMap()
    ok(runCatching { PortableRestoreTransaction.recover(committed) }.isFailure && committed.values==committedValues && committed.journal!=null,"changed committed state blocks journal cleanup")
    val corrupt=Store(); corrupt.journal="bad"
    ok(runCatching { PortableRestoreTransaction.recover(corrupt) }.isFailure && values(corrupt)==before && corrupt.journal!=null,"corrupt journal retained")
    val stale=Store(); stale.values["games"]="changed"
    ok(runCatching { PortableRestoreTransaction.begin(stale,changes) }.isFailure && stale.journal==null,"stale preview blocked before write")
    val record=PortableTransactionRecord(false,changes)
    ok(PortableRestoreTransaction.decode(PortableRestoreTransaction.encode(record))==record,"journal roundtrip")
    ok(runCatching { PortableRestoreTransaction.encode(record.copy(changes=changes+changes[0])) }.isFailure,"duplicate slot blocked")
    ok(runCatching { PortableRestoreTransaction.encode(record.copy(changes=listOf(PortableTransactionChange("../bad","a","b")))) }.isFailure,"unsafe slot blocked")
    val tampered=PortableRestoreTransaction.encode(record).replace("old-games","bad-games")
    ok(runCatching { PortableRestoreTransaction.decode(tampered) }.isFailure,"damage detected")
    println("PASS: $n portable transaction checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder=Path(tmp); (folder/'Checks.kt').write_text(checks)
    cp=str(COMPILER[1])+':'+str(jar)
    subprocess.run([str(JAVA),'-cp',':'.join(map(str,COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/PortableRestoreTransaction.kt'),str(folder/'Checks.kt')],check=True)
    subprocess.run([str(JAVA),'-cp',str(folder/'classes')+':'+cp,'tv.orbit.shield.ChecksKt'],check=True)
