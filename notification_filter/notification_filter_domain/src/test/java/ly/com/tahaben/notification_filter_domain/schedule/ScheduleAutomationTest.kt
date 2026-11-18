package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.BAD_CREDENTIAL
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.DATE_REQUIRED
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.DISABLED
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.INVALID_SCHEDULE
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.MALFORMED_DATE
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.MALFORMED_WINDOW
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.UNKNOWN_MODE
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError.UNSUPPORTED_VERSION
import ly.com.tahaben.notification_filter_domain.testsupport.BudgetProbe
import ly.com.tahaben.notification_filter_domain.testsupport.probe
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.time.LocalDate

/**
 * The automation entry (ScheduleAutomation.handle) and its shared write path with the manual editor.
 * UNWIRED platform parts, not covered here: the exported BroadcastReceiver, its manifest permission, extras parsing
 * into ScheduleIntent, credential storage/copy/revoke UI. Supported contract version: 1.
 *
 * proves: CLAIM(automation-validation), CLAIM(last-change-wins), CLAIM(edit-preserves-spend)
 */
class ScheduleAutomationTest {

    private val today = LocalDate.of(2026, 11, 18)
    private val tomorrow = LocalDate.of(2026, 11, 19)
    private val dated = listOf(window(0, 1440, 1))
    private val datedLater = listOf(window(0, 720, 3), window(720, 1440, 3))

    private fun windowsOf(schedule: List<NotificationSchedulePeriod>) =
        schedule.map { IntentWindow(it.startMinuteOfDay, it.endMinuteOfDay, it.budgetPoints) }

    private fun intent(
        schedule: List<NotificationSchedulePeriod> = threeWindows(8, middleStart = 570),
        version: Int? = 1,
        mode: String? = null,
        date: String? = null,
        credential: String? = "s3cret",
    ) = ScheduleIntent(version, mode, date, windowsOf(schedule), credential)

    private fun harness(
        book: ScheduleBook = ScheduleBook(repeating = threeWindows(8)),
        credentials: FakeCredentials = FakeCredentials(),
    ): Pair<FakeScheduleBookStore, ScheduleAutomation> {
        val store = FakeScheduleBookStore(ScheduleSnapshot(book, SIX_SPENT_IN_NINE_AM_WINDOW))
        return store to ScheduleAutomation(ScheduleWriter(store, TEN_AM_DENVER), credentials)
    }

    private data class Rejection(
        val name: String,
        val intent: ScheduleIntent,
        val expected: AutomationError,
        val enabled: Boolean = true,
    )

    // why: invalid or unauthenticated automation changes nothing: not the schedule, not the spend, no commit.
    // catches: writing before validating; checking auth last; a wrong error reported for the fault. Each case has
    //      exactly one fault. Windows: gap at 540-545, a 59-minute window, a negative budget, an end of 1439
    //      (the old 23:59), and a window with no budget field.
    // proves: CLAIM(automation-validation)
    @Test
    fun `rejected automation leaves the schedule and spend untouched`() = runTest {
        val gap = listOf(IntentWindow(0, 540, 2), IntentWindow(545, 1440, 0))
        val short = listOf(IntentWindow(0, 540, 2), IntentWindow(540, 599, 8), IntentWindow(599, 1440, 0))
        val negative = listOf(IntentWindow(0, 540, -1), IntentWindow(540, 1440, 0))
        val old2359 = listOf(IntentWindow(0, 540, 2), IntentWindow(540, 1439, 0))
        val noBudget = listOf(IntentWindow(0, 540, null), IntentWindow(540, 1440, 0))
        val rejections = listOf(
            Rejection("automation disabled", intent(), DISABLED, enabled = false),
            Rejection("wrong credential", intent(credential = "nope"), BAD_CREDENTIAL),
            Rejection("missing credential", intent(credential = null), BAD_CREDENTIAL),
            Rejection("future version", intent(version = 2), UNSUPPORTED_VERSION),
            Rejection("missing version", intent(version = null), UNSUPPORTED_VERSION),
            Rejection("unknown mode", intent(mode = "weekly"), UNKNOWN_MODE),
            Rejection("dated without date", intent(mode = "dated"), DATE_REQUIRED),
            Rejection("impossible date", intent(mode = "dated", date = "2026-02-30"), MALFORMED_DATE),
            Rejection("non-ISO date", intent(mode = "dated", date = "11/19/2026"), MALFORMED_DATE),
            Rejection("window without budget", intent().copy(windows = noBudget), MALFORMED_WINDOW),
            Rejection("gap", intent().copy(windows = gap), INVALID_SCHEDULE),
            Rejection("59 minute window", intent().copy(windows = short), INVALID_SCHEDULE),
            Rejection("negative budget", intent().copy(windows = negative), INVALID_SCHEDULE),
            Rejection("legacy 23:59 end", intent().copy(windows = old2359), INVALID_SCHEDULE),
        )
        assertThat(rejections).hasSize(14)

        for (case in rejections) {
            val (store, automation) = harness(credentials = FakeCredentials(enabled = case.enabled))
            val before = store.snapshot

            val ack = automation.handle(case.intent)

            assertWithMessage(case.name).that(ack).isEqualTo(AutomationAck.Rejected(case.expected))
            assertWithMessage("${case.name}: commits").that(store.commits).isEmpty()
            assertWithMessage("${case.name}: snapshot").that(store.snapshot).isEqualTo(before)
        }
    }

    // why: owner decision: omitted mode is repeating, and a repeating write supersedes the dated override that is
    //      in force today so the owner's edit is not silently hidden. A dated override for another date stays.
    // catches: omitted mode rejected or treated as dated; today's override left shadowing the write; all overrides wiped.
    // proves: CLAIM(last-change-wins)
    @Test
    fun `omitted mode writes the repeating schedule and supersedes today's dated override`() = runTest {
        val book = ScheduleBook(
            repeating = threeWindows(8),
            dated = mapOf(today to dated, LocalDate.of(2026, 11, 20) to datedLater),
        )
        val (store, automation) = harness(book)

        val ack = automation.handle(intent(mode = null))

        assertThat(ack).isEqualTo(AutomationAck.Accepted)
        assertThat(store.snapshot.book.scheduleFor(today)).isEqualTo(threeWindows(8, middleStart = 570))
        assertThat(store.snapshot.book.scheduleFor(tomorrow)).isEqualTo(threeWindows(8, middleStart = 570))
        assertThat(store.snapshot.book.scheduleFor(LocalDate.of(2026, 11, 20))).isEqualTo(datedLater)
    }

    // why: a dated intent changes only its date; every other date, and the repeating schedule, are untouched.
    // catches: dated write that overwrites the repeating schedule.
    @Test
    fun `a dated intent overrides one date and nothing else`() = runTest {
        val (store, automation) = harness()

        val ack = automation.handle(intent(mode = "dated", date = "2026-11-19"))

        assertThat(ack).isEqualTo(AutomationAck.Accepted)
        assertThat(store.snapshot.book.scheduleFor(tomorrow)).isEqualTo(threeWindows(8, middleStart = 570))
        assertThat(store.snapshot.book.scheduleFor(today)).isEqualTo(threeWindows(8))
        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(8))
    }

    // why: owner decision: last successfully accepted write wins, manual or automated; a rejected write is not a write.
    // catches: automation that takes priority over later manual edits; a rejected request blanking the schedule.
    // proves: CLAIM(last-change-wins)
    @Test
    fun `the last accepted write wins and a rejected one does not count`() = runTest {
        val (store, automation) = harness()
        val manual = ScheduleWriter(store, TEN_AM_DENVER)

        manual.write(ScheduleWrite(threeWindows(5)))
        automation.handle(intent(threeWindows(6)))
        manual.write(ScheduleWrite(threeWindows(7)))
        val rejected = automation.handle(intent(credential = "nope"))

        assertThat(rejected).isEqualTo(AutomationAck.Rejected(BAD_CREDENTIAL))
        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(7))
        assertThat(store.commits).hasSize(3)
    }

    // why: Tasker may fire the same intent repeatedly. Replays must not refill spend, through the real entry point.
    //      The first intent moves the active window's start (09:00 -> 09:30), the second replays it.
    // catches: a replay that resets spend; a move keyed to the old window start. Hand math: budget 8, spent 6,
    //      2 left = one HIGH, after the first and after the second.
    // proves: CLAIM(edit-preserves-spend)
    @Test
    fun `replaying an intent does not refill spending`() = runTest {
        val (store, automation) = harness()

        assertThat(automation.handle(intent())).isEqualTo(AutomationAck.Accepted)
        val afterFirst = probe(store.snapshot, TEN_AM_DENVER)
        assertThat(automation.handle(intent())).isEqualTo(AutomationAck.Accepted)

        assertThat(store.snapshot.book.repeating).isEqualTo(threeWindows(8, middleStart = 570))
        assertThat(afterFirst).isEqualTo(BudgetProbe(remaining = 2, deliverable = 1))
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(remaining = 2, deliverable = 1))
        assertThat(store.snapshot.budget.spentPoints).isEqualTo(6)
    }
}
