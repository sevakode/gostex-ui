package dev.gostex.release

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.Properties

data class Release(
    val platform: String,
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long
)

object ReleaseTrust {
    /** The resource is packaged with the app, never selected by preferences or a remote feed. */
    fun publicKey(): PublicKey {
        val bytes = ReleaseTrust::class.java.getResourceAsStream("/gostex-release-public.der")?.use {
            readBounded(it, 4096)
        } ?: throw IOException("Missing packaged release public key")
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }
}

/** Blocking adapter: Android/Desktop callers own their IO dispatcher and lifecycle. */
class SignedReleaseClient(
    private val publicKey: PublicKey = ReleaseTrust.publicKey(),
    private val allowLocalHttpForTests: Boolean = false
) {
    fun check(feedUrl: String, platform: String, currentCode: Long): Release? {
        require(currentCode >= 0) { "Invalid current version code" }
        val source = validatedUrl(feedUrl)
        if (source.query != null) throw IOException("Release feed query strings are not supported")
        val feed = fetch(source, MAX_FEED_BYTES)
        val signature = fetch(validatedUrl(feedUrl + ".sig"), MAX_SIGNATURE_BYTES)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(feed)
        if (!verifier.verify(signature)) throw SignatureException("Invalid release feed signature")

        // Decode and parse only after authentication of the exact original bytes.
        val properties = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            Properties().also { values ->
                InputStreamReader(ByteArrayInputStream(feed), decoder).use(values::load)
            }
        } catch (error: Exception) {
            throw IOException("Malformed release feed", error)
        }
        if (properties.stringPropertyNames() != FEED_KEYS || properties.getProperty("schema.version") != "1") {
            throw IOException("Unsupported release feed schema")
        }
        fun field(key: String): String = properties.getProperty(key) ?: throw IOException("Incomplete release feed")
        val release = Release(
            field("platform"), field("versionCode").toLongOrNull() ?: throw IOException("Invalid release version"),
            field("versionName"), field("url"), field("sha256").lowercase(Locale.ROOT),
            field("size").toLongOrNull() ?: throw IOException("Invalid release size")
        )
        validateRelease(release)
        if (release.platform != platform) throw IOException("Release feed platform does not match this application")
        return release.takeIf { it.versionCode > currentCode }
    }

    /** Streams into a sibling temporary file, verifies it, then replaces the destination. */
    fun download(release: Release, destination: File): File {
        validateRelease(release)
        if (destination.isDirectory) throw IOException("Artifact destination is a directory")
        if (isVerified(destination, release)) return destination
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Missing artifact parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create artifact directory")
        val temporary = File.createTempFile(".gostex-download-", ".part", parent)
        try {
            val connection = open(validatedUrl(release.url))
            try {
                val advertised = connection.getHeaderFieldLong("Content-Length", -1L)
                if (advertised >= 0 && advertised != release.size) throw IOException("Artifact size does not match the signed release")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            checkInterrupted()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > release.size || total > MAX_ARTIFACT_BYTES) throw IOException("Artifact exceeds the signed size")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (total != release.size || !MessageDigest.isEqual(digest.digest(), decodeHash(release.sha256))) {
                    throw IOException("Artifact integrity verification failed")
                }
            } finally {
                connection.disconnect()
            }
            checkInterrupted()
            // Both files share a directory/filesystem. Never delete the old file to make rename succeed.
            if (!temporary.renameTo(destination.absoluteFile)) throw IOException("Cannot atomically replace the artifact")
            if (!isVerified(destination, release)) throw IOException("Saved artifact integrity verification failed")
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun validateRelease(release: Release) {
        if (!release.platform.matches(Regex("[a-z][a-z0-9-]{0,63}")) || release.versionCode <= 0 ||
            release.versionName.isBlank() || release.versionName.length > 128 ||
            release.versionName.any { it.isISOControl() } || !release.sha256.matches(Regex("[a-fA-F0-9]{64}")) ||
            release.size !in 1..MAX_ARTIFACT_BYTES) throw IOException("Invalid signed release fields")
        validatedUrl(release.url)
    }

    private fun validatedUrl(value: String): URL {
        if (value.length > 8192 || value.any { it.isWhitespace() || it.isISOControl() }) throw IOException("Invalid release URL")
        val url = try { URL(value) } catch (error: Exception) { throw IOException("Invalid release URL", error) }
        val localHttp = allowLocalHttpForTests && url.protocol == "http" &&
            url.host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        if ((url.protocol != "https" && !localHttp) || url.host.isEmpty() || url.userInfo != null ||
            url.ref != null || url.port !in -1..65535 || url.port == 0) throw IOException("Release URL is not allowed")
        return url
    }

    private fun open(source: URL): HttpURLConnection {
        var current = source
        var redirects = 0
        while (true) {
            checkInterrupted()
            val connection = validatedUrl(current.toExternalForm()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "GOSTEX-Release/1")
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many release redirects")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing release redirect location")
                    val next = validatedUrl(URL(current, location).toExternalForm())
                    if (current.protocol == "https" && next.protocol != "https") throw IOException("Release redirect downgrade rejected")
                    current = next
                    connection.disconnect()
                } else {
                    if (status != HttpURLConnection.HTTP_OK) throw IOException("Release server returned HTTP $status")
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
    }

    private fun fetch(url: URL, limit: Int): ByteArray {
        val connection = open(url)
        return try {
            if (connection.getHeaderFieldLong("Content-Length", -1L) > limit) throw IOException("Release response is too large")
            connection.inputStream.use { readBounded(it, limit) }
        } finally {
            connection.disconnect()
        }
    }

    private fun isVerified(file: File, release: Release): Boolean {
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16_384)
            var total = 0L
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > release.size) return false
                digest.update(buffer, 0, count)
            }
            return total == release.size && MessageDigest.isEqual(digest.digest(), decodeHash(release.sha256))
        }
    }

    private fun decodeHash(value: String): ByteArray = ByteArray(32) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    companion object {
        private const val MAX_FEED_BYTES = 65_536
        private const val MAX_SIGNATURE_BYTES = 4096
        private const val MAX_ARTIFACT_BYTES = 1_073_741_824L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val FEED_KEYS = setOf("schema.version", "platform", "versionCode", "versionName", "url", "sha256", "size")
    }
}

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Release operation interrupted")
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkInterrupted()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Release response exceeds the size limit")
        output.write(buffer, 0, count)
    }
}
