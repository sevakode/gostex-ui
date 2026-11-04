package dev.gostex.desktop.platform

import dev.gostex.core.LaunchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Runs exact argument vectors; a shell is never inserted between GOSTEX and the application. */
class ProcessLauncher {
    suspend fun launch(target: LaunchTarget.Process): Process {
        val request = target.copy(arguments = target.arguments.toList())
        return withContext(Dispatchers.IO) {
            val executable = Path.of(request.executable)
            require(executable.isAbsolute) { "Executable must be an absolute path" }
            require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                "Executable must be an existing executable file"
            }
            require(request.arguments.none { '\u0000' in it }) { "Arguments cannot contain NUL" }
            val directory = request.workingDirectory?.let { value ->
                Path.of(value).also {
                    require(it.isAbsolute && Files.isDirectory(it)) {
                        "Working directory must be an existing absolute directory"
                    }
                }
            }
            ProcessBuilder(listOf(executable.toString()) + request.arguments)
                .directory(directory?.toFile())
                // External apps may write indefinitely; no undrained pipe can stall them.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { it.outputStream.close() }
        }
    }

    /** Cancelling the caller interrupts this waiter, never the user's external application. */
    suspend fun awaitExit(process: Process): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }
}
