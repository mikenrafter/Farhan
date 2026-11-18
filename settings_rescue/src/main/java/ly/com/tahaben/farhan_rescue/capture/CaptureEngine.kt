package ly.com.tahaben.farhan_rescue.capture

import ly.com.tahaben.farhan_rescue.adb.*
import ly.com.tahaben.farhan_rescue.input.SourceTarget
import ly.com.tahaben.farhan_rescue.storage.JobDirectory
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Why a bridge operation ended without a result. Reasons only: no path, value or output text. */
enum class FailureCode {
    BAD_REQUEST,
    BUSY,
    PAIR_FAILED,
    CONNECT_FAILED,
    PAIRING_REQUIRED,
    IDENTITY_FAILED,
    RUN_AS_NOT_DEBUGGABLE,
    RUN_AS_PACKAGE_NOT_FOUND,
    RUN_AS_USER_REJECTED,
    RUN_AS_UNAVAILABLE,
    RUN_AS_UNRECOGNIZED,
    UID_USER_MISMATCH,
    FORCE_STOP_FAILED,
    LISTING_FAILED,
    STAT_FAILED,
    NOTHING_TO_EXPORT,
    SIDECAR_WITHOUT_DATABASE,
    TOO_MANY_WIDGET_FILES,
    FILE_TOO_LARGE,
    CAPTURE_TOO_LARGE,
    OUTPUT_BUDGET_EXCEEDED,
    COPY_INCOMPLETE,
    DIGEST_MISMATCH,
    SOURCE_CHANGED,
    STORAGE_FAILED,
    CONVERSION_FAILED,
    TIMED_OUT,
    BRIDGE_DIED,
    CANCELLED,
    INTERNAL,
}

class CaptureException(val code: FailureCode) : RuntimeException(code.name)

enum class Integrity { SHA256, SIZE_ONLY }

enum class Stage { PROBING, READING_VERSION, LISTING, STOPPING_SOURCE, COPYING, VERIFYING }

data class Progress(val stage: Stage, val done: Int = 0, val total: Int = 0)

/** What a check found. Counts and flags only. */
data class CheckFacts(
    val uid: Long,
    val version: PackageVersion?,
    val plannedFileCount: Int,
    val hasDatabase: Boolean,
    val usedPreferencesBackup: Boolean,
)

data class CaptureFacts(
    val uid: Long,
    val version: PackageVersion?,
    /** Local paths (see [RemoteFiles]) of every file copied into the job directory. */
    val localPaths: List<String>,
    val usedPreferencesBackup: Boolean,
    val integrity: Integrity,
)

/**
 * The whole device-side sequence, over a [RemoteShell]. Pure JVM so the order and refusals are unit-tested with a
 * fake device.
 *
 * Invariants:
 *  - CLAIM(run-as-first): nothing is listed, stopped or copied until `run-as ... id -u` has returned a uid whose
 *    user part equals the requested user [CaptureEngineTest].
 *  - CLAIM(stop-once): the only write to the source is one `am force-stop`, and only in [export], after the probe
 *    and before any copy. [check] never stops anything [CaptureEngineTest].
 *  - CLAIM(absent-vs-failed): a directory is "absent" only when its parent's successful listing lacks it. A
 *    listing, stat or stop without the sentinel is a failure, never an empty result [CaptureEngineTest].
 *  - CLAIM(bounded-capture): sizes come from `stat`; each file is bounded by its role, widget files and the whole
 *    capture by aggregate caps, all checked before the first copy; each copy stops at its stat size; text outputs
 *    share one [OUTPUT_BUDGET_BYTES] budget [CaptureEngineTest].
 *  - CLAIM(source-stable): metadata before and after copying, and the file set before and after, must be equal;
 *    otherwise [FailureCode.SOURCE_CHANGED] and nothing is returned [CaptureEngineTest].
 *  - CLAIM(copy-verified): each copy has exactly its stat length and, when `sha256sum` answers, its digest.
 */
class CaptureEngine(
    private val shell: RemoteShell,
    private val reportProgress: (Progress) -> Unit = {},
    private val throwIfCancelled: () -> Unit = {},
) {
    private var outputBudget = OUTPUT_BUDGET_BYTES

    fun check(target: SourceTarget): CheckFacts {
        val uid = probe(target)
        val version = readVersion(target)
        val plan = planned(target)
        return CheckFacts(
            uid, version, plan.files.size,
            plan.files.any { it.role == FileRole.DATABASE }, plan.usedPreferencesBackup,
        )
    }

    fun export(target: SourceTarget, job: JobDirectory): CaptureFacts {
        val uid = probe(target)
        val version = readVersion(target)
        stopSource(target)
        val plan = planned(target)
        val before = statAll(target, plan.files)
        enforceAggregateBounds(plan.files, before)
        var allHashed = true
        plan.files.forEachIndexed { index, file ->
            throwIfCancelled()
            reportProgress(Progress(Stage.COPYING, index, plan.files.size))
            if (!copyOne(target, file, before.getValue(file), job)) allHashed = false
        }
        reportProgress(Progress(Stage.VERIFYING, plan.files.size, plan.files.size))
        val after = statAll(target, plan.files)
        if (after != before) throw CaptureException(FailureCode.SOURCE_CHANGED)
        val replan = planned(target)
        if (replan.files.map { it.remotePath } != plan.files.map { it.remotePath }) throw CaptureException(FailureCode.SOURCE_CHANGED)
        return CaptureFacts(uid, version, plan.files.map { it.localPath }, plan.usedPreferencesBackup,
            if (allHashed) Integrity.SHA256 else Integrity.SIZE_ONLY)
    }

    private fun probe(target: SourceTarget): Long {
        throwIfCancelled()
        reportProgress(Progress(Stage.PROBING))
        return when (val probe = AdbOutput.parseUidProbe(text(AdbCommand.ProbeUid(target), 4096))) {
            is UidProbe.Uid -> {
                if (!AdbOutput.uidMatchesUser(probe.uid, target.user.value)) throw CaptureException(FailureCode.UID_USER_MISMATCH)
                probe.uid
            }
            is UidProbe.Failed -> throw CaptureException(when (probe.reason) {
                RunAsFailure.NOT_DEBUGGABLE -> FailureCode.RUN_AS_NOT_DEBUGGABLE
                RunAsFailure.PACKAGE_NOT_FOUND, RunAsFailure.NOT_AN_APPLICATION -> FailureCode.RUN_AS_PACKAGE_NOT_FOUND
                RunAsFailure.USER_REJECTED -> FailureCode.RUN_AS_USER_REJECTED
                RunAsFailure.UNAVAILABLE -> FailureCode.RUN_AS_UNAVAILABLE
                RunAsFailure.UNRECOGNIZED -> FailureCode.RUN_AS_UNRECOGNIZED
            })
        }
    }

    /** Informational only: a dump that does not answer yields null, and the file says the version is unknown. */
    private fun readVersion(target: SourceTarget): PackageVersion? {
        throwIfCancelled()
        reportProgress(Progress(Stage.READING_VERSION))
        val interesting = ArrayList<String>()
        try {
            shell.lines(AdbCommand.ReadVersion(target.packageName), minOf(outputBudget, VERSION_DUMP_BYTES)) { line ->
                spend(line.length + 1)
                val trimmed = line.trim()
                if (trimmed.startsWith("versionCode=") || trimmed.startsWith("versionName=")) interesting += trimmed
                AdbOutput.parseVersion(interesting.asSequence()) == null && interesting.size < 16
            }
        } catch (failure: OutputTooLarge) {
        } catch (failure: IOException) {
        }
        return AdbOutput.parseVersion(interesting.asSequence())
    }

    private fun stopSource(target: SourceTarget) {
        throwIfCancelled()
        reportProgress(Progress(Stage.STOPPING_SOURCE))
        if (AdbOutput.linesBeforeSentinel(text(AdbCommand.ForceStop(target), 4096)) == null) {
            throw CaptureException(FailureCode.FORCE_STOP_FAILED)
        }
    }

    private fun planned(target: SourceTarget): CapturePlan.Planned {
        throwIfCancelled()
        reportProgress(Progress(Stage.LISTING))
        return when (val plan = CapturePlanner.plan(listings(target))) {
            is CapturePlan.Planned -> plan
            is CapturePlan.Refused -> throw CaptureException(when (plan.problem) {
                PlanProblem.NOTHING_TO_EXPORT -> FailureCode.NOTHING_TO_EXPORT
                PlanProblem.SIDECAR_WITHOUT_DATABASE -> FailureCode.SIDECAR_WITHOUT_DATABASE
                PlanProblem.TOO_MANY_WIDGET_FILES -> FailureCode.TOO_MANY_WIDGET_FILES
            })
        }
    }

    private fun listings(target: SourceTarget): Map<RemoteDirectory, List<String>> {
        val root = list(target, RemoteDirectory.ROOT).toSet()
        val filesDirectory = if ("files" in root) list(target, RemoteDirectory.FILES).toSet() else emptySet()
        return mapOf(
            RemoteDirectory.SHARED_PREFS to listIfPresent(target, RemoteDirectory.SHARED_PREFS, "shared_prefs" in root),
            RemoteDirectory.DATA_STORE to listIfPresent(target, RemoteDirectory.DATA_STORE, "datastore" in filesDirectory),
            RemoteDirectory.DATABASES to listIfPresent(target, RemoteDirectory.DATABASES, "databases" in root),
        )
    }

    private fun listIfPresent(target: SourceTarget, directory: RemoteDirectory, present: Boolean) =
        if (present) list(target, directory) else emptyList()

    private fun list(target: SourceTarget, directory: RemoteDirectory): List<String> =
        AdbOutput.linesBeforeSentinel(text(AdbCommand.ListDirectory(target, directory), LISTING_BYTES))
            ?: throw CaptureException(FailureCode.LISTING_FAILED)

    private fun statAll(target: SourceTarget, files: List<RemoteFile>): Map<RemoteFile, FileFingerprint> {
        throwIfCancelled()
        return files.associateWith { file ->
            AdbOutput.parseStat(text(AdbCommand.StatFile(target, file), STAT_BYTES)) ?: throw CaptureException(FailureCode.STAT_FAILED)
        }
    }

    private fun enforceAggregateBounds(files: List<RemoteFile>, sizes: Map<RemoteFile, FileFingerprint>) {
        var total = 0L
        var widgets = 0L
        for (file in files) {
            val size = sizes.getValue(file).sizeBytes
            if (size > file.role.maxBytes) throw CaptureException(FailureCode.FILE_TOO_LARGE)
            total += size
            if (file.role == FileRole.WIDGET_STATE) widgets += size
        }
        if (widgets > RemoteFiles.MAX_WIDGET_TOTAL_BYTES || total > RemoteFiles.MAX_TOTAL_BYTES) {
            throw CaptureException(FailureCode.CAPTURE_TOO_LARGE)
        }
    }

    /** Returns true when the digest was verified, false when the device had no usable `sha256sum`. */
    private fun copyOne(target: SourceTarget, file: RemoteFile, fingerprint: FileFingerprint, job: JobDirectory): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = try {
            job.sink(file.localPath).use { sink ->
                shell.copy(AdbCommand.CopyFile(target, file), DigestOutputStream(sink as OutputStream, digest), fingerprint.sizeBytes)
            }
        } catch (failure: OutputTooLarge) {
            throw CaptureException(FailureCode.SOURCE_CHANGED)
        } catch (failure: IOException) {
            throw CaptureException(FailureCode.STORAGE_FAILED)
        }
        if (copied != fingerprint.sizeBytes) throw CaptureException(FailureCode.COPY_INCOMPLETE)
        val remoteDigest = AdbOutput.parseSha256(text(AdbCommand.HashFile(target, file), STAT_BYTES)) ?: return false
        val localDigest = digest.digest().joinToString("") { "%02x".format(it) }
        if (remoteDigest != localDigest) throw CaptureException(FailureCode.DIGEST_MISMATCH)
        return true
    }

    private fun text(command: AdbCommand, maxBytes: Int): String {
        val output = try {
            shell.text(command, minOf(maxBytes, outputBudget))
        } catch (failure: OutputTooLarge) {
            throw CaptureException(FailureCode.OUTPUT_BUDGET_EXCEEDED)
        }
        spend(output.length)
        return output
    }

    private fun spend(bytes: Int) {
        outputBudget -= bytes
        if (outputBudget < 0) throw CaptureException(FailureCode.OUTPUT_BUDGET_EXCEEDED)
    }

    companion object {
        const val OUTPUT_BUDGET_BYTES = 16 shl 20
        const val VERSION_DUMP_BYTES = 8 shl 20
        const val LISTING_BYTES = 64 shl 10
        const val STAT_BYTES = 4 shl 10
    }
}
