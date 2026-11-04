package dev.gostex.desktop.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.gostex.release.SignedReleaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdatesTest {
    @Test
    fun `clearing or changing feed invalidates staged release and cannot download the old channel`() = runBlocking {
        fixture { host ->
            val updates = host.updates()
            val settings = host.settings(autoDownload = false)
            for (replacement in listOf("", host.base + "/other-channel.properties")) {
                withTimeout(10_000) {
                    updates.check(settings, force = true)
                    assertEquals(host.nextCode, updates.release?.versionCode)
                    updates.download()
                }
                assertTrue(updates.ready)
                assertTrue(Files.isRegularFile(host.root.resolve("pending.properties")))
                val previousDownloads = host.artifactRequests.get()

                updates.configure(settings.copy(feedUrl = replacement))
                assertNull(updates.release)
                assertFalse(updates.ready)
                assertFalse(Files.exists(host.root.resolve("pending.properties")))
                withTimeout(5_000) { updates.download() }
                assertEquals(previousDownloads, host.artifactRequests.get())
                assertFalse(updates.ready)
                assertFalse(updates.working)
                assertFalse(Files.exists(host.root.resolve("pending.properties")))
            }
        }
    }

    @Test
    fun `hiding launcher cancels manual check and download without writing pending release`() = runBlocking {
        fixture { host ->
            val updates = host.updates()
            val settings = host.settings(autoDownload = false)
            val checkGate = host.gateFeed()
            val check = launch { updates.check(settings, force = true) }.also(host.jobs::add)
            checkGate.awaitStarted()
            assertTrue(updates.working)
            updates.setForeground(false)
            // HttpURLConnection reads may not react to interrupt until the server releases bytes.
            checkGate.release.countDown()
            withTimeout(10_000) { check.join() }
            assertTrue(check.isCancelled)
            assertFalse(updates.working)
            assertNull(updates.release)
            assertFalse(updates.ready)
            assertFalse(Files.exists(host.root.resolve("pending.properties")))

            updates.setForeground(true)
            withTimeout(10_000) { updates.check(settings, force = true) }
            assertEquals(host.nextCode, updates.release?.versionCode)
            val downloadGate = host.gateArtifact()
            val download = launch { updates.download() }.also(host.jobs::add)
            downloadGate.awaitStarted()
            assertTrue(updates.working)
            updates.setForeground(false)
            downloadGate.release.countDown()
            withTimeout(10_000) { download.join() }
            assertTrue(download.isCancelled)
            assertFalse(updates.working)
            assertFalse(updates.ready)
            assertFalse(Files.exists(host.root.resolve("pending.properties")))
        }
    }

    @Test
    fun `rejected version skips automatic download while explicit retry clears marker and stages payload`() = runBlocking {
        fixture { host ->
            val rejected = host.root.resolve("rejected-version.properties")
            Files.newBufferedWriter(rejected).use { output ->
                Properties().apply { setProperty("versionCode", host.nextCode.toString()) }.store(output, null)
            }
            val updates = host.updates()
            withTimeout(10_000) { updates.check(host.settings(autoDownload = true)) }
            assertEquals("updates_skipped", updates.status)
            assertEquals(host.nextCode, updates.release?.versionCode)
            assertEquals(0, host.artifactRequests.get())
            assertFalse(updates.ready)
            assertTrue(Files.exists(rejected))
            assertFalse(Files.exists(host.root.resolve("pending.properties")))

            withTimeout(10_000) { updates.download() }
            assertFalse(Files.exists(rejected))
            assertEquals(1, host.artifactRequests.get())
            assertTrue(updates.ready)
            assertFalse(updates.working)
            assertEquals("updates_ready", updates.status)
            val pending = Properties().apply {
                Files.newBufferedReader(host.root.resolve("pending.properties")).use { load(it) }
            }
            assertEquals(host.nextCode.toString(), pending.getProperty("versionCode"))
            assertEquals(host.hash, pending.getProperty("sha256"))
            assertContentEquals(host.payload, Files.readAllBytes(Path.of(pending.getProperty("path"))))
        }
    }

    private class Gate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        suspend fun awaitStarted() = withContext(Dispatchers.IO) {
            assertTrue(started.await(5, TimeUnit.SECONDS), "The local streaming request did not start")
        }
    }

    private class Fixture(val root: Path) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "gostex-update-test").apply { isDaemon = true } }
        val jobs = mutableListOf<Job>()
        private val gates = mutableListOf<Gate>()
        @Volatile private var feedGate: Gate? = null
        @Volatile private var artifactGate: Gate? = null
        val base = "http://127.0.0.1:${server.address.port}"
        val nextCode = DesktopBuild.code + 1
        val payload = byteArrayOf(80, 75) + ByteArray(2048) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val artifactRequests = AtomicInteger()
        private val feed = """
            schema.version=1
            platform=linux-x64
            versionCode=$nextCode
            versionName=test-$nextCode
            url=$base/artifact.zip
            sha256=$hash
            size=${payload.size}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        private val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(feed)
            sign()
        }

        init {
            server.executor = executor
            server.createContext("/feed.properties") { send(it, feed, feedGate) }
            server.createContext("/feed.properties.sig") { send(it, signature, null) }
            server.createContext("/artifact.zip") {
                artifactRequests.incrementAndGet()
                send(it, payload, artifactGate)
            }
            server.start()
        }

        fun settings(autoDownload: Boolean) = DesktopSettings(
            feedUrl = "$base/feed.properties", autoDownload = autoDownload, debugEnabled = false
        )

        fun updates() = DesktopUpdates(root,
            clientFactory = { SignedReleaseClient(keys.public, allowLocalHttpForTests = true) },
            platformProvider = { "linux-x64" })

        fun gateFeed(): Gate = Gate().also { feedGate = it; gates.add(it) }
        fun gateArtifact(): Gate = Gate().also { artifactGate = it; gates.add(it) }

        private fun send(exchange: HttpExchange, bytes: ByteArray, gate: Gate?) {
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    val split = if (gate == null) bytes.size else bytes.size / 2
                    output.write(bytes, 0, split)
                    output.flush()
                    if (gate != null) {
                        gate.started.countDown()
                        if (!gate.release.await(10, TimeUnit.SECONDS)) return
                        output.write(bytes, split, bytes.size - split)
                    }
                }
            } catch (_: java.io.IOException) {
                // Cancellation may close the client's response while the fixture is finishing.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                exchange.close()
            }
        }

        suspend fun close() = withContext(NonCancellable) {
            gates.forEach { it.release.countDown() }
            jobs.forEach { it.cancel() }
            server.stop(0)
            try { withTimeout(10_000) { jobs.joinAll() } }
            finally {
                executor.shutdownNow()
                withContext(Dispatchers.IO) { executor.awaitTermination(5, TimeUnit.SECONDS) }
                root.toFile().deleteRecursively()
            }
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(Files.createTempDirectory("gostex-desktop-updates-"))
        try { block(fixture) } finally { fixture.close() }
    }

    companion object {
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
