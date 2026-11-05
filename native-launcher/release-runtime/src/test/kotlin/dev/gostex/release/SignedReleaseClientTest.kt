package dev.gostex.release

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.SignatureException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedReleaseClientTest {
    @Test
    fun `valid signed release downloads exact bytes and verifies existing destination again`() = server { host ->
        val artifact = "GOSTEX artifact тест".toByteArray()
        val release = host.publish(artifact)
        val client = client()
        assertEquals(release, client.check(host.feedUrl, "linux-x64", 1))
        temporaryDirectory { directory ->
            val destination = File(directory, "nested/application.bin")
            client.download(release, destination)
            assertContentEquals(artifact, destination.readBytes())
            host.server.removeContext("/artifact")
            // No HTTP needed for a verified cache. A same-size mutation is detected by hashing.
            client.download(release, destination)
            destination.writeBytes(ByteArray(artifact.size) { 0 })
            assertFailsWith<IOException> { client.download(release, destination) }
        }
    }

    @Test
    fun `signature is verified before malformed properties can be parsed`() = server { host ->
        val malformed = "bad=\\uZZZZ\n".toByteArray()
        host.respond("/feed.properties", malformed)
        host.respond("/feed.properties.sig", sign("different bytes".toByteArray()))
        assertFailsWith<SignatureException> { client().check(host.feedUrl, "linux-x64", 1) }
        host.replace("/feed.properties.sig", sign(malformed))
        assertFailsWith<IOException> { client().check(host.feedUrl, "linux-x64", 1) }
    }

    @Test
    fun `wrong platform fails and same or older versions never update`() = server { host ->
        host.publish("bytes".toByteArray())
        assertFailsWith<IOException> { client().check(host.feedUrl, "android-arm64", 1) }
        assertNull(client().check(host.feedUrl, "linux-x64", 2))
        assertNull(client().check(host.feedUrl, "linux-x64", 999))
    }

    @Test
    fun `bad hash or truncated download preserves previous file and removes partials`() = server { host ->
        val release = host.publish("real payload".toByteArray())
        temporaryDirectory { directory ->
            val destination = File(directory, "application.bin")
            val previous = "previous valid release".toByteArray()
            destination.writeBytes(previous)
            for (replacement in listOf("fake payload".toByteArray(), "short".toByteArray())) {
                host.replace("/artifact", replacement)
                assertFailsWith<IOException> { client().download(release, destination) }
                assertContentEquals(previous, destination.readBytes())
                assertEquals(listOf(destination.name), directory.listFiles()!!.map { it.name })
            }
        }
    }

    @Test
    fun `chunked payload exceeding signed size fails before replacement`() = server { host ->
        val release = host.publish("small".toByteArray())
        host.server.removeContext("/artifact")
        host.server.createContext("/artifact") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("too many bytes".toByteArray()) }
            exchange.close()
        }
        temporaryDirectory { directory ->
            val destination = File(directory, "application.bin")
            assertFailsWith<IOException> { client().download(release, destination) }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `transfer policy change aborts download and preserves previous destination`() = server { host ->
        val release = host.publish(ByteArray(1_048_576) { (it % 251).toByte() })
        temporaryDirectory { directory ->
            val destination = File(directory, "application.bin")
            val previous = "previous release".toByteArray()
            destination.writeBytes(previous)
            var checks = 0
            val guarded = SignedReleaseClient(keys.public, allowLocalHttpForTests = true,
                transferCheckpoint = { if (++checks >= 5) throw IOException("network became metered") })
            assertFailsWith<IOException> { guarded.download(release, destination) }
            assertTrue(checks >= 5)
            assertContentEquals(previous, destination.readBytes())
            assertEquals(listOf(destination.name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test
    fun `redirects support relative locations and query strings but are bounded`() = server { host ->
        val release = host.publish("artifact".toByteArray())
        host.redirect("/redirect", "/feed.properties?temporary=token")
        host.redirect("/redirect.sig", "/feed.properties.sig?temporary=token")
        assertEquals(release, client().check(host.base + "/redirect", "linux-x64", 1))
        host.redirect("/loop", "/loop")
        assertFailsWith<IOException> { client().check(host.base + "/loop", "linux-x64", 1) }
        host.redirect("/unsafe", "http://192.0.2.1/feed")
        assertFailsWith<IOException> { client().check(host.base + "/unsafe", "linux-x64", 1) }
    }

    @Test
    fun `transport rejects http outside explicit loopback test mode and URL credentials`() = server { host ->
        host.publish("bytes".toByteArray())
        assertFailsWith<IOException> { SignedReleaseClient(keys.public).check(host.feedUrl, "linux-x64", 1) }
        for (url in listOf("http://127.0.0.1@192.0.2.1/feed", "http://user:password@127.0.0.1/feed",
            host.feedUrl + "#fragment", host.feedUrl + "?query=value", "file:///tmp/feed")) {
            assertFailsWith<IOException> { client().check(url, "linux-x64", 1) }
        }
    }

    @Test
    fun `oversized feed signature and signed artifact declaration are rejected`() = server { host ->
        host.respond("/feed.properties", ByteArray(65_537) { 65 })
        assertFailsWith<IOException> { client().check(host.feedUrl, "linux-x64", 1) }
        host.publish("bytes".toByteArray())
        host.replace("/feed.properties.sig", ByteArray(4097))
        assertFailsWith<IOException> { client().check(host.feedUrl, "linux-x64", 1) }
        host.publish("bytes".toByteArray(), declaredSize = 1_073_741_825L)
        assertFailsWith<IOException> { client().check(host.feedUrl, "linux-x64", 1) }
    }

    private fun client() = SignedReleaseClient(keys.public, allowLocalHttpForTests = true)

    private class Host(val server: HttpServer) {
        val base = "http://127.0.0.1:${server.address.port}"
        val feedUrl = "$base/feed.properties"

        fun publish(artifact: ByteArray, declaredSize: Long = artifact.size.toLong()): Release {
            val release = Release("linux-x64", 2, "0.2.0", "$base/artifact", hash(artifact), declaredSize)
            val feed = """
                schema.version=1
                platform=${release.platform}
                versionCode=${release.versionCode}
                versionName=${release.versionName}
                url=${release.url}
                sha256=${release.sha256}
                size=${release.size}
            """.trimIndent().toByteArray()
            replace("/feed.properties", feed)
            replace("/feed.properties.sig", sign(feed))
            replace("/artifact", artifact)
            return release
        }

        fun respond(path: String, bytes: ByteArray) {
            server.createContext(path) { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
        }

        fun replace(path: String, bytes: ByteArray) {
            try { server.removeContext(path) } catch (_: IllegalArgumentException) { }
            respond(path, bytes)
        }

        fun redirect(path: String, location: String) {
            server.createContext(path) { exchange ->
                exchange.responseHeaders.set("Location", location)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
        }
    }

    private fun server(block: (Host) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(Host(server)) } finally { server.stop(0) }
    }

    private fun temporaryDirectory(block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("gostex-release-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    companion object {
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
