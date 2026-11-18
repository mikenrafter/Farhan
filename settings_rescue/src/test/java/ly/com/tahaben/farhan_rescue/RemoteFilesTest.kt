package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.adb.*
import org.junit.Test

class RemoteFilesTest {
    // why: CLAIM(allowlist-only) over the exact names the brief fixes. catches: a prefix/contains match, a shorter digit
    // cap on widgets, or a name allowed in the wrong directory.
    @Test fun acceptsOnlyTheFixedNamesInTheirOwnDirectory() {
        val accepted = listOf(
            RemoteDirectory.SHARED_PREFS to "shared_pref.xml", RemoteDirectory.SHARED_PREFS to "shared_pref.xml.bak",
            RemoteDirectory.DATA_STORE to "farhan_data_store.preferences_pb", RemoteDirectory.DATA_STORE to "appWidget-1.preferences_pb",
            RemoteDirectory.DATA_STORE to "appWidget-1234567890.preferences_pb",
            RemoteDirectory.DATABASES to "farhan_db", RemoteDirectory.DATABASES to "farhan_db-wal", RemoteDirectory.DATABASES to "farhan_db-journal",
        )
        for ((directory, name) in accepted) assertThat(RemoteFiles.classify(directory, name)).isNotNull()
        val rejected = listOf(
            RemoteDirectory.SHARED_PREFS to "shared_pref.xml; reboot", RemoteDirectory.SHARED_PREFS to "../shared_pref.xml",
            RemoteDirectory.SHARED_PREFS to "other.xml", RemoteDirectory.SHARED_PREFS to "shared_pref.xml\n",
            RemoteDirectory.DATA_STORE to "appWidget-12345678901.preferences_pb", RemoteDirectory.DATA_STORE to "appWidget-.preferences_pb",
            RemoteDirectory.DATA_STORE to "appWidget-1.preferences_pb.tmp", RemoteDirectory.DATA_STORE to "farhan_data_store.preferences_pb.tmp",
            RemoteDirectory.DATA_STORE to "sub/farhan_data_store.preferences_pb",
            RemoteDirectory.DATABASES to "farhan_db-shm", RemoteDirectory.DATABASES to "farhan_db\n", RemoteDirectory.DATABASES to "farhan_db_backup",
            RemoteDirectory.DATABASES to "shared_pref.xml", RemoteDirectory.SHARED_PREFS to "farhan_db",
            RemoteDirectory.ROOT to "farhan_db", RemoteDirectory.FILES to "farhan_data_store.preferences_pb",
        )
        for ((directory, name) in rejected) assertThat(RemoteFiles.classify(directory, name)).isNull()
    }

    // why: classifyLocal is the UI's re-check of what the bridge reports. catches: traversal or an unlisted local path.
    @Test fun localPathsAreRecheckedAgainstTheAllowlist() {
        assertThat(RemoteFiles.classifyLocal("shared_prefs/shared_pref.xml")).isEqualTo(FileRole.SHARED_PREFERENCES)
        assertThat(RemoteFiles.classifyLocal("datastore/appWidget-9.preferences_pb")).isEqualTo(FileRole.WIDGET_STATE)
        assertThat(RemoteFiles.classifyLocal("databases/farhan_db-journal")).isEqualTo(FileRole.DATABASE_JOURNAL)
        for (path in listOf("", "../x", "databases/other", "datastore/../shared_prefs/shared_pref.xml", "/etc/passwd", "shared_prefs/shared_pref.xml.bak", "datastore/appWidget-x.preferences_pb")) {
            assertThat(RemoteFiles.classifyLocal(path)).isNull()
        }
    }

    private fun plan(vararg entries: Pair<RemoteDirectory, List<String>>) = CapturePlanner.plan(entries.toMap())

    // why: CLAIM(bak-preferred): Android discards a truncated main XML when .bak exists. catches: copying the main file.
    @Test fun backupPreferencesWinOverTheMainXml() {
        val planned = plan(RemoteDirectory.SHARED_PREFS to listOf("shared_pref.xml", "shared_pref.xml.bak")) as CapturePlan.Planned
        assertThat(planned.files.map { it.name }).containsExactly("shared_pref.xml.bak")
        assertThat(planned.files.single().localPath).isEqualTo("shared_prefs/shared_pref.xml")
        assertThat(planned.usedPreferencesBackup).isTrue()
    }

    // why: a full app plan, order and ignored count. catches: widget ordering by listing order, counting wrong, copying -shm.
    @Test fun plansEveryAllowlistedFileAndCountsTheRest() {
        val planned = plan(
            RemoteDirectory.SHARED_PREFS to listOf("shared_pref.xml", "junk.xml"),
            RemoteDirectory.DATA_STORE to listOf("farhan_data_store.preferences_pb", "appWidget-7.preferences_pb", "appWidget-12.preferences_pb", "farhan_data_store.preferences_pb.tmp"),
            RemoteDirectory.DATABASES to listOf("farhan_db-wal", "farhan_db", "farhan_db-shm"),
        ) as CapturePlan.Planned
        assertThat(planned.files.map { it.remotePath }).containsExactly(
            "shared_prefs/shared_pref.xml",
            "files/datastore/appWidget-12.preferences_pb", "files/datastore/appWidget-7.preferences_pb", "files/datastore/farhan_data_store.preferences_pb",
            "databases/farhan_db", "databases/farhan_db-wal",
        ).inOrder()
        assertThat(planned.ignoredNameCount).isEqualTo(3)
        assertThat(planned.usedPreferencesBackup).isFalse()
    }

    // why: CLAIM(fresh-app-ok) and its edges. catches: failing a fresh app, accepting a sidecar with no database, an empty plan passing.
    @Test fun freshAppsPassAndBrokenShapesAreRefused() {
        assertThat((plan(RemoteDirectory.SHARED_PREFS to listOf("shared_pref.xml")) as CapturePlan.Planned).files).hasSize(1)
        assertThat(plan()).isEqualTo(CapturePlan.Refused(PlanProblem.NOTHING_TO_EXPORT))
        assertThat(plan(RemoteDirectory.DATABASES to listOf("farhan_db-wal"))).isEqualTo(CapturePlan.Refused(PlanProblem.SIDECAR_WITHOUT_DATABASE))
        assertThat(plan(RemoteDirectory.DATABASES to listOf("farhan_db-journal"))).isEqualTo(CapturePlan.Refused(PlanProblem.SIDECAR_WITHOUT_DATABASE))
        val tooMany = (1..RemoteFiles.MAX_WIDGET_FILES + 1).map { "appWidget-$it.preferences_pb" }
        assertThat(plan(RemoteDirectory.DATA_STORE to tooMany)).isEqualTo(CapturePlan.Refused(PlanProblem.TOO_MANY_WIDGET_FILES))
    }
}
