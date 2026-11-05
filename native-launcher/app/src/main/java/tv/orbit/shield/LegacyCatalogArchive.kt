package tv.orbit.shield

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Extracts data from an already authenticated historical APK, without installing it.
 * Callers must first verify the signed release feed and the entire APK's size/hash. */
internal object LegacyCatalogArchive {
    const val HISTORICAL_CATALOG_SHA256 = "0f12ccb33e7afd7daeb395ffcc7958f70b8ee44cace2119bee3da91e9a3d2536"
    private const val CATALOG_PATH = "assets/catalog.json"
    private const val MAX_APK_BYTES = 64L * 1024 * 1024
    private const val MAX_CATALOG_BYTES = 1024 * 1024
    private const val MAX_ENTRIES = 30_000

    fun extract(apk: File, expectedSha256: String = HISTORICAL_CATALOG_SHA256, checkpoint: () -> Unit = {}): String {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid catalog checksum" }
        require(apk.isFile && apk.length() in 1..MAX_APK_BYTES) { "Invalid historical package" }
        return ZipFile(apk).use { zip ->
            val entries = zip.entries()
            var count = 0
            var catalog: java.util.zip.ZipEntry? = null
            while (entries.hasMoreElements()) {
                checkpoint()
                val entry = entries.nextElement()
                check(++count <= MAX_ENTRIES) { "Historical package has too many entries" }
                if (entry.name == CATALOG_PATH) {
                    check(catalog == null && !entry.isDirectory) { "Ambiguous historical catalog" }
                    catalog = entry
                }
            }
            val entry = checkNotNull(catalog) { "Historical catalog is missing" }
            check(entry.size in 1..MAX_CATALOG_BYTES.toLong() && entry.compressedSize in 1..MAX_APK_BYTES) {
                "Historical catalog exceeds its size limit"
            }
            val output = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkpoint()
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= MAX_CATALOG_BYTES) { "Historical catalog exceeds its size limit" }
                    output.write(buffer, 0, read)
                }
            }
            val bytes = output.toByteArray()
            check(bytes.size.toLong() == entry.size) { "Historical catalog is incomplete" }
            val expected = ByteArray(32) { i -> expectedSha256.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), expected)) {
                "Historical catalog checksum mismatch"
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}
