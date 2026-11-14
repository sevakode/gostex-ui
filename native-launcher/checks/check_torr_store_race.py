"""Run the production facade with a file-backed fake under concurrent add/remove operations."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import ROOT, JAVA, COMPILER, CACHE
compiler = COMPILER
json_candidates = list((CACHE / 'org.json/json').glob('*/*/*.jar'))
json_candidates += list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'))
jsonjar = next(p for p in json_candidates if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
from check_torr_completed_store import context, uri, source, journal
checks = '''package tv.orbit.shield
import android.content.Context
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
fun main(args: Array<String>) {
 val ctx=Context(args[0])
 fun job(hash:String, state:String="paused")=TorrDownloadJob(UUID.randomUUID().toString(),hash,1,1024,
   "game.bin","content://tree","content://document/"+UUID.randomUUID(),"game.bin",state=state,parentId=UUID.randomUUID().toString(),grouped=true)
 val a="a".repeat(40);val b="b".repeat(40)
 val finished=job(a,"done");val pending=job(b)
 TorrDownloadStore.add(ctx,finished);TorrDownloadStore.add(ctx,pending)
 val longTag=job(a).copy(etag=34.toChar()+"a".repeat(310)+34.toChar())
 TorrDownloadStore.add(ctx,longTag);check(TorrDownloadStore.jobs.value.single { it.id==longTag.id }.etag?.length==312)
 TorrDownloadStore.removeRecord(ctx,longTag.id)
 check(runCatching { TorrDownloadStore.add(ctx,job(a).copy(etag="x".repeat(8193))) }.isFailure)
 check(TorrDownloadStore.beginDeletePrefetch(ctx,finished.id)==null)
 val offline=job(a,"done").copy(prefetchMovieId="movie:one")
 TorrDownloadStore.add(ctx,offline)
 check(TorrDownloadStore.beginDeletePrefetch(ctx,offline.id)!=null)
 check(TorrDownloadStore.beginDeletePrefetch(ctx,offline.id)==null)
 check(runCatching { TorrDownloadStore.removeRecord(ctx,offline.id) }.isFailure)
 TorrDownloadStore.finishDeletePrefetch(ctx,offline.id);TorrDownloadStore.endDiscard(offline.id)
 check(TorrDownloadStore.jobs.value.none { it.id==offline.id })
 check(!TorrDownloadStore.beginTorrentRemoval(ctx,b.uppercase()))
 check(TorrDownloadStore.beginTorrentRemoval(ctx,a.uppercase()))
 check(!TorrDownloadStore.beginTorrentRemoval(ctx,a))
 check(runCatching { TorrDownloadStore.add(ctx,job(a)) }.isFailure)
 TorrDownloadStore.queue(ctx,listOf(pending.id))
 check(TorrDownloadStore.jobs.value.single { it.id==pending.id }.state=="queued")
 TorrDownloadStore.update(ctx,pending.id) { it.copy(state="paused") }
 TorrDownloadStore.endTorrentRemoval(a)
 val after=job(a);TorrDownloadStore.add(ctx,after);TorrDownloadStore.removeRecord(ctx,after.id)
 check(TorrDownloadStore.claimWorker(ctx,pending.id).not())
 TorrDownloadStore.queue(ctx,listOf(pending.id))
 check(TorrDownloadStore.claimWorker(ctx,pending.id))
 TorrDownloadStore.update(ctx,pending.id) { it.copy(state="paused") }
 check(TorrDownloadStore.beginDiscard(ctx,pending.id)==null)
 check(runCatching { TorrDownloadStore.removeRecord(ctx,pending.id) }.isFailure)
 // A rapid Resume may queue the same job while the old stream is still closing.
 TorrDownloadStore.queue(ctx,listOf(pending.id))
 check(!TorrDownloadStore.claimWorker(ctx,pending.id))
 TorrDownloadStore.releaseWorker(pending.id)
 check(TorrDownloadStore.claimWorker(ctx,pending.id))
 TorrDownloadStore.update(ctx,pending.id) { it.copy(state="paused") }
 TorrDownloadStore.releaseWorker(pending.id)
 check(TorrDownloadStore.beginDiscard(ctx,pending.id)!=null)
 check(TorrDownloadStore.beginDiscard(ctx,pending.id)==null)
 check(runCatching { TorrDownloadStore.queue(ctx,listOf(pending.id)) }.isFailure)
 check(runCatching { TorrDownloadStore.removeRecord(ctx,pending.id) }.isFailure)
 TorrDownloadStore.endDiscard(pending.id)
 TorrDownloadStore.removeRecord(ctx,pending.id)
 val discarded=job(b);TorrDownloadStore.add(ctx,discarded)
 check(TorrDownloadStore.beginDiscard(ctx,discarded.id)!=null)
 TorrDownloadStore.finishDiscard(ctx,discarded.id);TorrDownloadStore.endDiscard(discarded.id)
 check(TorrDownloadStore.jobs.value.none { it.id==discarded.id })
 repeat(100) {
   val candidate=job(b);TorrDownloadStore.add(ctx,candidate);val barrier=CyclicBarrier(2)
   val reserved=AtomicBoolean(false);val queued=AtomicBoolean(false)
   val t1=thread { barrier.await();reserved.set(TorrDownloadStore.beginDiscard(ctx,candidate.id)!=null) }
   val t2=thread { barrier.await();queued.set(runCatching { TorrDownloadStore.queue(ctx,listOf(candidate.id)) }.isSuccess) }
   t1.join();t2.join();check(reserved.get() xor queued.get())
   if (reserved.get()) TorrDownloadStore.endDiscard(candidate.id)
   else TorrDownloadStore.update(ctx,candidate.id) { it.copy(state="paused") }
   TorrDownloadStore.removeRecord(ctx,candidate.id)
 }
 repeat(100) { i ->
   val hash=(i+100).toString(16).padStart(40,'0');val candidate=job(hash);val barrier=CyclicBarrier(2)
   val removed=AtomicBoolean(false);val added=AtomicBoolean(false)
   val t1=thread { barrier.await();removed.set(TorrDownloadStore.beginTorrentRemoval(ctx,hash)) }
   val t2=thread { barrier.await();added.set(runCatching { TorrDownloadStore.add(ctx,candidate) }.isSuccess) }
   t1.join();t2.join();check(removed.get() xor added.get())
   if (removed.get()) TorrDownloadStore.endTorrentRemoval(hash) else TorrDownloadStore.removeRecord(ctx,candidate.id)
 }
 check(TorrDownloadStore.jobs.value.single().id==finished.id)
 println("PASS: active worker protection, 100 queue/discard races and 100 add/torrent-removal races")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-store-race-') as td:
    folder=Path(td)
    for name,body in [('Context.kt',context),('Uri.kt',uri),('Source.kt',source),('Journal.kt',journal),('Checks.kt',checks)]: (folder/name).write_text(body)
    cp=':'.join(map(str,[compiler[1],compiler[4],jsonjar]))
    base=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield'
    sources=[base/name for name in ('TorrDownloadStore.kt','TorrDownloadJournal.kt','TorrDownloadJournalCodec.kt','HttpDownloadAdmission.kt')]
    result=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),*map(str,sources),*[str(folder/n) for n in ('Context.kt','Uri.kt','Source.kt','Journal.kt','Checks.kt')]],capture_output=True,text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result=subprocess.run([str(JAVA),'-cp',f'{folder/"classes"}:{cp}','tv.orbit.shield.ChecksKt',str(folder/'queue.properties')],capture_output=True,text=True)
    print(result.stdout,end='')
    if result.returncode: raise SystemExit(result.stderr)
