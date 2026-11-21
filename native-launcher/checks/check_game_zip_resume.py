"""Check real ZIP recovery callbacks against bytes, metadata changes and failed closes."""
import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

fixture = r'''
package tv.orbit.shield

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {
    val folder = Files.createTempDirectory("gostex-zip-resume").toFile()
    var passed = 0
    fun expect(value: Boolean) { check(value); passed++ }
    fun rejected(block: () -> Unit) { expect(runCatching(block).isFailure) }
    fun archive(name: String, items: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
        val file = File(folder, name)
        ZipOutputStream(file.outputStream()).use { output ->
            for ((path, bytes) in items) {
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }
    val cue = "FILE \"Track 01.bin\" BINARY".toByteArray()
    val track = ByteArray(150000) { (it % 251).toByte() }
    val payload = listOf("Disc/Game.cue" to cue, "Disc/Track 01.bin" to track)
    try {
        val good = archive("good.zip", payload)
        val plan = GameZipArchive.inspect(good)
        val files = mutableMapOf<String, ByteArray>()
        val opened = mutableListOf<String>()
        val directories = mutableSetOf<String>()
        val durable = mutableSetOf<String>()
        val closeOrder = mutableListOf<String>()
        fun output(path: String): OutputStream {
            opened += path
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    files[path] = toByteArray()
                    closeOrder += path
                }
            }
        }
        var cancelled = false
        rejected {
            GameZipArchive.extract(good, plan, directories::add, ::output,
                checkpoint = { if (cancelled) throw CancellationException() },
                onFileCompleted = { entry ->
                    expect(entry.path in closeOrder)
                    durable += entry.path
                    cancelled = true
                })
        }
        expect(opened == listOf("Disc/Game.cue") && durable == setOf("Disc/Game.cue"))
        val progress = mutableListOf<Long>()
        val newlyComplete = mutableListOf<String>()
        val checked = mutableListOf<String>()
        opened.clear()
        directories.clear()
        GameZipArchive.extract(good, plan, directories::add, ::output,
            onProgress = progress::add,
            completed = { entry ->
                checked += entry.path
                val existing = files[entry.path]
                entry.path in durable && existing != null && existing.size.toLong() == entry.size &&
                    CRC32().apply { update(existing) }.value == entry.crc
            },
            onFileCompleted = { entry ->
                expect(entry.path in closeOrder)
                newlyComplete += entry.path
            })
        expect(checked == payload.map { it.first })
        expect(opened == listOf("Disc/Track 01.bin") && newlyComplete == opened)
        expect(files.getValue("Disc/Game.cue").contentEquals(cue) && files.getValue("Disc/Track 01.bin").contentEquals(track))
        expect(progress.first() == 0L && progress[1] == cue.size.toLong() && progress.last() == plan.totalBytes)
        expect(progress.zipWithNext().all { (before, after) -> before <= after })
        expect(directories == setOf("Disc"))

        // A completed claim is accepted only after the caller checks actual saved bytes.
        files["Disc/Game.cue"] = cue.clone().apply { this[0] = 0 }
        opened.clear()
        GameZipArchive.extract(good, plan, {}, ::output, completed = { entry ->
            val existing = files[entry.path]
            existing != null && existing.size.toLong() == entry.size && CRC32().apply { update(existing) }.value == entry.crc
        })
        expect(opened == listOf("Disc/Game.cue") && files.getValue("Disc/Game.cue").contentEquals(cue))

        var outputMutations = 0
        var completionQueries = 0
        val changed = archive("changed.zip", listOf("other.bin" to byteArrayOf(3)))
        rejected {
            GameZipArchive.extract(changed, plan, { outputMutations++ }, {
                outputMutations++
                ByteArrayOutputStream()
            }, completed = { completionQueries++; true })
        }
        expect(outputMutations == 0 && completionQueries == 0)

        var finished = 0
        rejected {
            GameZipArchive.extract(good, plan, {}, {
                object : ByteArrayOutputStream() {
                    override fun close() { throw IOException("Failed durable close") }
                }
            }, onFileCompleted = { finished++ })
        }
        expect(finished == 0)
        rejected {
            GameZipArchive.extract(good, plan, {}, {
                object : OutputStream() {
                    override fun write(value: Int) { throw IOException("Failed write") }
                }
            }, onFileCompleted = { finished++ })
        }
        expect(finished == 0)

        val corrupt = archive("corrupt.zip", listOf("payload.bin" to track), stored = true)
        val beforeCorruption = GameZipArchive.inspect(corrupt)
        val bytes = corrupt.readBytes()
        bytes[30 + "payload.bin".length + 5] = (bytes[30 + "payload.bin".length + 5].toInt() xor 1).toByte()
        corrupt.writeBytes(bytes)
        rejected { GameZipArchive.extract(corrupt, beforeCorruption, {}, { ByteArrayOutputStream() }, onFileCompleted = { finished++ }) }
        expect(finished == 0)

        // A callback cannot publish completed data after cancellation becomes visible.
        var cancelAfterWrite = false
        rejected {
            GameZipArchive.extract(good, plan, {}, {
                object : ByteArrayOutputStream() {
                    override fun close() { super.close(); cancelAfterWrite = true }
                }
            }, checkpoint = { if (cancelAfterWrite) throw CancellationException() }, onFileCompleted = { finished++ })
        }
        expect(finished == 0)

        val fingerprint = GameZipArchive.fingerprint(plan)
        expect(fingerprint.matches(Regex("[0-9a-f]{64}")))
        expect(fingerprint == GameZipArchive.fingerprint(GameZipArchive.inspect(good)))
        val first = plan.entries.first()
        val changes = listOf(
            first.copy(path = "Other/Game.cue"), first.copy(directory = true),
            first.copy(size = first.size + 1), first.copy(compressedSize = first.compressedSize + 1),
            first.copy(crc = first.crc xor 1), first.copy(method = ZipEntry.STORED),
        )
        expect(changes.all { changedEntry ->
            fingerprint != GameZipArchive.fingerprint(plan.copy(entries = listOf(changedEntry) + plan.entries.drop(1)))
        })
        expect(fingerprint != GameZipArchive.fingerprint(plan.copy(entries = plan.entries.reversed())))
        expect(fingerprint != GameZipArchive.fingerprint(plan.copy(fileCount = plan.fileCount + 1)))
        expect(fingerprint != GameZipArchive.fingerprint(plan.copy(totalBytes = plan.totalBytes + 1)))
        println("PASS ZIP recovery engine: $passed checks, resumed bytes, failed writes/closes, CRC, cancellation and ordered metadata identity")
    } finally {
        folder.deleteRecursively()
    }
}
'''

with tempfile.TemporaryDirectory(prefix="gostex-zip-resume-check-") as temporary:
    directory = Path(temporary)
    source = directory / "Check.kt"
    source.write_text(fixture)
    classes = directory / "classes"
    app = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
    subprocess.run([
        str(JAVA), "-cp", os.pathsep.join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(COMPILER[1]), "-d", str(classes),
        str(app / "TorrDownloadPaths.kt"), str(app / "GameZipArchive.kt"), str(source),
    ], check=True, timeout=45)
    subprocess.run([
        str(JAVA), "-cp", os.pathsep.join([str(classes), str(COMPILER[1])]), "tv.orbit.shield.CheckKt",
    ], check=True, timeout=20)
