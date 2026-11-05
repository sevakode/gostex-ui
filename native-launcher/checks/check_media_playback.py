"""Exercise production media-session observation against a deterministic Android boundary.

Tests active-only/paused selection, session switching, stale callbacks, permission changes,
callback disposal and transport capability guards. This is a host boundary test; real
notification-access UI, third-party playback and lifecycle behavior need device verification.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / "native-launcher/app/src/main/java/tv/orbit/shield/MediaPlayback.kt"
cache = root / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob("*/*.jar"))
compiler = [jar("org.jetbrains.kotlin", a, v) for a, v in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10")]]
compiler += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
java = root / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
stubs = {
"Context.kt": r'''package android.content
open class Context {
 val services=mutableMapOf<Class<*>,Any>();val packageManager=android.content.pm.PackageManager()
 val started=mutableListOf<Intent>();var startFails=false
 @Suppress("UNCHECKED_CAST") fun <T> getSystemService(type:Class<T>):T?=services[type] as? T
 fun startActivity(intent:Intent){if(startFails)throw IllegalStateException();started+=intent}
}
class ComponentName(context:Context,cls:Class<*>) {fun flattenToString()="test/listener"}
class Intent(val action:String="") {fun putExtra(k:String,v:String)=this;fun addFlags(flags:Int)=this
 companion object {const val FLAG_ACTIVITY_NEW_TASK=0x10000000}}
''',
"Packages.kt": r'''package android.content.pm
class PackageManager {
 var lookups=0;var leanback=true;var launcher=true
 fun getApplicationInfo(pkg:String,flags:Int):ApplicationInfo{lookups++;return ApplicationInfo(pkg)}
 fun getLeanbackLaunchIntentForPackage(pkg:String)=if(leanback)android.content.Intent(pkg) else null
 fun getLaunchIntentForPackage(pkg:String)=if(launcher)android.content.Intent(pkg) else null
}
class ApplicationInfo(val pkg:String) {fun loadLabel(pm:PackageManager):CharSequence="App $pkg"}
''',
"Notification.kt": r'''package android.app
class NotificationManager {var granted=false;fun isNotificationListenerAccessGranted(c:android.content.ComponentName)=granted}
class PendingIntent {var sends=0;var fail=false;fun send(){if(fail)throw IllegalStateException();sends++}}
''',
"Handler.kt": r'''package android.os
class Looper {companion object {fun getMainLooper()=Looper()}}
class Handler(looper:Looper) {fun removeCallbacksAndMessages(token:Any?){}}
''',
"Key.kt": r'''package android.view
class KeyEvent(val action:Int,val keyCode:Int){companion object {const val ACTION_DOWN=0;const val ACTION_UP=1;const val KEYCODE_MEDIA_PLAY_PAUSE=85}}
''',
"Settings.kt": r'''package android.provider
object Settings {const val ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS="detail";const val ACTION_NOTIFICATION_LISTENER_SETTINGS="list";const val EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME="component"}
''',
"Service.kt": r'''package android.service.notification
open class NotificationListenerService:android.content.Context() {
 open fun onListenerConnected(){};open fun onListenerDisconnected(){};open fun onDestroy(){}
}
''',
"Metadata.kt": r'''package android.media
class MediaMetadata(val text:Map<String,String> = emptyMap()) {
 fun getText(k:String):CharSequence?=text[k]
 companion object {const val METADATA_KEY_DISPLAY_TITLE="displayTitle";const val METADATA_KEY_TITLE="title";const val METADATA_KEY_DISPLAY_SUBTITLE="subtitle";const val METADATA_KEY_ARTIST="artist";const val METADATA_KEY_ALBUM_ARTIST="albumArtist";const val METADATA_KEY_AUTHOR="author"}
}
''',
"Media.kt": r'''package android.media.session
import android.media.MediaMetadata
class PlaybackState(val state:Int,val actions:Long=0) {
 companion object {
 const val STATE_NONE=0;const val STATE_STOPPED=1;const val STATE_PAUSED=2;const val STATE_PLAYING=3
 const val STATE_FAST_FORWARDING=4;const val STATE_REWINDING=5;const val STATE_BUFFERING=6;const val STATE_ERROR=7
 const val STATE_CONNECTING=8;const val STATE_SKIPPING_TO_PREVIOUS=9;const val STATE_SKIPPING_TO_NEXT=10;const val STATE_SKIPPING_TO_QUEUE_ITEM=11
 const val ACTION_PAUSE=2L;const val ACTION_PLAY=4L;const val ACTION_SKIP_TO_PREVIOUS=16L;const val ACTION_SKIP_TO_NEXT=32L;const val ACTION_PLAY_PAUSE=512L
 }
}
class MediaSession {data class Token(val id:String)}
class MediaController(val packageName:String,var playbackState:PlaybackState?,var metadata:MediaMetadata?=null) {
 val sessionToken=MediaSession.Token(packageName);val transportControls=TransportControls();var sessionActivity:android.app.PendingIntent?=null
 val callbacks=mutableListOf<Callback>();var registrations=0;var unregistrations=0;var fail=false
 val mediaKeys=mutableListOf<android.view.KeyEvent>();fun dispatchMediaButtonEvent(e:android.view.KeyEvent):Boolean{mediaKeys+=e;return true}
 fun registerCallback(cb:Callback,h:android.os.Handler){if(fail)throw IllegalStateException();registrations++;callbacks+=cb}
 fun unregisterCallback(cb:Callback){unregistrations++;callbacks-=cb}
 fun playback(s:PlaybackState?){playbackState=s;callbacks.toList().forEach{it.onPlaybackStateChanged(s)}}
 fun metadata(m:MediaMetadata?){metadata=m;callbacks.toList().forEach{it.onMetadataChanged(m)}}
 fun destroy(){callbacks.toList().forEach{it.onSessionDestroyed()}}
 open class Callback {open fun onPlaybackStateChanged(state:PlaybackState?){};open fun onMetadataChanged(value:MediaMetadata?){};open fun onSessionDestroyed(){}}
 class TransportControls {val calls=mutableListOf<String>();var fail=false
  fun call(s:String){if(fail)throw IllegalStateException();calls+=s}
  fun pause()=call("pause");fun play()=call("play");fun skipToPrevious()=call("previous");fun skipToNext()=call("next")}
}
class MediaSessionManager {
 val listeners=mutableListOf<OnActiveSessionsChangedListener>();var active=emptyList<MediaController>();var denied=false
 var queries=0;var registrations=0;var unregistrations=0
 fun interface OnActiveSessionsChangedListener {fun onActiveSessionsChanged(controllers:List<MediaController>?)}
 fun addOnActiveSessionsChangedListener(l:OnActiveSessionsChangedListener,c:android.content.ComponentName,h:android.os.Handler){if(denied)throw SecurityException();registrations++;listeners+=l}
 fun removeOnActiveSessionsChangedListener(l:OnActiveSessionsChangedListener){unregistrations++;listeners-=l}
 fun getActiveSessions(c:android.content.ComponentName):List<MediaController>{if(denied)throw SecurityException();queries++;return active}
 fun emit(value:List<MediaController>){active=value;listeners.toList().forEach{it.onActiveSessionsChanged(value)}}
}
''',
"Compose.kt": r'''package androidx.compose.runtime
import kotlin.reflect.KProperty
annotation class Composable
class MutableState<T>(var value:T)
fun <T> mutableStateOf(v:T)=MutableState(v)
operator fun <T> MutableState<T>.getValue(thisRef:Any?,p:KProperty<*>)=value
operator fun <T> MutableState<T>.setValue(thisRef:Any?,p:KProperty<*>,v:T){value=v}
fun <T> remember(vararg keys:Any?,calculation:()->T)=calculation()
class DisposableEffectResult
class DisposableEffectScope {fun onDispose(block:()->Unit)=DisposableEffectResult()}
fun DisposableEffect(vararg keys:Any?,effect:DisposableEffectScope.()->DisposableEffectResult){}
''',
"Lifecycle.kt": r'''package androidx.lifecycle
class Lifecycle {
 enum class Event {ON_START,ON_STOP}
 enum class State {INITIALIZED,STARTED;fun isAtLeast(other:State)=ordinal>=other.ordinal}
 val currentState=State.INITIALIZED
 fun addObserver(o:LifecycleEventObserver){};fun removeObserver(o:LifecycleEventObserver){}
}
fun interface LifecycleEventObserver {fun changed(owner:Any,event:Lifecycle.Event)}
''',
"Main.kt": r'''package tv.orbit.shield
class MainActivity {val applicationContext=android.content.Context();val lifecycle=androidx.lifecycle.Lifecycle()}
''',
}
checks = r'''package tv.orbit.shield
import android.app.*
import android.content.*
import android.media.MediaMetadata
import android.media.session.*
fun main() {
 var count=0
 fun pass(name:String,ok:Boolean){check(ok){name};println("PASS|$name");count++}
 val ctx=Context();val grants=NotificationManager();val manager=MediaSessionManager()
 ctx.services[NotificationManager::class.java]=grants;ctx.services[MediaSessionManager::class.java]=manager
 val output=mutableListOf<MediaPlaybackState>();val observer=MediaPlaybackObserver(ctx,true){output+=it}
 observer.start()
 pass("No permission means no session access",!output.last().accessGranted && manager.queries==0 && manager.listeners.isEmpty())
 grants.granted=true;val service=MediaNotificationListenerService();service.onListenerConnected()
 pass("Grant refreshes immediately without recreating Activity",output.last().accessGranted && manager.listeners.size==1)
 observer.start()
 pass("Repeated start does not duplicate listeners",manager.registrations==1)
 val paused=MediaController("paused",PlaybackState(2,4))
 val playing=MediaController("playing",PlaybackState(3,2L or 16L or 32L),MediaMetadata(mapOf("title" to "Track","artist" to "Artist")))
 manager.emit(listOf(paused,playing))
 pass("Playing app wins over higher-priority paused app",output.last().session?.packageName=="playing")
 pass("Metadata and application identity are visible",output.last().session?.let{it.title=="Track" && it.artist=="Artist" && it.appName=="App playing"}==true)
 val queries=manager.queries;val labels=ctx.packageManager.lookups
 playing.metadata(MediaMetadata(mapOf("title" to "Next","displayTitle" to "Display","subtitle" to " ","artist" to "Singer")))
 pass("Metadata changes are callback-driven and label lookup is cached",output.last().session?.title=="Display" && output.last().session?.artist=="Singer" && manager.queries==queries && ctx.packageManager.lookups==labels)
 val live=output.last().session!!
 pass("Playing session supports pause and declared skip actions",live.isPlaying && live.canPlayPause && live.canSkipPrevious && live.canSkipNext)
 pass("Transport commands target the selected controller",live.togglePlayback() && live.skipPrevious() && live.skipNext() && playing.transportControls.calls==listOf("pause","previous","next"))
 playing.playback(PlaybackState(2,4))
 pass("Paused-only sessions disappear by default",output.last().session==null)
 paused.playback(PlaybackState(1))
 pass("Stopped and paused sessions leave no stale player",output.last().session==null)
 playing.playback(PlaybackState(7))
 pass("Error-only sessions leave no mini player",output.last().session==null)
 playing.playback(PlaybackState(6,512))
 pass("Buffering session remains visible with pause capability",output.last().session?.let{it.isPlaying && it.canPlayPause}==true)
 pass("Toggle-only sessions receive a balanced media button press",output.last().session!!.togglePlayback() && playing.mediaKeys.map{it.action}==listOf(0,1) && playing.mediaKeys.all{it.keyCode==85} && playing.transportControls.calls.size==3)
 val video=MediaController("another.video.player",PlaybackState(3,2),MediaMetadata(mapOf("title" to "Film")))
 manager.emit(listOf(playing,video))
 pass("Equal active sessions follow system priority",output.last().session?.packageName=="playing")
 playing.playback(PlaybackState(2,4))
 pass("Another app replaces a paused player without launcher-specific package rules",output.last().session?.packageName=="another.video.player" && output.last().session?.title=="Film")
 video.playback(PlaybackState(1))
 pass("Stopping the replacement does not revive a paused player",output.last().session==null)
 manager.emit(listOf(paused,playing));playing.playback(PlaybackState(3,2))
 val registrationCount=playing.registrations
 playing.playbackState=PlaybackState(1)
 manager.emit(listOf(paused,playing))
 pass("Fresh platform list resnapshots an existing session after a missed stop callback",output.last().session==null && playing.registrations==registrationCount)
 playing.playbackState=PlaybackState(3,2);playing.metadata=MediaMetadata(mapOf("title" to "Fresh snapshot"))
 manager.emit(listOf(paused,playing))
 pass("Resnapshot restores current state and metadata without duplicate registration",output.last().session?.title=="Fresh snapshot" && playing.registrations==registrationCount)
 playing.playback(null)
 pass("Null playback state clears the player",output.last().session==null)
 playing.playback(PlaybackState(3,2))
 val removedCallback=playing.callbacks.single()
 manager.emit(emptyList());val removedCount=output.size
 pass("Force-stopped or removed sessions clear selection and unregister controllers",output.last().session==null && paused.callbacks.isEmpty() && playing.callbacks.isEmpty())
 removedCallback.onPlaybackStateChanged(PlaybackState(3,2))
 pass("Removed session callbacks cannot revive a stopped app",output.size==removedCount)
 manager.emit(listOf(paused,playing))
 playing.playback(PlaybackState(3,0))
 val unsupported=output.last().session!!
 pass("Unsupported controls never dispatch commands",!unsupported.togglePlayback() && !unsupported.skipPrevious() && !unsupported.skipNext() && playing.transportControls.calls.size==3)
 playing.playback(PlaybackState(3,2));playing.transportControls.fail=true
 pass("Transport failure is contained",!output.last().session!!.togglePlayback())
 playing.transportControls.fail=false
 val pending=PendingIntent();playing.sessionActivity=pending
 pass("Open player uses its actual session intent",output.last().session!!.openApp(ctx) && pending.sends==1 && ctx.started.isEmpty())
 pending.fail=true
 pass("Expired session intent falls back to TV app",output.last().session!!.openApp(ctx) && ctx.started.last().action=="playing")
 val staleCallback=playing.callbacks.single();val staleSessions=manager.listeners.single()
 observer.stop();val stoppedCount=output.size
 pass("Stop unregisters session and controller callbacks",manager.listeners.isEmpty() && paused.callbacks.isEmpty() && playing.callbacks.isEmpty())
 staleCallback.onMetadataChanged(MediaMetadata(mapOf("title" to "Late")));staleSessions.onActiveSessionsChanged(listOf(playing))
 pass("Queued callbacks after stop cannot repopulate UI",output.size==stoppedCount)
 service.onListenerConnected()
 pass("Stopped observer has no access-change subscription",output.size==stoppedCount)
 observer.start()
 pass("Resume registers once and reads latest playback",manager.listeners.size==1 && playing.callbacks.size==1 && output.last().session?.packageName=="playing")
 val resumedCount=output.size
 staleSessions.onActiveSessionsChanged(emptyList());staleCallback.onPlaybackStateChanged(PlaybackState(1))
 pass("Callbacks queued before stop cannot replace the new visible lifecycle",output.size==resumedCount && output.last().session?.packageName=="playing")
 observer.stop();manager.active=emptyList();observer.start()
 pass("App stopped while launcher was hidden is absent on resume",output.last().session==null && playing.callbacks.isEmpty())
 manager.emit(listOf(paused,playing))
 grants.granted=false;service.onListenerDisconnected()
 pass("Permission revocation clears playback and all callbacks",!output.last().accessGranted && output.last().session==null && manager.listeners.isEmpty() && playing.callbacks.isEmpty())
 grants.granted=true;service.onListenerConnected();playing.destroy()
 pass("Destroyed session disappears without polling",output.last().session==null && playing.callbacks.isEmpty())
 manager.emit(listOf(playing,video));video.playback(PlaybackState(3,2));playing.destroy()
 pass("Destroying one player immediately selects another active app",output.last().session?.packageName=="another.video.player" && playing.callbacks.isEmpty())
 observer.stop()
 paused.playbackState=PlaybackState(2,4);playing.playbackState=PlaybackState(2,4)
 manager.active=listOf(paused,playing)
 val pausedOutput=mutableListOf<MediaPlaybackState>();val withPaused=MediaPlaybackObserver(ctx,true,true){pausedOutput+=it}
 withPaused.start()
 pass("Explicit paused option retains the platform's priority",pausedOutput.last().session?.packageName=="paused" && !pausedOutput.last().session!!.isPlaying)
 pass("Paused opt-in transport issues play",pausedOutput.last().session!!.togglePlayback() && paused.transportControls.calls==listOf("play"))
 playing.playback(PlaybackState(3,2))
 pass("Active app always wins when paused visibility is enabled",pausedOutput.last().session?.packageName=="playing")
 playing.playback(PlaybackState(1));paused.playback(PlaybackState(1))
 pass("Stopped sessions stay hidden even with paused visibility enabled",pausedOutput.last().session==null)
 withPaused.stop()
 val disabledOutput=mutableListOf<MediaPlaybackState>();val disabled=MediaPlaybackObserver(ctx,false){disabledOutput+=it}
 val queryCount=manager.queries;disabled.start()
 pass("Hidden feature reports access without observing media",disabledOutput.last().accessGranted && disabledOutput.last().session==null && manager.queries==queryCount && manager.listeners.isEmpty())
 disabled.stop();manager.denied=true;observer.start()
 pass("Permission race fails closed without crashing",!output.last().accessGranted && output.last().session==null && manager.listeners.isEmpty())
 observer.stop();manager.denied=false
 pass("Settings entry opens notification-access details",MediaAccess.openSettings(ctx) && ctx.started.last().action=="detail")
 ctx.startFails=true
 pass("Missing TV settings implementation reports failure",!MediaAccess.openSettings(ctx))
 println("COUNT|$count")
}
'''
with tempfile.TemporaryDirectory(prefix="gostex-media-") as directory:
    folder = Path(directory)
    files = []
    for name, text in stubs.items():
        path = folder / name
        path.write_text(text)
        files.append(path)
    test = folder / "Checks.kt"
    test.write_text(checks)
    runtime = str(compiler[1])
    compile_result = subprocess.run([str(java), "-cp", ":".join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"), str(source), *map(str, files), str(test)], capture_output=True, text=True)
    report = {"timestampUtc": datetime.now(timezone.utc).isoformat(), "scope": __doc__, "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "compileCode": compile_result.returncode, "compileOutput": compile_result.stderr}
    result = compile_result
    if result.returncode == 0:
        result = subprocess.run([str(java), "-cp", str(folder / "classes") + ":" + runtime, "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report.update({"runCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
out = root / ".reports/media45"
out.mkdir(parents=True, exist_ok=True)
(out / "backend-checks.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
raise SystemExit(result.returncode)
