"""Fault-inject the production SAF target and Engine at the Android fd boundary.

This is a host model of Android APIs, not a physical USB/provider integration test.
The stream data and successful fsync calls use real temporary host files.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import os
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

STUBS = {
    'Uri.kt': r'''package android.net
class Uri(val scheme: String?) { companion object { fun parse(value: String) = Uri(value.substringBefore(':')) } }
''',
    'ContentResolver.kt': r'''package android.content
import android.net.Uri
import android.os.ParcelFileDescriptor
abstract class ContentResolver {
 abstract fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor?
 companion object { const val SCHEME_CONTENT = "content" }
}
''',
    'ParcelFileDescriptor.kt': r'''package android.os
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
class ParcelFileDescriptor(val file: RandomAccessFile) {
 val fileDescriptor: FileDescriptor = file.fd
 var reliable = true
 var pendingError = false
 var checkCalls = 0
 var closeCalls = 0
 var syncCalls = 0
 var statvfsCalls = 0
 var available = 100L
 var fragment = 4096L
 var fault = ""
 var afterSync: () -> Unit = {}
 init { descriptors[fileDescriptor] = this }
 fun canDetectErrors() = reliable
 fun checkError() { checkCalls++; if (pendingError) throw IOException("Provider reported write failure") }
 fun close() { closeCalls++; file.close(); fail("close") }
 fun fail(operation: String) { if (fault == operation) throw IOException("Controlled $operation failure") }
 class AutoCloseOutputStream(private val descriptor: ParcelFileDescriptor): OutputStream() {
  override fun write(value: Int) { descriptor.fail("write"); descriptor.file.write(value) }
  override fun write(bytes: ByteArray, offset: Int, count: Int) { descriptor.fail("write"); descriptor.file.write(bytes, offset, count) }
  override fun close() = descriptor.close()
 }
 companion object { val descriptors = mutableMapOf<FileDescriptor, ParcelFileDescriptor>() }
}
''',
    'Os.kt': r'''package android.system
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.nio.ByteBuffer
class StructStat(val st_size: Long)
class StructStatVfs(val f_bavail: Long, val f_frsize: Long)
object OsConstants { const val SEEK_SET = 0; const val SEEK_CUR = 1 }
object Os {
 private fun target(fd: FileDescriptor) = ParcelFileDescriptor.descriptors.getValue(fd)
 fun fstat(fd: FileDescriptor): StructStat { val d = target(fd); d.fail("stat"); return StructStat(d.file.length()) }
 fun ftruncate(fd: FileDescriptor, size: Long) { val d = target(fd); d.fail("truncate"); d.file.setLength(size) }
 fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long {
  val d = target(fd); d.fail("seek"); d.file.seek(if (whence == OsConstants.SEEK_CUR) d.file.filePointer + offset else offset); return d.file.filePointer
 }
 fun pread(fd: FileDescriptor, data: ByteArray, from: Int, count: Int, offset: Long): Int {
  val d = target(fd); d.fail("pread")
  return d.file.channel.read(ByteBuffer.wrap(data, from, minOf(count, 2)), offset)
 }
 fun fsync(fd: FileDescriptor) { val d = target(fd); d.syncCalls++; d.fail("sync"); d.file.fd.sync(); d.afterSync() }
 fun fstatvfs(fd: FileDescriptor): StructStatVfs {
  val d = target(fd); d.statvfsCalls++; d.fail("space"); return StructStatVfs(d.available, d.fragment)
 }
}
''',
    'Checks.kt': r'''package tv.orbit.shield
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
private val uri = Uri.parse("content://fixture/temporary")
private class Resolver(val descriptor: ParcelFileDescriptor?): ContentResolver() {
 var denied = false
 override fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? {
  check(mode == "rw")
  if (denied) throw SecurityException("Permission revoked")
  return descriptor
 }
}
private inline fun withDescriptor(block: (ParcelFileDescriptor) -> Unit) {
 val path = Files.createTempFile("gostex-saf-", ".partial")
 val descriptor = ParcelFileDescriptor(RandomAccessFile(path.toFile(), "rw"))
 try { block(descriptor) } finally { runCatching { descriptor.file.close() }; ParcelFileDescriptor.descriptors.remove(descriptor.fileDescriptor); Files.deleteIfExists(path) }
}
private inline fun storageFailure(block: () -> Unit) {
 val error = runCatching(block).exceptionOrNull()
 check(error is TorrStorageFailure) { "Expected storage failure, got $error" }
}
fun main(args: Array<String>) {
 val port = args.single().toInt()
 // Reproduction: the peer has failed while the local seekable fd still works.
 withDescriptor { d ->
  val target = TorrSafTarget.open(Resolver(d), uri)
  target.write(byteArrayOf(1,2,3), 3)
  d.afterSync = { d.pendingError = true }
  storageFailure { target.sync() }
  check(d.syncCalls == 1 && d.file.length() == 3L)
  storageFailure { target.close() }
  check(d.closeCalls == 1) // reporting the peer error must never leak the fd
 }
 withDescriptor { d ->
  d.pendingError = true
  storageFailure { TorrSafTarget.open(Resolver(d), uri) }
  check(d.closeCalls == 1 && d.syncCalls == 0)
 }
 withDescriptor { d ->
  d.fault = "seek"
  storageFailure { TorrSafTarget.open(Resolver(d), uri) }
  check(d.closeCalls == 1)
 }
 storageFailure { TorrSafTarget.open(Resolver(null), uri) }
 storageFailure { TorrSafTarget.open(Resolver(null).apply { denied = true }, uri) }
 withDescriptor { d ->
  val target = TorrSafTarget.open(Resolver(d), uri)
  target.write(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
  target.seek(1)
  check(target.matchesAt(2, byteArrayOf(3, 4, 5, 6)) && d.file.filePointer == 1L)
  check(!target.matchesAt(2, byteArrayOf(3, 4, 9, 6)))
  check(!target.matchesAt(4, byteArrayOf(5, 6, 0)))
  d.fault = "pread"
  check(!target.matchesAt(2, byteArrayOf(3, 4, 5, 6)))
  d.fault = ""
  d.pendingError = true
  storageFailure { target.matchesAt(2, byteArrayOf(3, 4, 5, 6)) }
  storageFailure { target.close() }
 }
 for (operation in listOf("stat", "truncate", "seek", "write", "sync", "close")) withDescriptor { d ->
  val target = TorrSafTarget.open(Resolver(d), uri)
  d.fault = operation
  storageFailure {
   when (operation) {
    "stat" -> target.size()
    "truncate" -> target.truncate(0)
    "seek" -> target.seek(0)
    "write" -> target.write(byteArrayOf(1), 1)
    "sync" -> target.sync()
    else -> target.close()
   }
  }
  d.fault = ""
  if (d.closeCalls == 0) target.close()
  check(d.closeCalls == 1)
 }
 withDescriptor { d ->
  d.reliable = false
  d.pendingError = true // no error-channel calls for ordinary providers
  TorrSafTarget.open(Resolver(d), uri).use { target ->
   target.write(byteArrayOf(7,8,9), 3); target.sync(); check(target.size() == 3L)
   check(d.checkCalls == 0 && d.syncCalls == 1 && d.statvfsCalls == 0)
   check(target.availableBytes() == 409600L)
   d.available = 0; check(target.availableBytes() == 0L)
   d.available = Long.MAX_VALUE; check(target.availableBytes() == Long.MAX_VALUE)
   for ((available, fragment) in listOf(-1L to 4096L, 1L to 0L, 1L to -1L)) {
    d.available = available; d.fragment = fragment
    check(runCatching { target.availableBytes() }.exceptionOrNull() is TorrDownloadSpaceUnavailable)
   }
   d.fault = "space"
   check(runCatching { target.availableBytes() }.exceptionOrNull() is TorrDownloadSpaceUnavailable)
   d.fault = ""
  }
  check(d.closeCalls == 1 && d.checkCalls == 0)
 }
 // Real engine must preserve its prior durable offset when raw fsync succeeds
 // but the provider has rejected the destination through the separate channel.
 withDescriptor { d ->
  val target = TorrSafTarget.open(Resolver(d), uri)
  var durable = TorrDownloadCheckpoint(0, null)
  var commits = 0
  d.afterSync = { d.pendingError = true }
  storageFailure {
   TorrDownloadEngine.transfer(TorrDownloadSource("a".repeat(40), 1, 1048576), target, durable, port,
    saveCheckpoint = { durable = it; commits++ }, nowNanos = { 0 })
  }
  check(d.syncCalls == 1 && commits == 0 && durable.offset == 0L && target.size() == 1048576L)
  storageFailure { target.close() }
  check(d.closeCalls == 1)
 }
 println("PASS: provider errors, positional byte verification, fd cleanup, permission and fd faults, space bounds and Engine checkpoint protection")
}
''',
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header('ETag', '"storage-fixture"')
        self.send_header('Content-Length', str(1048576))
        self.end_headers()
        try:
            self.wfile.write(bytes(1048576))
        except (BrokenPipeError, ConnectionResetError):
            pass


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-saf-target-') as td:
        folder = Path(td)
        for name, source in STUBS.items():
            (folder / name).write_text(source)
        target = Path(os.environ.get('GOSTEX_SAF_TARGET_SOURCE', ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadTarget.kt'))
        engine = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadEngine.kt'
        cp = f'{COMPILER[1]}:{COMPILER[4]}'
        command = [str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
                   str(target), str(engine), str(engine.with_name('HttpDownloadAdmission.kt')),
                   *(str(folder / name) for name in STUBS)]
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise SystemExit(result.stderr)
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt',
                                 str(server.server_port)], capture_output=True, text=True, timeout=30)
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.stderr)
finally:
    server.shutdown()
    server.server_close()
    thread.join()
