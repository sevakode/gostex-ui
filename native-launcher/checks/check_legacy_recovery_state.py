"""Exercise durable clean/legacy classification and network choice without Android."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/LegacyRecoveryState.kt'
context = '''package android.content
abstract class Context {
 companion object { const val MODE_PRIVATE = 0 }
 abstract fun getSharedPreferences(name: String, mode: Int): Preferences
}
interface Preferences { val all: Map<String, *>; fun edit(): Editor }
interface Editor { fun putString(key: String, value: String): Editor; fun commit(): Boolean }
'''
checks = r'''package tv.orbit.shield
import android.content.*
data class Game(val tree: String, val files: List<String>)
object LegacyCatalogBridge { var state = false; fun hasLibraryState(context: Context) = state }
class TestContext: Context(), Preferences, Editor {
 val values = mutableMapOf<String, Any>(); var failure = false; private var key = ""; private var value = ""
 override val all: Map<String, *> get() = values.toMap()
 override fun getSharedPreferences(name: String, mode: Int) = this
 override fun edit() = this
 override fun putString(key: String, value: String): Editor { this.key = key; this.value = value; return this }
 override fun commit(): Boolean { if (failure) return false; values[key] = value; return true }
}
fun main() {
 val key = "library.legacyRecovery"
 val clean = TestContext(); LegacyCatalogBridge.state = false
 check(!LegacyRecoveryState.initialize(clean, emptyList())); check(clean.values[key] == "clean")
 LegacyCatalogBridge.state = true; check(!LegacyRecoveryState.initialize(clean, emptyList()))
 val old = TestContext(); check(LegacyRecoveryState.initialize(old, emptyList())); check(old.values[key] == "pending")
 LegacyCatalogBridge.state = false; check(LegacyRecoveryState.initialize(old, emptyList()))
 check(!LegacyRecoveryState.initialize(old, listOf(Game("old-root", listOf("old-file")))))
 val bridge = TestContext(); LegacyCatalogBridge.state = true
 check(!LegacyRecoveryState.initialize(bridge, listOf(Game("old-root", listOf("old-file"))))); check(bridge.values[key] == null)
 val invalid = TestContext(); invalid.values[key] = "future"
 check(runCatching { LegacyRecoveryState.initialize(invalid, emptyList()) }.isFailure); check(invalid.values[key] == "future")
 val failed = TestContext(); failed.failure = true
 check(runCatching { LegacyRecoveryState.initialize(failed, emptyList()) }.isFailure); check(failed.values.isEmpty())
 check(!legacyRecoveryAllowed(LegacyNetworkState.OFFLINE, false)); check(!legacyRecoveryAllowed(LegacyNetworkState.OFFLINE, true))
 check(!legacyRecoveryAllowed(LegacyNetworkState.METERED, false)); check(legacyRecoveryAllowed(LegacyNetworkState.METERED, true))
 check(legacyRecoveryAllowed(LegacyNetworkState.UNMETERED, false))
 println("PASS: durable clean/legacy classification and metered policy")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-recovery-state-') as temp:
    directory = Path(temp)
    (directory / 'Context.kt').write_text(context)
    (directory / 'Checks.kt').write_text(checks)
    compiler = ':'.join(map(str, COMPILER))
    subprocess.run([str(JAVA), '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', str(COMPILER[1]), '-d', str(directory / 'classes'), str(source),
                    str(directory / 'Context.kt'), str(directory / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(directory / 'classes') + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
