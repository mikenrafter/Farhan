package ly.com.tahaben.farhan_rescue.adb

import ly.com.tahaben.farhan_rescue.input.PackageName
import ly.com.tahaben.farhan_rescue.input.SourceTarget

/**
 * Every ADB service string the helper can ever open. There is no constructor taking free text: each case is built
 * from validated types, so the final `exec:` string has no shell metacharacter other than the fixed ones below.
 *
 * Why `exec:` and not `shell:`: `exec:` is a raw, non-PTY stream, so binary output keeps its newlines. It has no
 * exit status, and stderr shares the stream. Therefore:
 *  - CLAIM(sentinel): every command whose success matters ends with `&& echo [SENTINEL]`; success is the sentinel on
 *    the last line, never an empty output. The binary copy cannot carry a sentinel, so it is checked by length
 *    (from `stat`) and digest instead [AdbCommandTest, CaptureEngineTest].
 *  - CLAIM(stderr-split): binary copies and sentinel commands send stderr to /dev/null so an error message cannot
 *    be mistaken for file bytes. Only [ProbeUid] keeps stderr, because it needs the `run-as` error text.
 *
 * Syntax: `run-as <package> [--user <id>] <command...>` [AOSP run-as.cpp: "usage: run-as <package-name> [--user <uid>]"].
 * `run-as` changes into the package's data directory, so file arguments are relative.
 */
sealed interface AdbCommand {
    fun destination(): String

    /** `run-as ... id -u`: proves the package is debuggable and installed for the user, and yields its uid. */
    data class ProbeUid(val target: SourceTarget) : AdbCommand {
        override fun destination() = "exec:${runAs(target)} id -u"
    }

    /** Read-only `dumpsys package`: version name and code for the file's source block. */
    data class ReadVersion(val packageName: PackageName) : AdbCommand {
        override fun destination() = "exec:dumpsys package ${packageName.value}"
    }

    /** The one write to the source: `am force-stop`. Sentinel on success. */
    data class ForceStop(val target: SourceTarget) : AdbCommand {
        override fun destination() = "exec:am force-stop --user ${target.user.value} ${target.packageName.value} && echo $SENTINEL"
    }

    data class ListDirectory(val target: SourceTarget, val directory: RemoteDirectory) : AdbCommand {
        override fun destination() = "exec:${runAs(target)} ls -1 ${directory.path} 2>/dev/null && echo $SENTINEL"
    }

    data class StatFile(val target: SourceTarget, val file: RemoteFile) : AdbCommand {
        override fun destination() = "exec:${runAs(target)} stat -c '$STAT_FORMAT' ${file.remotePath} 2>/dev/null && echo $SENTINEL"
    }

    data class HashFile(val target: SourceTarget, val file: RemoteFile) : AdbCommand {
        override fun destination() = "exec:${runAs(target)} sha256sum ${file.remotePath} 2>/dev/null && echo $SENTINEL"
    }

    /** Raw bytes of one allowlisted file. Binary: no sentinel. */
    data class CopyFile(val target: SourceTarget, val file: RemoteFile) : AdbCommand {
        override fun destination() = "exec:${runAs(target)} cat ${file.remotePath} 2>/dev/null"
    }

    companion object {
        const val SENTINEL = "farhan-rescue-ok"
        /** size, mtime seconds, ctime seconds, inode, file type (last: it contains spaces). */
        const val STAT_FORMAT = "%s %Y %Z %i %F"

        private fun runAs(target: SourceTarget) = "run-as ${target.packageName.value} --user ${target.user.value}"
    }
}

/** What `run-as` said, reduced to a reason. The raw text is not kept. */
enum class RunAsFailure {
    NOT_DEBUGGABLE,
    PACKAGE_NOT_FOUND,
    NOT_AN_APPLICATION,
    USER_REJECTED,
    UNAVAILABLE,
    UNRECOGNIZED,
}

sealed interface UidProbe {
    data class Uid(val uid: Long) : UidProbe
    data class Failed(val reason: RunAsFailure) : UidProbe
}

data class FileFingerprint(
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val changedSeconds: Long,
    val inode: Long,
)

data class PackageVersion(val name: String, val code: Long)

/**
 * Reads command output. Nothing here throws on hostile text: a line that does not fit the expected shape is
 * "absent", and the caller treats absence as failure.
 */
object AdbOutput {
    private val SAFE_VERSION_NAME = Regex("[A-Za-z0-9._+\\-]{1,64}")

    /**
     * Strings from AOSP run-as.cpp (main): "unknown package: %s", "package not debuggable: %s",
     * "package not an application: %s", "user id too big: %d", "negative user id: %d".
     */
    fun parseUidProbe(text: String): UidProbe {
        val trimmed = text.trim()
        if (Regex("[0-9]{1,10}").matches(trimmed)) return UidProbe.Uid(trimmed.toLong())
        val reason = when {
            "not debuggable" in trimmed -> RunAsFailure.NOT_DEBUGGABLE
            "unknown package" in trimmed -> RunAsFailure.PACKAGE_NOT_FOUND
            "not an application" in trimmed -> RunAsFailure.NOT_AN_APPLICATION
            "user id" in trimmed -> RunAsFailure.USER_REJECTED
            "only 'shell' or 'root'" in trimmed || "disabled from the kernel" in trimmed -> RunAsFailure.UNAVAILABLE
            else -> RunAsFailure.UNRECOGNIZED
        }
        return UidProbe.Failed(reason)
    }

    /** Android uid = userId * 100000 + appId, appId 10000..19999 for normal apps. */
    fun uidMatchesUser(uid: Long, user: Int): Boolean = uid / 100_000 == user.toLong() && (uid % 100_000) in 10_000..19_999

    /**
     * Lines before a final [AdbCommand.SENTINEL] line, or null when the sentinel is absent (the command failed).
     * CLAIM(sentinel): an empty list with the sentinel is a real empty result; no sentinel is a failure.
     */
    fun linesBeforeSentinel(text: String): List<String>? {
        val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        if (lines.isEmpty() || lines.last() != AdbCommand.SENTINEL) return null
        return lines.dropLast(1)
    }

    fun parseStat(text: String): FileFingerprint? {
        val lines = linesBeforeSentinel(text) ?: return null
        val line = lines.singleOrNull() ?: return null
        val parts = line.split(' ', limit = 5)
        if (parts.size != 5 || !parts[4].startsWith("regular")) return null
        val numbers = parts.take(4).map { if (Regex("[0-9]{1,19}").matches(it)) it.toLongOrNull() else null }
        if (numbers.any { it == null }) return null
        return FileFingerprint(numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!)
    }

    /** Lowercase hex digest from `sha256sum` output, or null if the tool failed or answered in another shape. */
    fun parseSha256(text: String): String? {
        val line = linesBeforeSentinel(text)?.singleOrNull() ?: return null
        val digest = line.substringBefore(' ')
        return digest.takeIf { Regex("[0-9a-f]{64}").matches(it) }
    }

    /**
     * Version from `dumpsys package`: first `versionCode=<digits>` and first `versionName=<safe text>`. Null parts mean
     * the dump did not say; the file then records "unknown" and 0 and the preview says so.
     */
    fun parseVersion(lines: Sequence<String>): PackageVersion? {
        var name: String? = null
        var code: Long? = null
        for (raw in lines) {
            val line = raw.trim()
            if (code == null && line.startsWith("versionCode=")) {
                code = Regex("[0-9]{1,18}").find(line.removePrefix("versionCode="))?.value?.toLongOrNull()
            }
            if (name == null && line.startsWith("versionName=")) {
                name = line.removePrefix("versionName=").takeIf { SAFE_VERSION_NAME.matches(it) }
            }
            if (name != null && code != null) return PackageVersion(name, code)
        }
        return null
    }
}
