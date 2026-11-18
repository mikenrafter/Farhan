package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingValue.Bool
import ly.com.tahaben.settings_portability.SettingValue.Int32
import ly.com.tahaben.settings_portability.SettingValue.Int64
import ly.com.tahaben.settings_portability.SettingValue.Text
import ly.com.tahaben.settings_portability.SettingValue.TextSet
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The production import path: file bytes -> [ImportCoordinator.stage] -> restart -> [ImportCoordinator.startup],
 * over in-memory stores that share one operation counter so a crash can be placed between any two writes.
 * Each `rig.coordinator()` call is a new process over the same "disk".
 *
 * Expected values are literals below, not read back from the code under test. Secrets are synthetic sentinels.
 *
 * Cross-cutting by design: each case drives stage, apply, rollback and journal together.
 */
class ImportCoordinatorTest {

    // ---- literal fixtures -------------------------------------------------------------------------------

    private val credentialSentinel = "SYNTHETIC-CREDENTIAL-0123456789abcdef0123456789abcdef0123456789abcdef01234567"

    /** What the destination app holds before the import. Config keys, plus keys outside the replace domain. */
    private val previousShared: Map<String, SettingValue> = mapOf(
        "notification_service_stats" to Bool(false),
        "notification_flood_threshold" to Int32(3), // config, absent from the file: must be removed
        "notification_priority_override_com.old.app" to Text("LOW"), // config, absent from the file: removed
        "notification_automation_credential_v1" to Text(credentialSentinel), // reset on success, restored on rollback
        "notification_automation_enabled_v1" to Bool(true),
        "notification_automation_last_at_v1" to Int64(1_700_000_000_000L),
        "notification_automation_last_mode_v1" to Text("REPEATING"),
        "notification_automation_last_error_v1" to Text("NONE"),
        "should_combine_db" to Bool(true), // outside the domain
        "today_usage_key" to Int64(42L), // outside the domain
    )
    private val previousDataStore: Map<String, SettingValue> = mapOf(
        "app_theme_colors" to Text("Classic"),
        "key_mindful_launch_white_list" to TextSet(setOf("com.old.app")), // config, absent from file: removed
        "some_future_key" to Text("kept"), // not in the catalog: never touched
    )
    private val previousRows = ConfigurationRows(
        apps = listOf(AppActivityRow("Old", "com.old.app", "com.old.app.Main", 0L)),
        timeLimits = listOf(TimeLimitRow("com.old.app", 60_000L, 1L)),
    )

    /** The file. Hand-written; values differ from [previousShared] so a skipped write shows. */
    private val desiredFile = Json.document(
        shared = Json.groups(
            booleans = """"notification_service_stats":true,"notification_priority_locked_com.example.chat":true""",
            ints = """"notification_batch_window_minutes":45,"notification_channel_priority_offset_com.example.chat_direct":-1""",
            longs = """"notify_me_schedule_date":9007199254740993""",
            strings = """"notification_category_priority_SOCIAL":"LOW"""",
            stringSets = """"notification_filter_exceptions":["com.example.b","com.example.a"]""",
        ),
        dataStore = Json.groups(
            booleans = """"app_main_switch":false""",
            ints = """"key_delayed_launch_duration":9""",
            strings = """"app_theme_colors":"Ocean"""",
        ),
        apps = """{"appName":"Chat","packageName":"com.example.chat","activityName":"com.example.chat.Main","userSerial":0}""",
        timeLimits = """{"packageName":"com.example.chat","timeLimitInMilli":3600000,"timeAtAddingInMilli":1700000000000}""",
    )

    private val desiredSharedConfig: Map<String, SettingValue> = mapOf(
        "notification_service_stats" to Bool(true),
        "notification_priority_locked_com.example.chat" to Bool(true),
        "notification_batch_window_minutes" to Int32(45),
        "notification_channel_priority_offset_com.example.chat_direct" to Int32(-1),
        "notify_me_schedule_date" to Int64(9007199254740993L),
        "notification_category_priority_SOCIAL" to Text("LOW"),
        "notification_filter_exceptions" to TextSet(setOf("com.example.a", "com.example.b")),
    )
    private val desiredDataStoreConfig: Map<String, SettingValue> = mapOf(
        "app_main_switch" to Bool(false),
        "key_delayed_launch_duration" to Int32(9),
        "app_theme_colors" to Text("Ocean"),
    )
    private val desiredRows = ConfigurationRows(
        apps = listOf(AppActivityRow("Chat", "com.example.chat", "com.example.chat.Main", 0L)),
        timeLimits = listOf(TimeLimitRow("com.example.chat", 3_600_000L, 1_700_000_000_000L)),
    )

    /** Previous map minus its config keys, which the import must leave alone. */
    private val sharedOutsideDomain = previousShared.filterKeys {
        it in setOf("should_combine_db", "today_usage_key")
    }
    private val dataStoreOutsideDomain = previousDataStore.filterKeys { it == "some_future_key" }

    private val expectedSharedAfterImport = sharedOutsideDomain + desiredSharedConfig +
        ("notification_automation_enabled_v1" to Bool(false))
    private val expectedDataStoreAfterImport = dataStoreOutsideDomain + desiredDataStoreConfig

    private val expectedPreview = ImportPreview(
        source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0),
        sharedPreferenceCount = 7, // 2 booleans + 2 ints + 1 long + 1 string + 1 set
        dataStoreCount = 3,
        appActivityRowCount = 1,
        timeLimitRowCount = 1,
        unsupportedKeys = emptyList(),
    )

    private class Rig(
        shared: Map<String, SettingValue>,
        dataStore: Map<String, SettingValue>,
        rows: ConfigurationRows,
    ) {
        val faults = Faults()
        val shared = FakePreferencesStore(faults, shared)
        val dataStore = FakePreferencesStore(faults, dataStore)
        val rows = FakeRowsStore(faults, rows)
        val journal = FakeJournalStorage(faults)
        var validator = SemanticValidator { null }

        /** A new process over the same disk. */
        fun coordinator() = ImportCoordinator(journal, shared, dataStore, rows, validator)
    }

    private fun rig() = Rig(previousShared, previousDataStore, previousRows)

    // Failures are keyed on the value written, so only the forward apply of the desired configuration fails and a
    // replayed restore of the previous configuration is not caught by the same fault.
    private fun Rig.failApplyOfShared() { shared.failWhen = { "notification_batch_window_minutes" in it } }
    private fun Rig.failApplyOfDataStore() { dataStore.failWhen = { "key_delayed_launch_duration" in it } }
    private fun Rig.failApplyOfRows() { rows.failWhen = { it == desiredRows } }
    private fun Rig.failRestoreOfRows() { rows.failWhen = { it == previousRows } }

    private fun stagedRig(): Rig = rig().also {
        assertThat(it.coordinator().stage(desiredFile.toByteArray())).isEqualTo(StageResult.Staged(expectedPreview))
    }

    private fun Rig.assertStoresEqualPrevious() {
        assertThat(shared.contents).isEqualTo(previousShared)
        assertThat(dataStore.contents).isEqualTo(previousDataStore)
        assertThat(rows.contents).isEqualTo(previousRows)
    }

    private fun Rig.assertStoresEqualDesired() {
        assertThat(shared.contents).isEqualTo(expectedSharedAfterImport)
        assertThat(dataStore.contents).isEqualTo(expectedDataStoreAfterImport)
        assertThat(rows.contents).isEqualTo(desiredRows)
    }

    // ---- tests ------------------------------------------------------------------------------------------

    // why: an invalid or hostile file must change nothing, not even the journal (invariant: invalid imports change
    //      nothing). Each defect class is a separate entry point into the same stage seam.
    // proves: CLAIM(stage-no-write)
    // catches: validating after writing the journal or a store; a stage that skips the catalog or the validator;
    //          reporting the wrong class of error. The control proves the probe moves: the same stage call with the
    //          valid file does write, and exactly one journal record.
    @Test
    fun `a file that fails validation changes no store and writes no journal`() {
        val control = rig()
        assertThat(control.coordinator().stage(desiredFile.toByteArray())).isEqualTo(StageResult.Staged(expectedPreview))
        assertThat(control.journal.current.phaseName).isEqualTo("Staged")
        assertThat(control.faults.operations).isEqualTo(1) // the journal write is the only mutation
        control.assertStoresEqualPrevious() // staging alone never changes a store

        val duplicateMember = Json.document(shared = Json.groups(booleans = """"a":true,"a":false"""))
        val credentialInFile = Json.document(
            shared = Json.groups(strings = """"notification_automation_credential_v1":"$credentialSentinel""""),
        )
        val unknownSetting = Json.document(shared = Json.groups(strings = """"future_setting":"x""""))
        val wrongTypeForKnownKey = Json.document(shared = Json.groups(strings = """"notification_batch_window_minutes":"45""""))
        val validDocumentWithManyEntries = Json.document(
            shared = Json.groups(ints = """"notification_flood_threshold":1,"notification_batch_window_minutes":2,"notification_standing_threshold_hours":3,"infinite_scroll_time_out":4"""),
        )
        val cases: List<Triple<String, ByteArray, PortableSettingsErrorCode>> = listOf(
            Triple("truncated", desiredFile.dropLast(25).toByteArray(), PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("duplicate member", duplicateMember.toByteArray(), PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple("unknown version", Json.document(version = "2").toByteArray(), PortableSettingsErrorCode.UNSUPPORTED_VERSION),
            Triple("credential in file", credentialInFile.toByteArray(), PortableSettingsErrorCode.EXCLUDED_KEY),
            Triple("unknown setting", unknownSetting.toByteArray(), PortableSettingsErrorCode.UNSUPPORTED_KEY),
            Triple("type disagrees with catalog", wrongTypeForKnownKey.toByteArray(), PortableSettingsErrorCode.TYPE_MISMATCH),
        )
        assertThat(cases.map { it.first }).containsExactly(
            "truncated", "duplicate member", "unknown version", "credential in file", "unknown setting", "type disagrees with catalog",
        )
        for ((name, bytes, code) in cases) {
            val rig = rig()
            val result = rig.coordinator().stage(bytes)
            assertWithMessage(name).that(result).isInstanceOf(StageResult.Rejected::class.java)
            assertWithMessage(name).that((result as StageResult.Rejected).error.code).isEqualTo(code)
            rig.assertStoresEqualPrevious()
            assertWithMessage(name).that(rig.journal.current).isNull()
            assertWithMessage(name).that(rig.faults.operations).isEqualTo(0)
            assertWithMessage(name).that(rig.coordinator().blocksNormalOperation()).isFalse()
        }

        // limit: 4 entries accepted at exactly the limit, rejected one over.
        val atLimit = rig().coordinator().stage(validDocumentWithManyEntries.toByteArray(), DecodeLimits(maxEntries = 4))
        assertThat(atLimit).isInstanceOf(StageResult.Staged::class.java)
        val overLimitRig = rig()
        val overLimit = overLimitRig.coordinator().stage(validDocumentWithManyEntries.toByteArray(), DecodeLimits(maxEntries = 3))
        assertThat((overLimit as StageResult.Rejected).error.code).isEqualTo(PortableSettingsErrorCode.TOO_MANY_ENTRIES)
        assertThat(overLimitRig.faults.operations).isEqualTo(0)

        // meaning the pure module cannot check goes through the validator port.
        val semanticRig = rig().also {
            it.validator = SemanticValidator { configuration ->
                if (configuration.sharedPreferences.containsKey("notification_category_priority_SOCIAL")) {
                    SemanticProblem(SettingsStoreId.SHARED_PREFERENCES, "notification_category_priority_SOCIAL")
                } else null
            }
        }
        val semantic = semanticRig.coordinator().stage(desiredFile.toByteArray())
        assertThat(semantic).isEqualTo(
            StageResult.Rejected(
                PortableSettingsError(
                    PortableSettingsErrorCode.SEMANTIC_REJECTED,
                    "sharedPreferences/notification_category_priority_SOCIAL",
                ),
            ),
        )
        assertThat(semanticRig.faults.operations).isEqualTo(0)
    }

    // why: a valid import replaces exactly the configuration domain: stale config keys go, unrelated keys
    //      (internal flag, usage counter) and unknown keys stay. Per spec review F3, destination automation is
    //      revoked in the same commit; source secrets remain nontransferable. Report until acknowledged.
    // proves: CLAIM(domain-replace), CLAIM(journal-first)
    // catches: a restore that clears the whole store; one that only adds keys; a restore that deletes the
    //          credential only after Applied; clearing the journal before the owner is told; applying before restart.
    @Test
    fun `a staged import replaces exactly the configuration and reports until acknowledged`() {
        val rig = stagedRig()
        rig.assertStoresEqualPrevious()

        val result = rig.coordinator().startup()

        assertThat(result).isEqualTo(StartupResult.Applied(expectedPreview))
        assertThat(result.allowsNormalOperation).isTrue()
        rig.assertStoresEqualDesired()
        assertThat(rig.journal.current).isEqualTo(ImportJournal.Applied(expectedPreview))

        // A crash after the work must still show the outcome, and must not apply a second time.
        val operationsBefore = rig.faults.operations
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.Applied(expectedPreview))
        assertThat(rig.faults.operations).isEqualTo(operationsBefore)
        rig.assertStoresEqualDesired()

        rig.coordinator().acknowledge()
        assertThat(rig.journal.current).isNull()
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.NoImport)
    }

    // why: a store that fails while applying must send every component back to the previous configuration,
    //      including components already written, and must never be reported as applied.
    // proves: CLAIM(rollback-on-doubt), CLAIM(all-or-previous)
    // catches: rollback that restores only the failing store; rollback that skips stores already applied; a result
    //          or journal that says Applied after a failure. Each component is failed in turn, so the first store
    //          (nothing else written yet) and the last (everything else written) are both covered.
    @Test
    fun `a failure in any store rolls every store back to the previous settings`() {
        val cases = listOf(
            SettingsComponent.CONFIGURATION_ROWS to { rig: Rig -> rig.failApplyOfRows() },
            SettingsComponent.DATA_STORE to { rig: Rig -> rig.failApplyOfDataStore() },
            SettingsComponent.SHARED_PREFERENCES to { rig: Rig -> rig.failApplyOfShared() },
        )
        assertThat(cases.map { it.first }).containsExactly(
            SettingsComponent.CONFIGURATION_ROWS, SettingsComponent.DATA_STORE, SettingsComponent.SHARED_PREFERENCES,
        )
        for ((component, injectFailure) in cases) {
            val rig = stagedRig()
            injectFailure(rig)
            val failure = ImportFailure(FailureReason.STORE_FAILED, component)

            val result = rig.coordinator().startup()

            assertThat(result).isEqualTo(StartupResult.RolledBack(failure))
            assertThat(result.allowsNormalOperation).isTrue()
            rig.assertStoresEqualPrevious()
            assertThat(rig.journal.current).isEqualTo(ImportJournal.RolledBack(failure))
            assertThat(rig.coordinator().blocksNormalOperation()).isFalse()
        }
    }

    // why: the process can die between any two durable writes, including inside a store write. After the next
    //      startup every component must hold the desired configuration or every component the previous one, and
    //      while the journal says apply/rollback is in progress, normal operation must stay blocked.
    // proves: CLAIM(all-or-previous), CLAIM(rollback-on-doubt)
    // catches: journal written after the first store write; no replay of Applying; replay that rolls forward on a
    //          store that cannot finish; apply that never reaches a terminal journal; a gate that lets screens read
    //          while Applying or RollingBack is pending. Torn writes (half a store) are swept too.
    @Test
    fun `a crash at every durable write converges to all previous or all desired`() {
        val applied = StartupResult.Applied(expectedPreview)
        val failed = StartupResult.RolledBack(ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.SHARED_PREFERENCES))
        val applyPhases = crashSweep(tear = false, stagedReplay = applied) { }
        val tornPhases = crashSweep(tear = true, stagedReplay = applied) { }
        // rollback path: shared preferences fails, then the process dies at each rollback write.
        val rollbackPhases = crashSweep(tear = false, stagedReplay = failed) { it.failApplyOfShared() }
        val tornRollbackPhases = crashSweep(tear = true, stagedReplay = failed) { it.failApplyOfShared() }

        // The sweeps must actually have landed in each phase, or the loop proves nothing.
        assertThat(applyPhases).containsAtLeast("Staged", "Applying")
        assertThat(tornPhases).containsAtLeast("Staged", "Applying")
        assertThat(rollbackPhases).containsAtLeast("Applying", "RollingBack")
        assertThat(tornRollbackPhases).containsAtLeast("Applying", "RollingBack")
    }

    private fun crashSweep(tear: Boolean, stagedReplay: StartupResult, configure: (Rig) -> Unit): Set<String> {
        val dry = stagedRig().also(configure)
        val before = dry.faults.operations
        dry.coordinator().startup()
        val operationsInRun = dry.faults.operations - before
        assertThat(operationsInRun).isAtLeast(5) // journal, three stores, journal

        val phasesSeen = mutableSetOf<String>()
        for (crashPoint in 1..operationsInRun) {
            val rig = stagedRig().also(configure)
            rig.faults.crashAtOperation = rig.faults.operations + crashPoint
            rig.faults.tearOnCrash = tear
            assertThrows("crash point $crashPoint", SimulatedProcessDeath::class.java) { rig.coordinator().startup() }
            rig.faults.crashAtOperation = null

            val persisted = rig.journal.current
            phasesSeen += persisted.phaseName
            val inProgress = persisted is ImportJournal.Applying || persisted is ImportJournal.RollingBack
            assertThat(rig.coordinator().blocksNormalOperation()).isEqualTo(inProgress)

            val result = rig.coordinator().startup()

            when (persisted) {
                is ImportJournal.Staged -> {
                    assertThat(result).isEqualTo(stagedReplay)
                    if (stagedReplay is StartupResult.Applied) rig.assertStoresEqualDesired()
                    else rig.assertStoresEqualPrevious()
                }
                is ImportJournal.Applying -> {
                    assertThat(result).isEqualTo(StartupResult.RolledBack(ImportFailure(FailureReason.INTERRUPTED)))
                    rig.assertStoresEqualPrevious()
                }
                is ImportJournal.RollingBack -> {
                    assertThat(result).isEqualTo(StartupResult.RolledBack(persisted.failure))
                    rig.assertStoresEqualPrevious()
                }
                else -> throw AssertionError("a crash cannot leave ${persisted.phaseName} at point $crashPoint")
            }
            assertThat(result.allowsNormalOperation).isTrue()
            assertThat(rig.coordinator().blocksNormalOperation()).isFalse()
        }
        return phasesSeen
    }

    // why: if rollback itself fails, the app must not run on mixed settings and must not claim success. It keeps
    //      a journal that a later start can finish.
    // proves: CLAIM(blocked-not-success)
    // catches: treating a failed restore as RolledBack; clearing the journal on any exit; acknowledge() clearing
    //          a journal that still needs recovery; a retry that cannot finish once the fault is gone; restoring
    //          nothing after the first failed restore.
    @Test
    fun `a failed rollback blocks startup keeps the journal and finishes on retry`() {
        val rig = stagedRig()
        rig.failApplyOfShared() // the apply fails at the last store
        rig.failRestoreOfRows() // and the restore of rows fails too
        val cause = ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.SHARED_PREFERENCES)

        val blocked = rig.coordinator().startup()

        assertThat(blocked).isEqualTo(StartupResult.RecoveryRequired(cause))
        assertThat(blocked.allowsNormalOperation).isFalse()
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.RollingBack::class.java)
        assertThat(rig.coordinator().blocksNormalOperation()).isTrue()
        assertThrows(IllegalStateException::class.java) { rig.coordinator().acknowledge() }
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.RollingBack::class.java)
        // The other components were still restored; rows were not. The state is mixed, and says so by blocking.
        assertThat(rig.shared.contents).isEqualTo(previousShared)
        assertThat(rig.dataStore.contents).isEqualTo(previousDataStore)
        assertThat(rig.rows.contents).isEqualTo(desiredRows)

        // Same fault again: still blocked, never success.
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.RecoveryRequired(cause))
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.RollingBack::class.java)

        rig.rows.failWhen = { false }
        val recovered = rig.coordinator().startup()

        assertThat(recovered).isEqualTo(StartupResult.RolledBack(cause))
        rig.assertStoresEqualPrevious()
        assertThat(rig.journal.current).isEqualTo(ImportJournal.RolledBack(cause))
        assertThat(rig.coordinator().blocksNormalOperation()).isFalse()
    }

    // why: a journal that exists but cannot be read must stop startup. Reading it as "no import" would let the
    //      app run on whatever half-applied state a crash left.
    // proves: CLAIM(blocked-not-success)
    // catches: catching JournalUnreadableException and returning NoImport; staging over an unreadable journal.
    @Test
    fun `an unreadable journal blocks startup and is never read as no import`() {
        val rig = stagedRig()
        rig.coordinator().startup().also { assertThat(it).isInstanceOf(StartupResult.Applied::class.java) }
        rig.journal.unreadable = true
        val operationsBefore = rig.faults.operations

        val result = rig.coordinator().startup()

        assertThat(result).isEqualTo(StartupResult.RecoveryRequired(ImportFailure(FailureReason.JOURNAL_UNREADABLE)))
        assertThat(rig.coordinator().blocksNormalOperation()).isTrue()
        assertThat(rig.coordinator().stage(desiredFile.toByteArray())).isEqualTo(StageResult.NotStaged(NotStagedReason.JOURNAL_UNAVAILABLE))
        assertThat(rig.faults.operations).isEqualTo(operationsBefore)
    }

    // why: if the current settings cannot be read, there is nothing to roll back to, so the import must not start.
    // proves: CLAIM(journal-first)
    // catches: applying without a captured previous configuration; a crash path that still writes the stores.
    @Test
    fun `an unreadable current store stops the import before any store changes`() {
        val rig = stagedRig()
        rig.dataStore.failReads = true
        val operationsBefore = rig.faults.operations

        val result = rig.coordinator().startup()

        assertThat(result).isEqualTo(StartupResult.RolledBack(ImportFailure(FailureReason.PREVIOUS_UNREADABLE)))
        rig.dataStore.failReads = false
        rig.assertStoresEqualPrevious()
        // only the terminal journal record is written; no store was touched
        assertThat(rig.faults.operations - operationsBefore).isEqualTo(1)
        assertThat(rig.journal.current).isEqualTo(ImportJournal.RolledBack(ImportFailure(FailureReason.PREVIOUS_UNREADABLE)))
    }

    // why: replaying Staged after changing a store can silently roll forward from mixed state (review F2).
    // proves: CLAIM(journal-first), CLAIM(rollback-on-doubt)
    // catches: retaining Applying only in memory or persisting it after the first store commit.
    @Test
    fun `a crash after the first store commit retains Applying and rolls back on restart`() {
        val rig = stagedRig()
        // Applying journal, rows commit, then death before the DataStore commit.
        rig.faults.crashAtOperation = rig.faults.operations + 3
        assertThrows(SimulatedProcessDeath::class.java) { rig.coordinator().startup() }
        assertThat(rig.rows.contents).isEqualTo(desiredRows) // proves a store commit happened
        assertThat(rig.journal.current).isInstanceOf(ImportJournal.Applying::class.java)
        rig.faults.crashAtOperation = null
        assertThat(rig.coordinator().startup()).isEqualTo(StartupResult.RolledBack(ImportFailure(FailureReason.INTERRUPTED)))
        rig.assertStoresEqualPrevious()
    }

    // why: a new file must not erase pending recovery or an outcome the owner has not acknowledged (review F8).
    // proves: CLAIM(stage-no-write), CLAIM(blocked-not-success)
    // catches: overwriting Applying, RollingBack, Applied or RolledBack with a fresh Staged record.
    @Test
    fun `staging refuses in-progress and unacknowledged terminal journals without mutations`() {
        val previous = SettingsConfiguration(previousShared, previousDataStore, previousRows)
        val desired = SettingsConfiguration(desiredSharedConfig, desiredDataStoreConfig, desiredRows)
        val failure = ImportFailure(FailureReason.INTERRUPTED)
        val phases = listOf(
            ImportJournal.Applying(previous, desired, expectedPreview),
            ImportJournal.RollingBack(previous, failure),
            ImportJournal.Applied(expectedPreview),
            ImportJournal.RolledBack(failure),
        )
        assertThat(phases.map { it.phaseName }).containsExactly("Applying", "RollingBack", "Applied", "RolledBack")
        for (phase in phases) {
            val rig = rig()
            rig.journal.write(phase)
            val before = rig.faults.operations
            assertWithMessage(phase.phaseName).that(rig.coordinator().stage(desiredFile.toByteArray()))
                .isEqualTo(StageResult.NotStaged(NotStagedReason.IMPORT_IN_PROGRESS))
            assertThat(rig.faults.operations).isEqualTo(before)
            assertThat(rig.journal.current).isEqualTo(phase)
            rig.assertStoresEqualPrevious()
        }
    }

    // why: an older build's stored type is part of the previous snapshot even when today's catalog disagrees.
    // proves: CLAIM(all-or-previous)
    // catches: validating or coercing the previous configuration before journaling, losing an Int64 on rollback.
    @Test
    fun `rollback restores previous values with their raw stored types`() {
        val rawShared = previousShared + ("notification_flood_threshold" to Int64(9_007_199_254_740_993L))
        val rig = Rig(rawShared, previousDataStore, previousRows)
        assertThat(rig.coordinator().stage(desiredFile.toByteArray())).isEqualTo(StageResult.Staged(expectedPreview))
        rig.failApplyOfShared()
        assertThat(rig.coordinator().startup()).isEqualTo(
            StartupResult.RolledBack(ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.SHARED_PREFERENCES)),
        )
        assertThat(rig.shared.contents).isEqualTo(rawShared)
        assertThat(rig.dataStore.contents).isEqualTo(previousDataStore)
        assertThat(rig.rows.contents).isEqualTo(previousRows)
    }

    // why: the owner may edit settings after staging; rollback must not discard those edits.
    // proves: CLAIM(all-or-previous), CLAIM(journal-first)
    // catches: capturing previous at staging instead of immediately before the cold-start apply.
    @Test
    fun `rollback uses settings edited between staging and restart`() {
        val rig = stagedRig()
        val edited = previousShared + ("notification_flood_threshold" to Int32(19))
        rig.shared.apply(emptySet(), mapOf("notification_flood_threshold" to Int32(19)))
        rig.failApplyOfShared()
        assertThat(rig.coordinator().startup()).isEqualTo(
            StartupResult.RolledBack(ImportFailure(FailureReason.STORE_FAILED, SettingsComponent.SHARED_PREFERENCES)),
        )
        assertThat(rig.shared.contents).isEqualTo(edited)
        assertThat(rig.dataStore.contents).isEqualTo(previousDataStore)
        assertThat(rig.rows.contents).isEqualTo(previousRows)
    }
}
