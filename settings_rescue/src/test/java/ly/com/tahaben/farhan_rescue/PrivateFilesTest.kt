package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.input.JobId
import ly.com.tahaben.farhan_rescue.storage.*
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateFilesTest {
    @get:Rule val folder = TemporaryFolder()

    // why: CLAIM(atomic-replace): the target is whole old or whole new, no temp file left. catches: writing in place.
    @Test fun atomicWriteReplacesWholeAndLeavesNoTemporaryFile() {
        val target = File(folder.root, "sub/value.bin")
        AtomicFiles.write(target, byteArrayOf(1, 2, 3))
        AtomicFiles.write(target, byteArrayOf(9))
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(9))
        assertThat(File(target.path + ".tmp").exists()).isFalse()
        // A crash after the temp write and before the rename leaves the old value readable.
        File(target.path + ".tmp").writeBytes(byteArrayOf(7, 7, 7, 7))
        assertThat(AtomicFiles.read(target, 100)).isEqualTo(byteArrayOf(9))
        AtomicFiles.delete(target)
        assertThat(target.exists()).isFalse()
        assertThat(File(target.path + ".tmp").exists()).isFalse()
        assertThat(AtomicFiles.read(target, 100)).isNull()
    }

    // why: a bounded read never truncates silently. catches: reading a file larger than its bound.
    @Test fun readRefusesAFileOverItsBound() {
        val target = File(folder.root, "big.bin").also { it.writeBytes(ByteArray(11)) }
        assertThrows(IllegalStateException::class.java) { AtomicFiles.read(target, 10) }
    }

    // why: CLAIM(raw-contained): only allowlisted local paths can be created, only under the job directory.
    // catches: a traversal or a remote-chosen name escaping the job directory.
    @Test fun jobDirectoryAcceptsOnlyAllowlistedLocalPaths() {
        val store = RawJobStore(File(folder.root, "raw"))
        val job = store.create(JobId.random())
        job.sink("databases/farhan_db").use { it.write(byteArrayOf(5)) }
        job.sink("datastore/appWidget-3.preferences_pb").use { it.write(byteArrayOf(6)) }
        assertThat(job.file("databases/farhan_db")!!.readBytes()).isEqualTo(byteArrayOf(5))
        assertThat(job.file("databases/farhan_db")!!.canonicalPath).startsWith(job.directory.canonicalPath)
        for (path in listOf("../evil", "databases/other", "shared_prefs/../x", "/abs/path", "datastore/appWidget-x.preferences_pb", "")) {
            assertThrows(IllegalArgumentException::class.java) { job.sink(path) }
            assertThat(job.file(path)).isNull()
        }
        assertThrows(IllegalStateException::class.java) { job.sink("databases/farhan_db") } // never overwrite a copy
    }

    // why: raw copies must disappear on cancel, error and next start. catches: leaving a job after deleteAll or delete.
    @Test fun rawJobsAreDeletedIndividuallyAndAllAtOnce() {
        val store = RawJobStore(File(folder.root, "raw"))
        val first = JobId.random(); val second = JobId.random()
        store.create(first).sink("databases/farhan_db").use { it.write(1) }
        store.create(second)
        assertThrows(IllegalStateException::class.java) { store.create(first) }
        assertThat(store.open(first)).isNotNull()
        store.delete(first)
        assertThat(store.open(first)).isNull()
        assertThat(store.isEmpty()).isFalse()
        store.deleteAll()
        assertThat(store.isEmpty()).isTrue()
    }

    // why: CLAIM(short-lived-identity). catches: an identity living past 30 minutes, a corrupt file being trusted,
    // an erase that leaves the file.
    @Test fun identityExpiresAndCorruptionIsErased() {
        val file = File(folder.root, "no_backup/adb_identity")
        val store = IdentityStore(file)
        val identity = IdentityStore.Identity(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 1_000L)
        store.save(identity)
        val loaded = store.load(1_000L + IdentityStore.MAX_AGE_MILLIS)!!
        assertThat(loaded.privateKeyPkcs8).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(loaded.certificateDer).isEqualTo(byteArrayOf(4, 5))
        assertThat(loaded.createdAtMillis).isEqualTo(1_000L)
        assertThat(store.load(1_000L + IdentityStore.MAX_AGE_MILLIS + 1)).isNull()
        assertThat(file.exists()).isFalse()

        store.save(identity)
        assertThat(store.load(999L)).isNull() // a clock that went backwards is not trusted
        assertThat(file.exists()).isFalse()

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThat(store.load(1_000L)).isNull()
        assertThat(file.exists()).isFalse()

        store.save(identity)
        store.erase()
        assertThat(file.exists()).isFalse()
    }

    // why: the converted file survives a cancelled save so the owner can retry (brief). catches: clearing on read.
    @Test fun pendingExportSurvivesReadsUntilCleared() {
        val file = File(folder.root, "pending/portable-settings.json")
        PendingExportStore(file).save("{}".toByteArray())
        val again = PendingExportStore(file)
        assertThat(again.load()).isEqualTo("{}".toByteArray())
        assertThat(again.load()).isEqualTo("{}".toByteArray())
        again.clear()
        assertThat(again.load()).isNull()
    }
}
