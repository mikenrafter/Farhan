package ly.com.tahaben.farhan_rescue.storage

import ly.com.tahaben.farhan_rescue.adb.RemoteFiles
import ly.com.tahaben.farhan_rescue.input.JobId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write-temp, sync, rename. A reader sees the old file or the new file, never a half-written one. JVM-only so the
 * unit tests exercise the real code. CLAIM(atomic-replace) [PrivateFilesTest].
 */
object AtomicFiles {
    fun write(target: File, bytes: ByteArray) {
        val directory = requireNotNull(target.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "cannot create directory" }
        val temporary = File(directory, target.name + ".tmp")
        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Null when absent. Throws if larger than [maxBytes]: a bounded read, never a silent truncation. */
    fun read(target: File, maxBytes: Int): ByteArray? {
        if (!target.isFile) return null
        check(target.length() <= maxBytes) { "private file exceeds its bound" }
        return target.readBytes()
    }

    fun delete(target: File) {
        target.delete()
        File(target.absoluteFile.parentFile, target.name + ".tmp").delete()
    }
}

/**
 * The raw copies of the old app's files. Everything here is deleted on success, error, cancel and the next start.
 *
 * CLAIM(raw-contained): a job file can only be opened through [JobDirectory.sink], which accepts the fixed allowlisted
 * local paths, so no remote name can place a file anywhere else [PrivateFilesTest].
 */
class RawJobStore(private val root: File) {
    fun create(jobId: JobId): JobDirectory {
        val directory = File(root, jobId.value)
        check(!directory.exists()) { "job directory already exists" }
        check(directory.mkdirs()) { "cannot create job directory" }
        return JobDirectory(directory)
    }

    fun open(jobId: JobId): JobDirectory? = File(root, jobId.value).takeIf { it.isDirectory }?.let(::JobDirectory)

    fun delete(jobId: JobId) {
        File(root, jobId.value).deleteRecursively()
    }

    /** Next-startup sweep and every cancel: removes every job, finished or not. */
    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun isEmpty(): Boolean = root.listFiles().isNullOrEmpty()
}

class JobDirectory internal constructor(val directory: File) {
    /** Opens a new file for [localPath]. Throws if the path is not an allowlisted local path. */
    fun sink(localPath: String): OutputStream {
        requireNotNull(RemoteFiles.classifyLocal(localPath)) { "path is not allowlisted" }
        val target = File(directory, localPath)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "cannot create directory" }
        check(!target.exists()) { "file already exists" }
        return FileOutputStream(target)
    }

    /** The file for an allowlisted path, or null if absent or not allowlisted. */
    fun file(localPath: String): File? {
        if (RemoteFiles.classifyLocal(localPath) == null) return null
        return File(directory, localPath).takeIf { it.isFile }
    }
}

/** The converted, validated portable file waiting to be saved. Not raw data: it survives a cancelled save. */
class PendingExportStore(private val file: File) {
    fun save(bytes: ByteArray) = AtomicFiles.write(file, bytes)
    fun load(maxBytes: Int = 1 shl 20): ByteArray? = AtomicFiles.read(file, maxBytes)
    fun clear() = AtomicFiles.delete(file)
}

/**
 * The throwaway ADB client identity (RSA key + self-signed certificate), in `no_backup` storage only.
 * CLAIM(short-lived-identity): [load] refuses an identity older than [MAX_AGE_MILLIS] and erases it; erase on
 * completed capture, reset and cancel is the caller's job [PrivateFilesTest].
 */
class IdentityStore(private val file: File) {
    class Identity(val privateKeyPkcs8: ByteArray, val certificateDer: ByteArray, val createdAtMillis: Long)

    fun save(identity: Identity) {
        val bytes = java.io.ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(identity.createdAtMillis)
                out.writeInt(identity.privateKeyPkcs8.size); out.write(identity.privateKeyPkcs8)
                out.writeInt(identity.certificateDer.size); out.write(identity.certificateDer)
            }
        }.toByteArray()
        AtomicFiles.write(file, bytes)
    }

    /** Null when absent, expired or unreadable. An expired or unreadable file is erased. */
    fun load(nowMillis: Long): Identity? {
        val bytes = try { AtomicFiles.read(file, MAX_FILE_BYTES) } catch (failure: IllegalStateException) { erase(); return null }
            ?: return null
        return try {
            DataInputStream(bytes.inputStream()).use { input ->
                check(input.readInt() == MAGIC)
                val created = input.readLong()
                val key = ByteArray(input.readInt().also { check(it in 1..MAX_PART_BYTES) }).also(input::readFully)
                val certificate = ByteArray(input.readInt().also { check(it in 1..MAX_PART_BYTES) }).also(input::readFully)
                check(input.available() == 0)
                Identity(key, certificate, created).takeIf { nowMillis - created in 0..MAX_AGE_MILLIS }
            } ?: run { erase(); null }
        } catch (failure: Exception) {
            erase(); null
        }
    }

    fun erase() = AtomicFiles.delete(file)

    companion object {
        const val MAX_AGE_MILLIS = 30L * 60 * 1000
        private const val MAGIC = 0x46524944
        private const val MAX_PART_BYTES = 8192
        private const val MAX_FILE_BYTES = 20_000
    }
}
