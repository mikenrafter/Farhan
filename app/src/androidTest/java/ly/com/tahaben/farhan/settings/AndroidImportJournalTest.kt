package ly.com.tahaben.farhan.settings

import androidx.test.platform.app.InstrumentationRegistry
import ly.com.tahaben.settings_portability.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AndroidImportJournalTest {
    private fun directory(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "journal-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }

    // WHY: rollback must preserve an older stored type instead of validating it as incoming configuration.
    // catches: catalog-filtering or coercing the private previous snapshot.
    @Test fun previousSnapshotRetainsRawWrongTypeAndExactLong() {
        val directory = directory()
        try {
            val previous = SettingsConfiguration(sharedPreferences = mapOf(
                "notification_flood_threshold" to SettingValue.Text("legacy-type"),
                "notification_automation_enabled_v1" to SettingValue.Bool(true),
                "internal_exact_long" to SettingValue.Int64(9_007_199_254_740_993L),
                "internal_set" to SettingValue.TextSet(setOf("b", "a"))))
            val journal = AndroidImportJournal(directory)
            journal.write(ImportJournal.RollingBack(previous, ImportFailure(FailureReason.INTERRUPTED)))
            val decoded = journal.read() as ImportJournal.RollingBack
            assertEquals(SettingValue.Text("legacy-type"), decoded.previous.sharedPreferences["notification_flood_threshold"])
            assertEquals(SettingValue.Int64(9_007_199_254_740_993L), decoded.previous.sharedPreferences["internal_exact_long"])
            assertEquals(previous, decoded.previous)
        } finally { directory.deleteRecursively() }
    }

    // WHY: every durable phase must survive Android AtomicFile and the private typed codec.
    // catches: an unexercised phase dropping preview counts, desired settings, or raw previous values.
    @Test fun everyPhaseRoundTripsThroughFreshJournalInstances() {
        val directory = directory()
        try {
            val previous = SettingsConfiguration(sharedPreferences = mapOf(
                "notification_flood_threshold" to SettingValue.Text("old-type")))
            val desired = SettingsConfiguration(sharedPreferences = mapOf(
                "notification_flood_threshold" to SettingValue.Int32(17)))
            val preview = ImportPreview(PortableSource("test.fixture", "1", 1, 0), 1, 0, 0, 0,
                listOf(UnsupportedKey(SettingsStoreId.DATA_STORE, "unknown_fixture")))
            val failure = ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.DATA_STORE)
            val phases = listOf(ImportJournal.Staged(desired, preview),
                ImportJournal.Applying(previous, desired, preview),
                ImportJournal.RollingBack(previous, failure), ImportJournal.Applied(preview),
                ImportJournal.RolledBack(failure))
            for (phase in phases) {
                AndroidImportJournal(directory).write(phase)
                assertEquals(phase, AndroidImportJournal(directory).read())
            }
        } finally { directory.deleteRecursively() }
    }

    // WHY: duplicate or truncated private records must block recovery, never masquerade as an absent journal.
    // catches: JSONObject last-value-wins and returning null on read exceptions.
    @Test fun duplicateAndTruncatedRecordsAreUnreadable() {
        val directory = directory()
        try {
            val file = File(directory, "settings_import_v1")
            for (literal in listOf("{\"version\":\"1\",\"version\":\"1\",\"phase\":\"rolledBack\"}", "{\"version\":")) {
                file.writeText(literal)
                try { AndroidImportJournal(directory).read(); fail("Corrupt journal was accepted") }
                catch (_: JournalUnreadableException) { }
            }
        } finally { directory.deleteRecursively() }
    }

    // WHY: a retained outcome is shown until acknowledgment, including the specific component failure.
    // catches: terminal records losing failure details or clear leaving the outcome behind.
    @Test fun terminalFailureSurvivesAndClearRemovesOnlyJournal() {
        val directory = directory()
        try {
            val failure = ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.DATA_STORE)
            val journal = AndroidImportJournal(directory)
            journal.write(ImportJournal.RolledBack(failure))
            assertEquals(ImportJournal.RolledBack(failure), journal.read())
            File(directory, "unrelated").writeText("keep")
            journal.clear()
            assertNull(journal.read())
            assertEquals("keep", File(directory, "unrelated").readText())
        } finally { directory.deleteRecursively() }
    }
}
