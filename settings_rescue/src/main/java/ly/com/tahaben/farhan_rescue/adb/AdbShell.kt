package ly.com.tahaben.farhan_rescue.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [RemoteShell] over an open libadb connection.
 *
 * Two facts about libadb 3.1.1 shape this class [tagged source]:
 *  - `AdbInputStream.read()` (one byte) returns a signed byte, so binary data must use `read(byte[], int, int)`.
 *  - When the daemon closes a stream while the reader is waiting with nothing queued, `AdbStream.read` throws
 *    `IOException("Stream closed.")` instead of returning -1. Both are end of stream here. That makes a dropped
 *    connection look like a normal end, so completeness is never inferred from the stream: [CaptureEngine] checks
 *    each copy against `stat` length and digest.
 */
internal class AdbShell(private val manager: AbsAdbConnectionManager) : RemoteShell {
    override fun text(command: AdbCommand, maxBytes: Int): String {
        val collected = ByteArrayOutputStream()
        withInput(command) { input ->
            pump(input, maxBytes.toLong()) { chunk, count -> collected.write(chunk, 0, count) }
        }
        return String(collected.toByteArray(), Charsets.UTF_8)
    }

    override fun copy(command: AdbCommand.CopyFile, sink: OutputStream, maxBytes: Long): Long {
        var total = 0L
        withInput(command) { input ->
            pump(input, maxBytes) { chunk, count ->
                sink.write(chunk, 0, count)
                total += count
            }
        }
        sink.flush()
        return total
    }

    override fun lines(command: AdbCommand, maxBytes: Int, onLine: (String) -> Boolean) {
        val line = ByteArrayOutputStream()
        var overlong = false
        var wantMore = true
        withInput(command) { input ->
            try {
                pump(input, maxBytes.toLong()) { chunk, count ->
                    for (index in 0 until count) {
                        if (!wantMore) return@pump
                        val byte = chunk[index]
                        if (byte == NEWLINE) {
                            if (!overlong) wantMore = onLine(String(line.toByteArray(), Charsets.UTF_8))
                            line.reset(); overlong = false
                        } else if (line.size() < MAX_LINE_BYTES) line.write(byte.toInt())
                        else overlong = true
                    }
                    if (!wantMore) throw StopReading
                }
            } catch (stop: StopReadingSignal) {
            }
        }
    }

    private object StopReading : StopReadingSignal()
    private open class StopReadingSignal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private inline fun withInput(command: AdbCommand, block: (InputStream) -> Unit) {
        val stream: AdbStream = manager.openStream(command.destination())
        try {
            block(stream.openInputStream())
        } finally {
            try { stream.close() } catch (ignored: IOException) { }
        }
    }

    private inline fun pump(input: InputStream, maxBytes: Long, onChunk: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val count = try {
                input.read(buffer, 0, buffer.size)
            } catch (failure: IOException) {
                if (failure.message == STREAM_CLOSED) -1 else throw failure
            }
            if (count < 0) return
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw OutputTooLarge()
            onChunk(buffer, count)
        }
    }

    private companion object {
        const val STREAM_CLOSED = "Stream closed."
        const val CHUNK_BYTES = 32 * 1024
        const val MAX_LINE_BYTES = 8192
        const val NEWLINE = '\n'.code.toByte()
    }
}
