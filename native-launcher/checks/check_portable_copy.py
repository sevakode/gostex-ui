"""Exercise portable copy's stream, verification, limits and cancellation contracts."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/PortableVerifiedCopy.kt'
checks = r'''package tv.orbit.shield
import java.io.*
import java.security.MessageDigest

private class Staging(val transform: (ByteArray) -> ByteArray = { it }) : PortableCopyDestination {
    val committed = byteArrayOf(3, 1, 4)
    private val buffer = ByteArrayOutputStream()
    var opened = false
    var verifyOpened = false
    override fun openNew(): OutputStream { check(!opened); opened = true; return buffer }
    override fun openForVerification(): InputStream {
        verifyOpened = true
        return ByteArrayInputStream(transform(buffer.toByteArray()))
    }
}
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
fun main() {
    var count = 0
    fun ok(v: Boolean, msg: String) { check(v) { msg }; count++ }
    val data = ByteArray(2 * 1024 * 1024 + 17) { (it * 31).toByte() }
    val unchanged = data.clone()
    val good = Staging()
    val receipt = portableVerifiedCopy({ ByteArrayInputStream(data) }, good, data.size.toLong())
    ok(receipt.bytes == data.size.toLong() && receipt.sha256 == sha(data), "verified streamed copy")
    portableVerifySourceMatches({ ByteArrayInputStream(data) }, receipt)
    ok(runCatching { portableVerifySourceMatches({ ByteArrayInputStream(data.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }) }, receipt) }.isFailure,
        "same-size modified source rejected")
    ok(runCatching { portableVerifySourceMatches({ ByteArrayInputStream(data.copyOf(data.size - 1)) }, receipt) }.isFailure,
        "shortened source rejected")
    var sourceChecks = 0
    ok(runCatching { portableVerifySourceMatches({ ByteArrayInputStream(data) }, receipt) {
        if (++sourceChecks == 3) throw IOException("cancelled")
    } }.isFailure && sourceChecks == 3, "source verification cancellation")
    ok(data.contentEquals(unchanged) && good.committed.contentEquals(byteArrayOf(3,1,4)), "source and previous copy unchanged")
    val empty = portableVerifiedCopy({ ByteArrayInputStream(byteArrayOf()) }, Staging(), 0)
    ok(empty.bytes == 0L && empty.sha256 == sha(byteArrayOf()), "empty file")
    for (transform in listOf<(ByteArray) -> ByteArray>(
        { it.dropLast(1).toByteArray() },
        { it + byteArrayOf(7) },
        { it.clone().also { b -> b[0] = (b[0].toInt() xor 1).toByte() } }
    )) {
        val dst = Staging(transform)
        ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, dst, Long.MAX_VALUE) }.isFailure,
            "reject destination corruption or length change")
        ok(dst.committed.contentEquals(byteArrayOf(3,1,4)) && data.contentEquals(unchanged), "failed verification does not modify source or previous copy")
    }
    val tooSmall = Staging()
    ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, tooSmall, 4) }.isFailure && !tooSmall.verifyOpened,
        "limit stops before verification")
    var sourceOpened = false
    ok(runCatching { portableVerifiedCopy({ sourceOpened = true; ByteArrayInputStream(data) }, Staging(), -1) }.isFailure && !sourceOpened,
        "invalid limit does not open source")
    var checks = 0
    val cancelled = Staging()
    ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, cancelled, Long.MAX_VALUE) {
        checks++; if (checks == 3) throw IOException("cancelled")
    } }.isFailure && !cancelled.verifyOpened && cancelled.committed.contentEquals(byteArrayOf(3,1,4)), "copy cancellation")
    val verifyCancelled = Staging()
    ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, verifyCancelled, Long.MAX_VALUE) {
        if (verifyCancelled.verifyOpened) throw IOException("cancelled")
    } }.isFailure && verifyCancelled.verifyOpened && verifyCancelled.committed.contentEquals(byteArrayOf(3,1,4)), "verification cancellation")
    val stalled = object : InputStream() {
        override fun read() = 0
        override fun read(b: ByteArray, off: Int, len: Int) = 0
    }
    ok(runCatching { portableVerifiedCopy({ stalled }, Staging(), Long.MAX_VALUE) }.isFailure, "stalled source does not loop")
    val failing = object : PortableCopyDestination {
        override fun openNew() = object : OutputStream() {
            override fun write(b: Int) { throw IOException("no space") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("no space") }
        }
        override fun openForVerification(): InputStream = error("must not verify")
    }
    ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, failing, Long.MAX_VALUE) }.isFailure, "write failure propagated")
    var sourceClosed = false
    val trackedSource = object : ByteArrayInputStream(data) {
        override fun close() { sourceClosed = true; super.close() }
    }
    val cannotCreate = object : PortableCopyDestination {
        override fun openNew(): OutputStream = throw IOException("cannot create")
        override fun openForVerification(): InputStream = error("must not verify")
    }
    ok(runCatching { portableVerifiedCopy({ trackedSource }, cannotCreate, Long.MAX_VALUE) }.isFailure && sourceClosed,
        "source closed when destination cannot be opened")
    var verificationOpened = false
    val cannotClose = object : PortableCopyDestination {
        override fun openNew(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close failed") }
        }
        override fun openForVerification(): InputStream { verificationOpened = true; return ByteArrayInputStream(data) }
    }
    ok(runCatching { portableVerifiedCopy({ ByteArrayInputStream(data) }, cannotClose, Long.MAX_VALUE) }.isFailure && !verificationOpened,
        "close failure never commits")
    println("PASS: $count portable copy checks")
}
'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]),
                    'tv.orbit.shield.ChecksKt'], check=True)
