package tv.orbit.shield

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import kotlin.coroutines.EmptyCoroutineContext

internal enum class HttpDownloadAdmissionError {
    INVALID_LINK, CLEARTEXT_CONFIRMATION, REDIRECT, AUTH, NOT_FOUND, UNSUPPORTED, RESPONSE, TIMEOUT, TLS, NETWORK,
}

/** Do not attach a network or URI exception as a cause: it may include the full link. */
internal class HttpDownloadAdmissionException(val reason: HttpDownloadAdmissionError) : IOException(reason.name)

/** Holds a potentially private URL. Only the transport and private journal should use [serialized]. */
internal class HttpDownloadUrl private constructor(private val uri: URI) {
    internal val serialized: String = uri.toASCIIString()
    private val scheme = uri.scheme.lowercase(Locale.ROOT)
    private val host = uri.host.lowercase(Locale.ROOT)
    private val port = if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port
    internal val cleartext: Boolean get() = scheme == "http"

    override fun toString(): String = "HttpDownloadUrl(redacted)"
    override fun equals(other: Any?): Boolean = other is HttpDownloadUrl && serialized == other.serialized
    override fun hashCode(): Int = serialized.hashCode()

    internal fun resourceKey(): String {
        // Preserve the raw path and query. Ordering or decoding them can change the resource.
        val target = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val identity = "$scheme://$host:$port$target"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal fun connection(): HttpURLConnection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection

    internal fun redirect(location: String): HttpDownloadUrl {
        val next = try {
            require(location.isNotBlank() && location.length <= MAX_INPUT && location.none(Char::isISOControl))
            val reference = URI(location)
            // java.net.URI.resolve drops the basename for some query-only references.
            val resolved = if (reference.scheme == null && reference.rawAuthority == null &&
                reference.rawPath.isEmpty() && reference.rawQuery != null && reference.rawFragment == null) {
                URI("${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?${reference.rawQuery}")
            } else uri.resolve(reference)
            parse(resolved.toASCIIString())
        } catch (_: URISyntaxException) { fail(HttpDownloadAdmissionError.REDIRECT) }
        catch (_: IllegalArgumentException) { fail(HttpDownloadAdmissionError.REDIRECT) }
        catch (_: HttpDownloadAdmissionException) { fail(HttpDownloadAdmissionError.REDIRECT) }
        if (next.scheme != scheme || next.host != host || next.port != port) fail(HttpDownloadAdmissionError.REDIRECT)
        return next
    }

    companion object {
        private const val MAX_INPUT = 8192

        fun parse(value: String): HttpDownloadUrl {
            if (value.length !in 1..MAX_INPUT || value.any(Char::isISOControl)) fail(HttpDownloadAdmissionError.INVALID_LINK)
            val uri = try { URI(URI(value.trim()).toASCIIString()) }
                catch (_: URISyntaxException) { fail(HttpDownloadAdmissionError.INVALID_LINK) }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank() || '%' in host || uri.rawUserInfo != null ||
                uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535) ||
                uri.rawAuthority.endsWith(':') || uri.toASCIIString().length > MAX_INPUT) {
                fail(HttpDownloadAdmissionError.INVALID_LINK)
            }
            return HttpDownloadUrl(uri)
        }
    }
}

/** Null URL is valid only for a finished record; the private locator is no longer needed. */
internal data class HttpDownloadOrigin(
    val url: HttpDownloadUrl?,
    val resourceKey: String,
    val expectedEtag: String?, // Null means a whole-file transfer only.
) {
    override fun toString(): String = "HttpDownloadOrigin(redacted)"
}

internal data class HttpDownloadAdmissionResult(
    val url: HttpDownloadUrl,
    val resourceKey: String,
    val size: Long,
    val strongEtag: String?,
) {
    override fun toString(): String = "HttpDownloadAdmissionResult(size=$size)"
}

private fun fail(reason: HttpDownloadAdmissionError): Nothing = throw HttpDownloadAdmissionException(reason)

/** RFC 9110 §8.8.3 opaque tag syntax without the optional weak prefix. */
internal fun isStrongHttpEtag(tag: String?): Boolean = tag != null && tag.length in 2..8192 &&
    tag.first() == '"' && tag.last() == '"' && (1 until tag.lastIndex).all { index ->
        tag[index].code.let { it == 0x21 || it in 0x23..0x7e || it in 0x80..0xff }
    }

/** Owns at most the current connection of one attempt, including redirect hops. */
internal class HttpDownloadAbort {
    @Volatile var cancelled: Boolean = false
        private set
    private var active: HttpURLConnection? = null

    @Synchronized internal fun attach(connection: HttpURLConnection) {
        if (cancelled) throw CancellationException()
        check(active == null)
        active = connection
    }

    @Synchronized internal fun detach(connection: HttpURLConnection) {
        if (active === connection) active = null
    }

    fun cancel() {
        val connection = synchronized(this) {
            if (cancelled) return
            cancelled = true
            active.also { active = null }
        }
        // Some platform/JDK implementations wait for a read lock in disconnect().
        // Keep it off the cancelling thread and close only this attempt's socket.
        if (connection != null) Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            runCatching { connection.disconnect() }
        }
    }
}

internal fun HttpURLConnection.singleDownloadHeader(name: String, error: HttpDownloadAdmissionError): String? {
    val values = headerFields.entries.filter { it.key?.equals(name, ignoreCase = true) == true }.flatMap { it.value }
    if (values.size > 1) fail(error)
    return values.singleOrNull()
}

private const val MAX_HTTP_DOWNLOAD_HOPS = 3

/** Admission and transfer share clear routing, redirect validation and connection ownership. */
internal fun <T> withHttpDownloadResponse(
    original: HttpDownloadUrl,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    cancelled: () -> Boolean,
    abort: HttpDownloadAbort?,
    configure: HttpURLConnection.() -> Unit,
    consume: (HttpDownloadUrl, HttpURLConnection, Int) -> T,
): T {
    fun checkActive() {
        if (abort?.cancelled == true || cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
    }
    fun <R> network(block: () -> R): R = try { block() }
    catch (_: SocketTimeoutException) { checkActive(); fail(HttpDownloadAdmissionError.TIMEOUT) }
    catch (_: SSLException) { checkActive(); fail(HttpDownloadAdmissionError.TLS) }
    catch (_: IOException) { checkActive(); fail(HttpDownloadAdmissionError.NETWORK) }
    catch (_: SecurityException) { checkActive(); fail(HttpDownloadAdmissionError.NETWORK) }
    catch (_: IllegalArgumentException) { fail(HttpDownloadAdmissionError.RESPONSE) }

    var current = original
    repeat(MAX_HTTP_DOWNLOAD_HOPS + 1) { hop ->
        checkActive()
        val connection = network { current.connection() }
        try {
            val status = network {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Connection", "close")
                connection.configure()
                checkActive()
                // Android disconnect before connect() is a no-op. Attach only after TCP/TLS setup;
                // cancellation during setup is checked again before we start waiting for headers.
                connection.connect()
                abort?.attach(connection)
                checkActive()
                connection.responseCode
            }
            checkActive()
            when (status) {
                301, 302, 303, 307, 308 -> {
                    if (hop == MAX_HTTP_DOWNLOAD_HOPS) fail(HttpDownloadAdmissionError.REDIRECT)
                    current = current.redirect(connection.singleDownloadHeader("Location", HttpDownloadAdmissionError.REDIRECT)
                        ?: fail(HttpDownloadAdmissionError.REDIRECT))
                }
                in 300..399 -> fail(HttpDownloadAdmissionError.REDIRECT)
                else -> return consume(current, connection, status)
            }
        } finally {
            abort?.detach(connection)
            runCatching { connection.disconnect() }
        }
    }
    fail(HttpDownloadAdmissionError.REDIRECT)
}

/** Identify a known-size file with a one-byte Range request, before creating a partial. */
internal object HttpDownloadAdmission {
    private const val MAX_TIMEOUT_MS = 5_000
    private const val PROBE_DEADLINE_MS = 12_000L
    private val singleByteRange = Regex("bytes 0-0/([1-9][0-9]{0,18})", RegexOption.IGNORE_CASE)
    private val contentLength = Regex("[1-9][0-9]{0,18}")

    suspend fun probeCancellable(value: String, allowCleartext: Boolean = false): HttpDownloadAdmissionResult =
        withTimeoutOrNull(PROBE_DEADLINE_MS) {
            suspendCancellableCoroutine { continuation ->
                val abort = HttpDownloadAbort()
                continuation.invokeOnCancellation { abort.cancel() }
                // Keep the suspension distinct from the worker so this deadline also bounds
                // waiting for an IO lane or a platform connect still unwinding after cancellation.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    if (continuation.isActive) continuation.resumeWith(runCatching {
                        probe(value, allowCleartext, abort = abort)
                    })
                }
            }
        } ?: fail(HttpDownloadAdmissionError.TIMEOUT)

    fun probe(
        value: String,
        allowCleartext: Boolean = false,
        timeoutMs: Int = MAX_TIMEOUT_MS,
        cancelled: () -> Boolean = { false },
        abort: HttpDownloadAbort? = null,
    ): HttpDownloadAdmissionResult {
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        fun checkActive() {
            if (abort?.cancelled == true || cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        checkActive()
        val original = HttpDownloadUrl.parse(value)
        if (original.cleartext && !allowCleartext) fail(HttpDownloadAdmissionError.CLEARTEXT_CONFIRMATION)
        return withHttpDownloadResponse(original, timeoutMs, timeoutMs, cancelled, abort,
            configure = { setRequestProperty("Range", "bytes=0-0") }) { current, connection, status ->
            fun header(name: String) = connection.singleDownloadHeader(name, HttpDownloadAdmissionError.RESPONSE)
            fun requireUnencodedBody() {
                val encoding = header("Content-Encoding")
                if ((encoding != null && !encoding.equals("identity", ignoreCase = true)) || header("Transfer-Encoding") != null) {
                    fail(HttpDownloadAdmissionError.UNSUPPORTED)
                }
            }
            when (status) {
                200 -> {
                    // A server that ignores Range may send the entire file. Do not read its body.
                    requireUnencodedBody()
                    if (header("Content-Range") != null) fail(HttpDownloadAdmissionError.UNSUPPORTED)
                    val size = header("Content-Length")?.takeIf(contentLength::matches)?.toLongOrNull()
                        ?: fail(HttpDownloadAdmissionError.UNSUPPORTED)
                    HttpDownloadAdmissionResult(original, current.resourceKey(), size, null)
                }
                206 -> {
                    requireUnencodedBody()
                    if (header("Content-Length") != "1") fail(HttpDownloadAdmissionError.UNSUPPORTED)
                    val size = header("Content-Range")?.let { singleByteRange.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
                        ?: fail(HttpDownloadAdmissionError.UNSUPPORTED)
                    val etag = header("ETag")
                    val strong = etag?.takeIf(::isStrongHttpEtag)
                    if (etag != null && strong == null && !(etag.startsWith("W/") && isStrongHttpEtag(etag.substring(2)))) {
                        fail(HttpDownloadAdmissionError.UNSUPPORTED)
                    }
                    checkActive()
                    val firstByte = try { connection.inputStream.use { it.read() } }
                    catch (_: SocketTimeoutException) { checkActive(); fail(HttpDownloadAdmissionError.TIMEOUT) }
                    catch (_: SSLException) { checkActive(); fail(HttpDownloadAdmissionError.TLS) }
                    catch (_: IOException) { checkActive(); fail(HttpDownloadAdmissionError.NETWORK) }
                    checkActive()
                    if (firstByte < 0) fail(HttpDownloadAdmissionError.RESPONSE)
                    HttpDownloadAdmissionResult(original, current.resourceKey(), size, strong)
                }
                401, 403, 407 -> fail(HttpDownloadAdmissionError.AUTH)
                404, 410 -> fail(HttpDownloadAdmissionError.NOT_FOUND)
                204, 416 -> fail(HttpDownloadAdmissionError.UNSUPPORTED)
                else -> fail(HttpDownloadAdmissionError.RESPONSE)
            }
        }
    }
}
