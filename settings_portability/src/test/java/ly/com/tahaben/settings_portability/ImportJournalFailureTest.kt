package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingValue.Bool
import ly.com.tahaben.settings_portability.SettingValue.Int32
import ly.com.tahaben.settings_portability.SettingValue.Text
import org.junit.Test

/**
 * A failure of the journal itself, which the main coordinator suite does not inject (it fails stores, crashes and
 * unreadable reads). The invariant: when a phase cannot be made durable the result is RecoveryRequired, never a
 * success, the previous durable phase stays on disk, and a later startup converges.
 */
class ImportJournalFailureTest {

    private val previousShared = mapOf<String, SettingValue>(
        "notification_flood_threshold" to Int32(3),
        "notification_automation_credential_v1" to Text("SYNTHETIC-CREDENTIAL"),
        "notification_automation_enabled_v1" to Bool(true),
    )
    private val previousDataStore = mapOf<String, SettingValue>("app_theme_colors" to Text("Classic"))
    private val previousRows = ConfigurationRows(apps = listOf(AppActivityRow("Old", "com.old.app", "com.old.app.Main", 0L)))

    private val file = Json.document(
        shared = Json.groups(ints = """"notification_batch_window_minutes":45"""),
        dataStore = Json.groups(strings = """"app_theme_colors":"Ocean""""),
        apps = """{"appName":"Chat","packageName":"com.example.chat","activityName":"com.example.chat.Main","userSerial":0}""",
    )
    private val desiredShared = mapOf<String, SettingValue>(
        "notification_batch_window_minutes" to Int32(45),
        "notification_automation_enabled_v1" to Bool(false),
    )
    private val desiredDataStore = mapOf<String, SettingValue>("app_theme_colors" to Text("Ocean"))
    private val desiredRows = ConfigurationRows(apps = listOf(AppActivityRow("Chat", "com.example.chat", "com.example.chat.Main", 0L)))

    private class Rig(shared: Map<String, SettingValue>, dataStore: Map<String, SettingValue>, rows: ConfigurationRows) {
        val faults = Faults()
        val shared = FakePreferencesStore(faults, shared)
        val dataStore = FakePreferencesStore(faults, dataStore)
        val rows = FakeRowsStore(faults, rows)
        val journal = FakeJournalStorage(faults)
        fun coordinator() = ImportCoordinator(journal, shared, dataStore, rows, SemanticValidator { null })
    }

    private fun stagedRig() = Rig(previousShared, previousDataStore, previousRows).also {
        assertThat(it.coordinator().stage(file.toByteArray())).isInstanceOf(StageResult.Staged::class.java)
    }

    private val journalFailure = StartupResult.RecoveryRequired(ImportFailure(FailureReason.JOURNAL_WRITE_FAILED))

    private fun Rig.assertStoresEqualPrevious() {
        assertThat(shared.contents).isEqualTo(previousShared)
        assertThat(dataStore.contents).isEqualTo(previousDataStore)
        assertThat(rows.contents).isEqualTo(previousRows)
    }

    private fun Rig.assertStoresEqualDesired() {
        assertThat(shared.contents).isEqualTo(desiredShared)
        assertThat(dataStore.contents).isEqualTo(desiredDataStore)
        assertThat(rows.contents).isEqualTo(desiredRows)
    }

    // why: if "Applying" cannot be made durable, no store may be written (journal-first), and the app must not
    //      treat the start as finished. Staged stays, so the next start retries from the beginning.
    // proves: CLAIM(journal-first), CLAIM(blocked-not-success)
    // catches: ignoring a failed Applying write and applying anyway; reporting NoImport or Applied.
    @Test
    fun `a failed Applying write changes no store, blocks, and the next start applies`() {
        val rig = stagedRig()
        rig.journal.failWriteWhen = { it is ImportJournal.Applying }
        val before = rig.faults.operations

        assertThat(rig.coordinator().startup()).isEqualTo(journalFailure)

        rig.assertStoresEqualPrevious()
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.Staged::class.java)
        assertThat(rig.faults.operations - before).isEqualTo(1) // only the refused journal write; no store write

        rig.journal.failWriteWhen = { false }
        assertThat(rig.coordinator().startup()).isInstanceOf(StartupResult.Applied::class.java)
        rig.assertStoresEqualDesired()
    }

    // why: a store failure that cannot record "RollingBack" leaves Applying on disk. That phase blocks normal
    //      operation by itself and always rolls back, so the stores converge to previous on the next start.
    // proves: CLAIM(blocked-not-success), CLAIM(rollback-on-doubt)
    // catches: swallowing the failed RollingBack write and carrying on as rolled back; clearing the journal.
    @Test
    fun `a failed RollingBack write keeps Applying and the next start rolls back`() {
        val rig = stagedRig()
        rig.shared.failWhen = { "notification_batch_window_minutes" in it }
        rig.journal.failWriteWhen = { it is ImportJournal.RollingBack }

        assertThat(rig.coordinator().startup()).isEqualTo(journalFailure)

        assertThat(rig.journal.current).isInstanceOf(ImportJournal.Applying::class.java)
        assertThat(rig.coordinator().blocksNormalOperation()).isTrue()

        rig.journal.failWriteWhen = { false }
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.RolledBack(ImportFailure(FailureReason.INTERRUPTED)))
        rig.assertStoresEqualPrevious()
        assertThat(rig.coordinator().blocksNormalOperation()).isFalse()
    }

    // why: every store holds the desired settings when "Applied" fails to persist, but the journal still says
    //      Applying. It must not be reported as success; the documented rule (Applying rolls back) then runs.
    // proves: CLAIM(blocked-not-success), CLAIM(all-or-previous)
    // catches: returning Applied although the terminal record was not written.
    @Test
    fun `a failed Applied write is not success and the next start rolls back to previous`() {
        val rig = stagedRig()
        rig.journal.failWriteWhen = { it is ImportJournal.Applied }

        assertThat(rig.coordinator().startup()).isEqualTo(journalFailure)

        rig.assertStoresEqualDesired()
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.Applying::class.java)
        assertThat(rig.coordinator().blocksNormalOperation()).isTrue()
        assertThrowsIllegalState { rig.coordinator().acknowledge() }

        rig.journal.failWriteWhen = { false }
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.RolledBack(ImportFailure(FailureReason.INTERRUPTED)))
        rig.assertStoresEqualPrevious()
    }

    // why: after every store is restored, a failed "RolledBack" record leaves RollingBack on disk, which blocks and
    //      replays the (idempotent) restore on the next start.
    // proves: CLAIM(blocked-not-success)
    // catches: treating a failed terminal write as done; a retry that does not finish.
    @Test
    fun `a failed RolledBack write keeps RollingBack and the next start finishes`() {
        val rig = stagedRig()
        rig.shared.failWhen = { "notification_batch_window_minutes" in it }
        rig.journal.failWriteWhen = { it is ImportJournal.RolledBack }
        val cause = ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.SHARED_PREFERENCES)

        assertThat(rig.coordinator().startup()).isEqualTo(journalFailure)

        rig.assertStoresEqualPrevious()
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.RollingBack::class.java)
        assertThat(rig.coordinator().blocksNormalOperation()).isTrue()

        rig.journal.failWriteWhen = { false }
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.RolledBack(cause))
        assertThat(rig.journal.current).isEqualTo(ImportJournal.RolledBack(cause))
    }

    // why: staging writes the Staged record last; if that write fails nothing was staged and the owner is told so.
    // proves: CLAIM(stage-no-write)
    // catches: reporting Staged after a failed journal write (the owner would restart into nothing).
    @Test
    fun `a failed Staged write is reported as not staged`() {
        val rig = Rig(previousShared, previousDataStore, previousRows)
        rig.journal.failWriteWhen = { true }

        assertThat(rig.coordinator().stage(file.toByteArray())).isEqualTo(StageResult.NotStaged(NotStagedReason.JOURNAL_UNAVAILABLE))

        assertWithMessage("no journal").that(rig.journal.current).isNull()
        rig.assertStoresEqualPrevious()
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return
        }
        throw AssertionError("expected IllegalStateException")
    }
}
