package ly.com.tahaben.farhan_rescue.adb

import java.io.OutputStream

/**
 * The only door from the capture logic to the device. The Android implementation wraps a libadb connection; tests
 * wrap an in-memory filesystem. It exposes [AdbCommand]s, never strings, so no caller can run a command that is not
 * in that sealed list.
 */
interface RemoteShell {
    /** Runs [command] to its end of stream and returns its output as text. Throws [OutputTooLarge] past [maxBytes]. */
    fun text(command: AdbCommand, maxBytes: Int): String

    /**
     * Streams a copy command into [sink]. Returns the byte count. Throws [OutputTooLarge] as soon as more than
     * [maxBytes] arrive (the excess is never written).
     */
    fun copy(command: AdbCommand.CopyFile, sink: OutputStream, maxBytes: Long): Long

    /** Streams the output lines of [command] to [onLine] until it returns false or the stream ends. */
    fun lines(command: AdbCommand, maxBytes: Int, onLine: (String) -> Boolean)
}

class OutputTooLarge : RuntimeException("remote output exceeded its bound")
