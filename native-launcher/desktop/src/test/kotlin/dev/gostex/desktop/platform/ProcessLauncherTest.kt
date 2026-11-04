package dev.gostex.desktop.platform

import dev.gostex.core.LaunchTarget
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessLauncherTest {
    private val launcher = ProcessLauncher()
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val classpath = listOf(ArgvFixture::class.java, Unit::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        .distinct().joinToString(File.pathSeparator)

    @Test
    fun `exact argv survives unicode quotes whitespace shell metacharacters and empty args`() = runBlocking {
        val directory = Files.createTempDirectory("gostex-argv-")
        try {
            val result = directory.resolve("result.bin")
            val arguments = listOf("", "two words", "кавычки \" aquí", "\nline\tbreak", ";touch injected", "$(whoami)", "`id`", "*.rom", "a\\b")
            val process = withTimeout(15_000) {
                launcher.launch(LaunchTarget.Process(java,
                    listOf("-cp", classpath, ArgvFixture::class.java.name, "argv", result.toString()) + arguments,
                    directory.toString()))
            }
            try {
                assertEquals(0, withTimeout(15_000) { launcher.awaitExit(process) })
                DataInputStream(Files.newInputStream(result)).use { stream ->
                    assertEquals(directory.toRealPath(), Path.of(stream.readUTF()).toRealPath())
                    assertEquals(arguments.size, stream.readInt())
                    assertEquals(arguments, List(arguments.size) { stream.readUTF() })
                }
                assertFalse(Files.exists(directory.resolve("injected")))
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid executable and directory fail before spawning`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("gostex-invalid-process-")
        try {
            assertFailsWith<IllegalArgumentException> { launcher.launch(LaunchTarget.Process("java")) }
            assertFailsWith<IllegalArgumentException> { launcher.launch(LaunchTarget.Process(directory.toString())) }
            assertFailsWith<IllegalArgumentException> { launcher.launch(LaunchTarget.Process(directory.resolve("missing").toString())) }
            val ordinaryFile = Files.writeString(directory.resolve("ordinary.txt"), "ordinary data")
            assertFailsWith<IllegalArgumentException> { launcher.launch(LaunchTarget.Process(ordinaryFile.toString())) }
            assertFailsWith<IllegalArgumentException> {
                launcher.launch(LaunchTarget.Process(java, workingDirectory = directory.resolve("missing").toString()))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancelling exit observation leaves external app alive`() = runBlocking {
        val process = launcher.launch(LaunchTarget.Process(java,
            listOf("-cp", classpath, ArgvFixture::class.java.name, "sleep")))
        try {
            val observer = async(start = CoroutineStart.UNDISPATCHED) { launcher.awaitExit(process) }
            withTimeout(5_000) { observer.cancelAndJoin() }
            assertTrue(process.isAlive)
        } finally {
            process.destroyForcibly().waitFor()
        }
    }
}

/** Real child JVM: large output also checks the launcher does not leave pipes undrained. */
object ArgvFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments[0] == "sleep") {
            Thread.sleep(30_000)
            return
        }
        val noise = "x".repeat(4096)
        repeat(100) {
            System.out.println(noise)
            System.err.println(noise)
        }
        DataOutputStream(Files.newOutputStream(Path.of(arguments[1]))).use { stream ->
            stream.writeUTF(Path.of("").toAbsolutePath().toString())
            stream.writeInt(arguments.size - 2)
            for (index in 2 until arguments.size) stream.writeUTF(arguments[index])
        }
    }
}
