package ly.com.tahaben.privileged_access_data.shizuku

import ly.com.tahaben.privileged_access_domain.ShellCommand
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs one argument vector as a child process and returns its exit code and (merged) output. It runs in the process
 * Shizuku starts, as the shell user. It never goes through `sh -c`, and it refuses any vector that is not a
 * connector command, so a caller that is not Farhan's own code cannot widen what runs.
 */
class ExecRunner(
    private val allowed: (List<String>) -> Boolean = ShellCommand::isAllowed,
    private val timeoutMillis: Long = 30_000,
    private val maxOutputBytes: Int = 8 * 1024,
) {
    data class Outcome(val exitCode: Int, val output: String)

    fun run(args: List<String>): Outcome {
        if (!allowed(args)) return Outcome(EXIT_REFUSED, "refused")
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (failure: IOException) {
            return Outcome(EXIT_NOT_STARTED, "")
        }
        process.outputStream.close()
        val watchdog = Thread {
            try { Thread.sleep(timeoutMillis); process.destroy() } catch (ignored: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        val output = ByteArrayOutputStream()
        try {
            process.inputStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val room = maxOutputBytes - output.size()
                    if (room > 0) output.write(buffer, 0, minOf(count, room))
                }
            }
        } catch (failure: IOException) {
            // The watchdog closed the stream.
        }
        val exit = try { process.waitFor() } catch (interrupted: InterruptedException) { process.destroy(); EXIT_TIMED_OUT }
        watchdog.interrupt()
        return Outcome(exit, String(output.toByteArray(), Charsets.UTF_8))
    }

    companion object {
        const val EXIT_REFUSED = -2
        const val EXIT_NOT_STARTED = 127
        const val EXIT_TIMED_OUT = -3
    }
}
