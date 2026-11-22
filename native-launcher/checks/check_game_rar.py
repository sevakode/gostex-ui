"""Test the real RAR admission, extraction and resume with synthetic RAR4/5 files."""
from pathlib import Path
import struct
import subprocess
import tempfile
import zlib

from kotlin_toolchain import COMPILER, JAVA, ROOT, jar


def vint(value):
    encoded = bytearray()
    while value > 0x7f:
        encoded.append((value & 0x7f) | 0x80)
        value >>= 7
    return bytes(encoded + bytes([value]))


def rar5_block(fields):
    header = vint(len(fields)) + fields
    return struct.pack('<I', zlib.crc32(header)) + header


def rar5(path, members):
    with path.open('wb') as out:
        out.write(b'Rar!\x1a\x07\x01\0' + rar5_block(bytes([1, 0, 0])))
        for name, body in members:
            encoded = name.encode('utf-8')
            if body is None:
                fields = bytes([2, 0, 1, 0]) + vint(0o40755)
            else:
                fields = (bytes([2, 2]) + vint(len(body)) + bytes([4]) + vint(len(body)) +
                          vint(0o100644) + struct.pack('<I', zlib.crc32(body)))
            out.write(rar5_block(fields + bytes([0, 1]) + vint(len(encoded)) + encoded))
            if body is not None:
                out.write(body)
        out.write(rar5_block(bytes([5, 0, 0])))


def rar4_block(type_id, flags, payload):
    fields = struct.pack('<BHH', type_id, flags, 7 + len(payload)) + payload
    return struct.pack('<H', zlib.crc32(fields) & 0xffff) + fields


def rar4(path, members):
    with path.open('wb') as out:
        out.write(b'Rar!\x1a\x07\0' + rar4_block(0x73, 0, bytes(6)))
        for name, body in members:
            is_dir = body is None
            body = body or b''
            encoded = name.encode('ascii')
            fields = struct.pack('<IIBIIBBHI', len(body), len(body), 3, zlib.crc32(body), 0,
                                 20, 0x30, len(encoded), 0o40755 if is_dir else 0o100644)
            out.write(rar4_block(0x74, 0x80e0 if is_dir else 0x8000, fields + encoded))
            out.write(body)
        out.write(rar4_block(0x7b, 0x4000, b''))


checks = r'''
package tv.orbit.shield

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.concurrent.CancellationException

fun main(args: Array<String>) {
    val fixtures = File(args.single())
    val expected = mapOf("Disc/Game.cue" to "FILE \"Track01.bin\" BINARY\n".toByteArray(),
        "Disc/Track01.bin" to ByteArray(81_920) { it.toByte() }, "Empty.txt" to byteArrayOf())
    for (name in listOf("ordinary4.rar", "ordinary5.rar")) {
        val file = File(fixtures, name)
        val original = file.readBytes()
        val seen = mutableListOf<String>()
        val saved = mutableMapOf<String, ByteArray>()
        val directories = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        fun output(path: String) = object : ByteArrayOutputStream() {
            init { seen += path }
            override fun close() { saved[path] = toByteArray() }
        }
        FileChannel.open(file.toPath(), READ).use { input ->
            input.position(2)
            check(GameArchiveFormat.detect(input) == GameArchiveFormat.RAR && input.position() == 2L)
            check(GameArchiveFormat.supportsName("TEST.RAR") && !GameArchiveFormat.supportsName("TEST.r00"))
            val plan = GameRarArchive.inspect(input) {}
            check(input.isOpen && plan.format == GameArchiveFormat.RAR && plan.fileCount == expected.size)
            check(plan.sourceMetadata?.length == 64 && plan.entries.any { it.path == "Empty" && it.directory })
            check(runCatching {
                GameRarArchive.extract(input, plan, directories::add, ::output,
                    onFileCompleted = { completed += it.path; throw CancellationException("pause") })
            }.exceptionOrNull() is CancellationException)
            val first = completed.single()
            check(saved.getValue(first).contentEquals(expected.getValue(first)))
            seen.clear()
            val progress = mutableListOf<Long>()
            GameRarArchive.extract(input, plan, directories::add, ::output, onProgress = progress::add,
                completed = { it.path in completed }, onFileCompleted = { completed += it.path })
            check(input.isOpen && first !in seen && completed == expected.keys && saved.keys == expected.keys)
            check(expected.all { (path, bytes) -> saved.getValue(path).contentEquals(bytes) })
            check(directories.containsAll(listOf("Disc", "Empty")))
            check(progress.first() == 0L && progress.last() == plan.totalBytes &&
                progress.zipWithNext().all { (previous, next) -> previous <= next })
            check(runCatching {
                GameRarArchive.extract(input, plan.copy(sourceMetadata = "stale"), { error("unexpected mkdir") },
                    { error("unexpected write") })
            }.isFailure)
        }
        check(file.readBytes().contentEquals(original))
    }
    for (name in listOf("missing-end.rar", "traversal.rar", "too-many-siblings.rar")) {
        FileChannel.open(File(fixtures, name).toPath(), READ).use { input ->
            check(runCatching { GameRarArchive.inspect(input) {} }.isFailure) { name }
            check(input.isOpen)
        }
    }
    FileChannel.open(File(fixtures, "large.rar").toPath(), READ).use { input ->
        val loadedAt = System.nanoTime()
        val plan = GameRarArchive.inspect(input) {}
        val inspectedAt = System.nanoTime()
        check(plan.fileCount == 57_338 && plan.totalBytes == 57_338L)
        val digest = MessageDigest.getInstance("SHA-256")
        val counter = object : OutputStream() {
            override fun write(value: Int) = digest.update(value.toByte())
            override fun write(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
        }
        var written = 0
        GameRarArchive.extract(input, plan, {}, { counter }, onFileCompleted = { written++ })
        check(written == 57_338 && digest.digest().contentEquals(MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(57_338) { it.toByte() })))
        println("PASS 57,338 RAR5 members, inspect ${(inspectedAt - loadedAt) / 1_000_000} ms, full checked extraction ${(System.nanoTime() - inspectedAt) / 1_000_000} ms")
    }
    println("PASS RAR4/5 preview, CUE/BIN/empty members, cancel/resume identity, stale source and structural rejection")
}
'''


if __name__ == '__main__':
    app = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
    classpath = ':'.join(str(p) for p in [COMPILER[1], jar('com.github.junrar', 'junrar', '8.1.2'),
                                          jar('org.slf4j', 'slf4j-api', '2.0.17')])
    with tempfile.TemporaryDirectory(prefix='gostex-rar-') as temporary:
        folder = Path(temporary)
        small = [('Disc/Game.cue', b'FILE "Track01.bin" BINARY\n'),
                 ('Disc/Track01.bin', bytes(range(256))*320), ('Empty', None), ('Empty.txt', b'')]
        rar4(folder / 'ordinary4.rar', small)
        rar5(folder / 'ordinary5.rar', small)
        (folder / 'missing-end.rar').write_bytes((folder / 'ordinary5.rar').read_bytes()[:-8])
        rar5(folder / 'traversal.rar', [('../escape', b'x')])
        rar5(folder / 'too-many-siblings.rar', ((f'only/secondary{n}.txt', b'x') for n in range(10_001)))
        rar5(folder / 'large.rar', ((f'Group{n//1_000:02}/'+('fixture-'*18)+f'{n:05}.bin', bytes([n & 255]))
                                    for n in range(57_338)))
        source = folder / 'Checks.kt'
        source.write_text(checks)
        classes = folder / 'classes'
        subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), str(source),
                        *[str(app / name) for name in ('TorrDownloadPaths.kt', 'GameArchivePlan.kt', 'GameRarAdmission.kt',
                                                       'GameRarArchive.kt')]], check=True)
        subprocess.run([str(JAVA), '-Xmx256m', '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.ChecksKt', str(folder)], check=True)
