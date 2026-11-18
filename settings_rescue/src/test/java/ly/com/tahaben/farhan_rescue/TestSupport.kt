package ly.com.tahaben.farhan_rescue

import ly.com.tahaben.farhan_rescue.adb.*
import ly.com.tahaben.farhan_rescue.input.*
import java.io.OutputStream
import java.security.MessageDigest

fun target(user: Int = 0, packageName: String = PackageName.DEFAULT) =
    SourceTarget(PackageName.parse(packageName).orNull()!!, AndroidUserId.parse(user.toString()).orNull()!!)

fun file(directory: RemoteDirectory, name: String): RemoteFile = RemoteFiles.classify(directory, name)!!

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * An in-memory phone. `files` maps data-directory-relative paths to bytes. Every command is logged so tests can
 * assert order and that no unexpected command ran. The answers copy the shapes of real `run-as`, toybox `ls`,
 * `stat -c`, `sha256sum` and `am force-stop` output with the helper's sentinel (see AdbCommand).
 */
class FakeDevice(private val uid: Long = 10234, var debuggable: Boolean = true) : RemoteShell {
    val files = linkedMapOf<String, ByteArray>()
    val log = mutableListOf<String>()
    val sizeOverride = mutableMapOf<String, Long>()
    val modifiedOffset = mutableMapOf<String, Long>()
    var hashAvailable = true
    var stopFails = false
    var failListingOf: String? = null
    var versionLines = listOf("    versionCode=11 minSdk=24 targetSdk=35", "    versionName=0.2.6-alpha")
    var beforeCopy: (String) -> Unit = {}
    var afterCopy: (String) -> Unit = {}
    var copyTransform: (String, ByteArray) -> ByteArray = { _, bytes -> bytes }

    private val ok = AdbCommand.SENTINEL + "\n"

    override fun text(command: AdbCommand, maxBytes: Int): String {
        log += command.destination()
        val output = when (command) {
            is AdbCommand.ProbeUid -> if (debuggable) "$uid\n" else "run-as: package not debuggable: ${command.target.packageName.value}\n"
            is AdbCommand.ForceStop -> if (stopFails) "Error: failed\n" else ok
            is AdbCommand.ListDirectory -> list(command.directory.path)
            is AdbCommand.StatFile -> files[command.file.remotePath]?.let { bytes ->
                val key = command.file.remotePath
                val size = sizeOverride[key] ?: bytes.size.toLong()
                "$size ${1_700_000_000L + (modifiedOffset[key] ?: 0L)} 1700000001 ${key.hashCode().toLong() and 0xffff} regular file\n$ok"
            } ?: ""
            is AdbCommand.HashFile -> files[command.file.remotePath]?.takeIf { hashAvailable }
                ?.let { "${sha256Hex(it)}  ${command.file.remotePath}\n$ok" } ?: ""
            is AdbCommand.ReadVersion, is AdbCommand.CopyFile -> error("not a text command")
        }
        if (output.length > maxBytes) throw OutputTooLarge()
        return output
    }

    private fun list(directory: String): String {
        if (directory == failListingOf) return ""
        val prefix = if (directory == ".") "" else "$directory/"
        val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        if (names.isEmpty() && directory != ".") return ""
        return names.joinToString("") { "$it\n" } + ok
    }

    override fun copy(command: AdbCommand.CopyFile, sink: OutputStream, maxBytes: Long): Long {
        log += command.destination()
        val key = command.file.remotePath
        beforeCopy(key)
        val bytes = copyTransform(key, files.getValue(key))
        if (bytes.size > maxBytes) throw OutputTooLarge()
        sink.write(bytes)
        afterCopy(key)
        return bytes.size.toLong()
    }

    override fun lines(command: AdbCommand, maxBytes: Int, onLine: (String) -> Boolean) {
        log += command.destination()
        for (line in versionLines) if (!onLine(line)) return
    }
}
