package tv.orbit.shield

import android.content.Context
import dev.gostex.release.SignedReleaseClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface HistoricalPackageFetcher { fun fetch(destination: File) }

/** Called on demand only. The caller owns network/metering policy and user-visible state. */
internal object LegacyCatalogRecovery {
    private const val FEED = "https://github.com/sevakode/gostex-ui/releases/download/build-4/android-arm64.properties"
    private const val APK = "https://github.com/sevakode/gostex-ui/releases/download/build-4/gostex-android-arm64-0.33.4.apk"
    private const val VERSION = 100004L
    private const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    private val lock = Mutex()
    private fun fetchSigned(destination: File, checkpoint: () -> Unit) {
        val client = SignedReleaseClient(transferCheckpoint = checkpoint)
        val release = checkNotNull(client.check(FEED, "android-arm64", 0)) { "Historical release unavailable" }
        check(release.versionCode == VERSION && release.url == APK && release.size in 1..MAX_PACKAGE_BYTES) {
            "Unexpected historical release"
        }
        client.download(release, destination)
    }

    /** Tests can provide a local source, never a user-supplied URL. */
    suspend fun restore(context: Context, fetcher: HistoricalPackageFetcher? = null,
                        expectedCatalogSha256: String = LegacyCatalogArchive.HISTORICAL_CATALOG_SHA256,
                        networkCheckpoint: () -> Unit = {}) = lock.withLock {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        if (LegacyCatalogBridge.hasVerifiedCopy(context)) return@withLock
        val temporary = File.createTempFile("gostex-legacy-", ".apk", context.cacheDir)
        try {
            val catalog = runInterruptible(Dispatchers.IO) {
                coroutine.ensureActive()
                networkCheckpoint()
                if (fetcher == null) fetchSigned(temporary, networkCheckpoint) else fetcher.fetch(temporary)
                networkCheckpoint()
                coroutine.ensureActive()
                LegacyCatalogArchive.extract(temporary, expectedCatalogSha256) { coroutine.ensureActive() }
            }
            coroutine.ensureActive()
            LegacyCatalogBridge.adoptVerified(context, catalog)
        } finally {
            temporary.delete()
        }
    }
}
