package dev.gostex.release

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticLogTest {
    @Test
    fun `events and metadata redact credentials urls identities and private paths`() = inDirectory { root ->
        val logs = File(root, "logs")
        val log = DiagnosticLog(logs)
        val secrets = listOf(
            "https://url-user:url-pass@example.test/private-rom?token=query-secret#fragment-secret",
            "/home/private-owner/ROM collection/private-game.rom",
            "C:\\Users\\windows-owner\\private-game.rom",
            "~/private-save",
            "password=pass-secret",
            "\"access_token\":\"access-secret\"",
            "client-secret='client secret value'",
            "--token cli-secret",
            "Authorization: Bearer bearer-secret",
            "Cookie: session=cookie-secret; private=second-secret",
            "Basic YmFzaWMtc2VjcmV0",
            "username=identity-secret",
            "mail-secret@example.test",
            "github_pat_opaqueSecret1234567890",
            "-----BEGIN PRIVATE KEY-----\nprivate-key-secret\n-----END PRIVATE KEY-----"
        )
        secrets.forEach { log.event("test", it) }
        val destination = File(root, "report.zip")
        log.export(destination, linkedMapOf(
            "version" to "0.33.0",
            "password" to "metadata-secret",
            "location" to "https://host.test/private-meta?secret=meta-query"
        ))
        val exported = readZip(destination).values.joinToString("\n")
        val stored = logs.listFiles().orEmpty().joinToString("\n") { it.readText() }
        val hidden = listOf("url-user", "url-pass", "example.test", "private-rom", "query-secret",
            "fragment-secret", "private-owner", "private-game", "windows-owner", "private-save",
            "pass-secret", "access-secret", "client secret value", "cli-secret", "bearer-secret",
            "cookie-secret", "second-secret", "YmFzaWMtc2VjcmV0", "identity-secret", "mail-secret",
            "opaqueSecret", "private-key-secret", "metadata-secret", "private-meta", "meta-query")
        hidden.forEach { secret ->
            assertFalse(stored.contains(secret), "Stored sensitive text: $secret")
            assertFalse(exported.contains(secret), "Exported sensitive text: $secret")
        }
        assertTrue(exported.contains("version=0.33.0"))
        assertTrue(stored.contains("[url]"))
        assertTrue(stored.contains("[path]"))
        assertTrue(stored.lines().filter { it.isNotEmpty() }.all {
            it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\\t.*"))
        })
    }

    @Test
    fun `events remain utf8 bounded and rotation retains only newest four files`() = inDirectory { root ->
        val logs = File(root, "logs")
        val log = DiagnosticLog(logs)
        log.event("old-marker", "界".repeat(20_000))
        repeat(120) { log.event("entry-$it", "界".repeat(3000)) }
        val files = logs.listFiles().orEmpty()
        assertEquals(setOf("events.log", "events.1.log", "events.2.log", "events.3.log"), files.map { it.name }.toSet())
        assertTrue(files.all { it.length() <= 128 * 1024 })
        assertTrue(files.sumOf { it.length() } <= 4 * 128 * 1024)
        val all = files.joinToString("\n") { it.readText() }
        assertFalse(all.contains("old-marker"))
        assertTrue(all.contains("entry-119"))
        assertFalse(all.contains('\uFFFD'))
        files.forEach { file ->
            file.readText().lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                assertTrue(line.toByteArray(Charsets.UTF_8).size + 1 <= 8 * 1024)
            }
        }
    }

    @Test
    fun `disabled logging stops writes but explicit export and clear still work`() = inDirectory { root ->
        val logs = File(root, "logs")
        val log = DiagnosticLog(logs)
        log.enabled = false
        log.event("disabled")
        log.failure("disabled", IOException("should-not-exist"))
        assertFalse(logs.exists())
        log.enabled = true
        log.event("before-disable")
        val before = File(logs, "events.log").readBytes()
        log.enabled = false
        log.event("after-disable")
        log.failure("after-disable", IOException("should-not-exist"))
        assertTrue(before.contentEquals(File(logs, "events.log").readBytes()))
        val unrelated = File(logs, "preferences.json").apply { writeText("unrelated-private-setting") }
        val destination = File(root, "disabled.zip")
        log.export(destination, mapOf("version" to "test"))
        val zip = readZip(destination)
        assertEquals(setOf("metadata.txt", "events.log", "threads.txt"), zip.keys)
        assertFalse(zip.values.joinToString().contains("unrelated-private-setting"))
        assertTrue(zip.getValue("events.log").contains("before-disable"))
        log.clear()
        assertEquals(listOf("preferences.json"), logs.listFiles().orEmpty().map { it.name })
        assertEquals("unrelated-private-setting", unrelated.readText())
    }

    @Test
    fun `failure includes bounded redacted throwable frames and causes`() = inDirectory { root ->
        val logs = File(root, "logs")
        val cause = IOException("password=failure-secret")
        val error = IllegalStateException("failed at /Users/exception-owner/private-save", cause).apply {
            stackTrace = Array(2000) { StackTraceElement("Operation", "run", "Operation.kt", it) }
        }
        DiagnosticLog(logs).failure("update.install", error)
        val stored = File(logs, "events.log").readText()
        assertTrue(stored.contains("update.install"))
        assertTrue(stored.contains("java.lang.IllegalStateException"))
        assertTrue(stored.contains("Operation.run(Operation.kt:0)"))
        assertTrue(stored.contains("java.io.IOException"))
        assertFalse(stored.contains("exception-owner"))
        assertFalse(stored.contains("failure-secret"))
        assertFalse(stored.contains("Operation.kt:16"))
        assertTrue(stored.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
    }

    @Test
    fun `export is bounded sanitizes existing logs and ignores unrelated files`() = inDirectory { root ->
        val logs = File(root, "logs").apply { mkdirs() }
        File(logs, "events.log").writeText("password=injected-secret\n" + "x".repeat(300_000))
        File(logs, "rom.sav").writeText("save-file-secret")
        File(logs, "settings.properties").writeText("preferences-secret")
        val metadata = (0..2000).associate { "field-$it" to "z".repeat(10_000) }
        val destination = File(root, "bounded.zip")
        DiagnosticLog(logs).export(destination, metadata)
        assertTrue(destination.length() <= 2 * 1024 * 1024)
        val entries = readZip(destination)
        assertEquals(setOf("metadata.txt", "events.log", "threads.txt"), entries.keys)
        assertTrue(entries.getValue("metadata.txt").toByteArray().size <= 64 * 1024)
        assertTrue(entries.getValue("events.log").toByteArray().size <= 4 * 128 * 1024)
        assertFalse(entries.values.joinToString().contains("injected-secret"))
        assertFalse(entries.values.joinToString().contains("save-file-secret"))
        assertFalse(entries.values.joinToString().contains("preferences-secret"))
        assertFalse(entries.keys.any { it.contains("..") || it.startsWith('/') })
    }

    @Test
    fun `explicit export includes bounded thread ids states and frames without thread names`() = inDirectory { root ->
        val thread = Thread.currentThread()
        val originalName = thread.name
        val privateName = "private-thread-owner-token-name"
        val logs = File(root, "logs")
        val log = DiagnosticLog(logs).apply { enabled = false }
        try {
            thread.name = privateName
            val destination = File(root, "threads.zip")
            log.export(destination, emptyMap())
            val entries = readZip(destination)
            val snapshot = entries.getValue("threads.txt")
            assertFalse(snapshot.contains(privateName))
            assertTrue(snapshot.contains("thread id=${thread.id} state="))
            assertTrue(snapshot.toByteArray().size <= 64 * 1024)
            val sections = snapshot.split(Regex("(?m)(?=^thread id=)" )).filter { it.isNotBlank() }
            assertTrue(sections.size in 1..32)
            sections.forEach { section ->
                assertTrue(section.lineSequence().first().matches(Regex("thread id=\\d+ state=[A-Z_]+")))
                assertTrue(section.lineSequence().count { it.startsWith(" at ") } <= 16)
            }
            assertFalse(logs.exists())
            assertTrue(destination.length() <= 2 * 1024 * 1024)
        } finally {
            thread.name = originalName
        }
    }

    @Test
    fun `managed symlinks cannot read or modify unrelated files and clear removes only link`() = inDirectory { root ->
        val logs = File(root, "logs").apply { mkdirs() }
        val unrelated = File(root, "private.txt").apply { writeText("symlink-private-secret") }
        val link = File(logs, "events.log")
        Files.createSymbolicLink(link.toPath(), unrelated.toPath())
        val log = DiagnosticLog(logs)
        log.event("must-not-append")
        assertEquals("symlink-private-secret", unrelated.readText())
        val destination = File(root, "link.zip")
        assertFailsWith<IOException> { log.export(destination, emptyMap()) }
        assertFalse(destination.exists())
        log.clear()
        assertFalse(link.exists())
        assertEquals("symlink-private-secret", unrelated.readText())
    }

    @Test
    fun `explicit IO failures are visible and an export cannot overwrite managed logs`() = inDirectory { root ->
        val logs = File(root, "logs")
        val log = DiagnosticLog(logs)
        log.event("retained")
        val managed = File(logs, "events.log")
        val before = managed.readText()
        assertFailsWith<IOException> { log.export(managed, emptyMap()) }
        assertEquals(before, managed.readText())
        val notDirectory = File(root, "not-directory").apply { writeText("keep") }
        assertFailsWith<IOException> { log.export(File(notDirectory, "report.zip"), emptyMap()) }
        assertEquals("keep", notDirectory.readText())
        assertEquals(setOf("events.log"), logs.listFiles().orEmpty().map { it.name }.toSet())
    }

    private fun readZip(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun inDirectory(block: (File) -> Unit) {
        val file = File.createTempFile("gostex-diagnostics-", ".tmp")
        check(file.delete() && file.mkdir())
        try { block(file) } finally { file.deleteRecursively() }
    }
}
