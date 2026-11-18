package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.adb.PackageVersion
import ly.com.tahaben.farhan_rescue.capture.*
import ly.com.tahaben.farhan_rescue.input.JobId
import ly.com.tahaben.farhan_rescue.storage.JobDirectory
import ly.com.tahaben.farhan_rescue.storage.RawJobStore
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureEngineTest {
    @get:Rule val folder = TemporaryFolder()

    private fun job(): JobDirectory = RawJobStore(folder.newFolder()).create(JobId.random())

    private fun fullDevice(user: Int = 0) = FakeDevice(uid = user * 100_000L + 10_234).apply {
        files["shared_prefs/shared_pref.xml"] = "main".toByteArray()
        files["shared_prefs/shared_pref.xml.bak"] = "backup".toByteArray()
        files["files/datastore/farhan_data_store.preferences_pb"] = byteArrayOf(10, 7, 10, 1, 97, 18, 2, 8, 1)
        files["files/datastore/appWidget-7.preferences_pb"] = byteArrayOf(1, 2, 3)
        files["files/datastore/unrelated.txt"] = "x".toByteArray()
        files["databases/farhan_db"] = ByteArray(5000) { (it % 251).toByte() }
        files["databases/farhan_db-wal"] = ByteArray(300) { 7 }
        files["cache/never.bin"] = "never".toByteArray()
    }

    private fun capture(device: FakeDevice, user: Int = 0, job: JobDirectory = job(), cancelled: () -> Unit = {}): CaptureFacts =
        CaptureEngine(device, throwIfCancelled = cancelled).export(target(user), job)

    private fun failureOf(block: () -> Unit): FailureCode = assertThrows(CaptureException::class.java, block).code

    // why: end-to-end happy path through the real engine and real job store: right files, .bak preferred, bytes exact,
    // digest verified, version read. catches: copying the main XML, losing a file, a wrong local path, no verification.
    @Test fun exportsExactlyTheAllowlistedFilesAndPrefersTheBackup() {
        val device = fullDevice()
        val directory = job()
        val facts = capture(device, job = directory)
        assertThat(facts.localPaths).containsExactly(
            "shared_prefs/shared_pref.xml", "datastore/appWidget-7.preferences_pb", "datastore/farhan_data_store.preferences_pb",
            "databases/farhan_db", "databases/farhan_db-wal",
        ).inOrder()
        assertThat(facts.usedPreferencesBackup).isTrue()
        assertThat(facts.integrity).isEqualTo(Integrity.SHA256)
        assertThat(facts.version).isEqualTo(PackageVersion("0.2.6-alpha", 11))
        assertThat(facts.uid).isEqualTo(10234)
        assertThat(String(directory.file("shared_prefs/shared_pref.xml")!!.readBytes())).isEqualTo("backup")
        assertThat(directory.file("databases/farhan_db")!!.readBytes()).isEqualTo(device.files["databases/farhan_db"])
        assertThat(directory.file("datastore/farhan_data_store.preferences_pb")!!.readBytes()).isEqualTo(device.files["files/datastore/farhan_data_store.preferences_pb"])
        assertThat(device.log.joinToString("\n")).doesNotContain("never.bin")
        assertThat(device.log.joinToString("\n")).doesNotContain("unrelated")
    }

    // why: CLAIM(run-as-first) and CLAIM(stop-once): probe first, one stop, stop before the first copy, and check() is read-only.
    // catches: stopping before the probe, stopping per file, copying before stopping, a check that stops the app.
    @Test fun orderIsProbeThenStopThenCopyAndCheckNeverStops() {
        val device = fullDevice()
        capture(device)
        val log = device.log
        assertThat(log.first()).contains("id -u")
        assertThat(log.count { it.contains("am force-stop") }).isEqualTo(1)
        assertThat(log.indexOfFirst { it.contains("am force-stop") }).isLessThan(log.indexOfFirst { it.contains(" cat ") })
        assertThat(log.indexOfFirst { it.contains("id -u") }).isLessThan(log.indexOfFirst { it.contains("am force-stop") })

        val checkDevice = fullDevice()
        val facts = CaptureEngine(checkDevice).check(target())
        assertThat(facts.plannedFileCount).isEqualTo(5)
        assertThat(facts.hasDatabase).isTrue()
        assertThat(checkDevice.log.any { it.contains("am force-stop") || it.contains(" cat ") }).isFalse()
    }

    // why: a package that is not debuggable stops everything after the probe. catches: continuing past a run-as error.
    @Test fun nothingRunsAfterAFailedProbe() {
        val device = fullDevice().apply { debuggable = false }
        assertThat(failureOf { capture(device) }).isEqualTo(FailureCode.RUN_AS_NOT_DEBUGGABLE)
        assertThat(device.log).hasSize(1)
    }

    // why: a uid from another Android user must not pass (spec: requested user, not any numeric uid).
    // catches: dropping the uidMatchesUser check; stopping the app before that check.
    @Test fun uidOfAnotherUserIsRefusedBeforeAnyStop() {
        val device = fullDevice(user = 0)
        assertThat(failureOf { capture(device, user = 10) }).isEqualTo(FailureCode.UID_USER_MISMATCH)
        assertThat(device.log.any { it.contains("am force-stop") }).isFalse()
    }

    // why: CLAIM(sentinel) for the one write. catches: accepting an empty reply from `am force-stop` as success.
    @Test fun aStopWithoutTheSentinelIsAFailureAndNothingIsCopied() {
        val device = fullDevice().apply { stopFails = true }
        assertThat(failureOf { capture(device) }).isEqualTo(FailureCode.FORCE_STOP_FAILED)
        assertThat(device.log.any { it.contains(" cat ") }).isFalse()
    }

    // why: CLAIM(absent-vs-failed). A directory the root lists but whose own listing fails is an error, not "empty";
    // a directory the root does not list is absent. catches: silently omitting shared_prefs when `ls` fails.
    @Test fun failedListingIsAnErrorButAnAbsentDirectoryIsFine() {
        val failing = fullDevice().apply { failListingOf = "shared_prefs" }
        assertThat(failureOf { capture(failing) }).isEqualTo(FailureCode.LISTING_FAILED)

        val fresh = FakeDevice().apply { files["shared_prefs/shared_pref.xml"] = "x".toByteArray() }
        val facts = capture(fresh)
        assertThat(facts.localPaths).containsExactly("shared_prefs/shared_pref.xml")
    }

    // why: an app dir with no allowlisted file is probably the wrong package/user. catches: exporting an empty backup.
    @Test fun emptyDataDirectoryIsRefused() {
        val device = FakeDevice().apply { files["cache/x"] = byteArrayOf(1) }
        assertThat(failureOf { capture(device) }).isEqualTo(FailureCode.NOTHING_TO_EXPORT)
        val sidecar = FakeDevice().apply { files["databases/farhan_db-wal"] = byteArrayOf(1) }
        assertThat(failureOf { capture(sidecar) }).isEqualTo(FailureCode.SIDECAR_WITHOUT_DATABASE)
    }

    // why: hostile file names in a listing must never reach a command line. catches: using a listed name unfiltered.
    @Test fun hostileListedNamesAreNeverUsed() {
        val device = fullDevice().apply {
            files["shared_prefs/shared_pref.xml; reboot"] = byteArrayOf(1)
            files["databases/farhan_db\$(id)"] = byteArrayOf(1)
        }
        capture(device)
        assertThat(device.log.joinToString("\n")).doesNotContain("reboot")
        assertThat(device.log.joinToString("\n")).doesNotContain("\$(id)")
    }

    // why: CLAIM(copy-verified). catches: accepting a short copy (dropped connection looks like end of stream) and
    // a same-length corrupted copy.
    @Test fun shortAndCorruptCopiesAreRejected() {
        val short = fullDevice().apply { copyTransform = { path, bytes -> if (path.endsWith("farhan_db")) bytes.copyOf(bytes.size - 1) else bytes } }
        assertThat(failureOf { capture(short) }).isEqualTo(FailureCode.COPY_INCOMPLETE)
        val corrupt = fullDevice().apply { copyTransform = { path, bytes -> if (path.endsWith("farhan_db")) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes } }
        assertThat(failureOf { capture(corrupt) }).isEqualTo(FailureCode.DIGEST_MISMATCH)
    }

    // why: a phone without sha256sum still exports, but the result says the digest was not checked. catches: failing
    // such phones, or claiming SHA256 without a digest.
    @Test fun missingSha256sumDowngradesIntegrityOnly() {
        val device = fullDevice().apply { hashAvailable = false }
        assertThat(capture(device).integrity).isEqualTo(Integrity.SIZE_ONLY)
    }

    // why: CLAIM(source-stable). catches: removing the second stat, and a file that grows while being read.
    @Test fun aSourceThatChangesDuringCaptureIsRefused() {
        val touched = fullDevice().apply { afterCopy = { path -> if (path.endsWith("farhan_db")) modifiedOffset[path] = 5 } }
        assertThat(failureOf { capture(touched) }).isEqualTo(FailureCode.SOURCE_CHANGED)
        val grew = fullDevice().apply { beforeCopy = { path -> if (path.endsWith("farhan_db-wal")) files[path] = files.getValue(path) + 1 } }
        assertThat(failureOf { capture(grew) }).isEqualTo(FailureCode.SOURCE_CHANGED)
        val appeared = fullDevice().apply { afterCopy = { path -> if (path.endsWith("farhan_db-wal")) files["databases/farhan_db-journal"] = byteArrayOf(1) } }
        assertThat(failureOf { capture(appeared) }).isEqualTo(FailureCode.SOURCE_CHANGED)
    }

    // why: CLAIM(bounded-capture): bounds come from stat and are checked before the first copy. 8 MiB + 1 prefs; and
    // DB 128 + WAL 32 + journal 32 + prefs 8 = 200 MiB > 192 MiB aggregate. catches: checking after copying, a per-file only check.
    @Test fun sizeBoundsAreEnforcedBeforeAnyCopy() {
        val mib = 1L shl 20
        val oversized = fullDevice().apply { sizeOverride["shared_prefs/shared_pref.xml.bak"] = 8 * mib + 1 }
        assertThat(failureOf { capture(oversized) }).isEqualTo(FailureCode.FILE_TOO_LARGE)
        assertThat(oversized.log.any { it.contains(" cat ") }).isFalse()

        val aggregate = fullDevice().apply {
            files["databases/farhan_db-journal"] = byteArrayOf(1)
            sizeOverride["databases/farhan_db"] = 128 * mib
            sizeOverride["databases/farhan_db-wal"] = 32 * mib
            sizeOverride["databases/farhan_db-journal"] = 32 * mib
            sizeOverride["shared_prefs/shared_pref.xml.bak"] = 8 * mib
        }
        assertThat(failureOf { capture(aggregate) }).isEqualTo(FailureCode.CAPTURE_TOO_LARGE)
        assertThat(aggregate.log.any { it.contains(" cat ") }).isFalse()
    }

    // why: cancellation must stop the sequence between files. catches: ignoring the cancel hook.
    @Test fun cancellationStopsTheCopyLoop() {
        val device = fullDevice()
        // Polls: probe, version, stop, plan, stat = 5 before copying; each copy polls once. The 8th poll is the third file.
        var polls = 0
        val code = failureOf { capture(device, cancelled = { if (++polls > 7) throw CaptureException(FailureCode.CANCELLED) }) }
        assertThat(code).isEqualTo(FailureCode.CANCELLED)
        assertThat(device.log.count { it.contains(" cat ") }).isEqualTo(2)
    }
}
