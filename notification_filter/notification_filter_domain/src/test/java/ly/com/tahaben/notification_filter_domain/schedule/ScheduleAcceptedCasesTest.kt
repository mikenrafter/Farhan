package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.testsupport.probe
import ly.com.tahaben.notification_filter_domain.testsupport.BudgetProbe
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.time.LocalDate

class ScheduleAcceptedCasesTest {
    // why: both dated-today and replacing today's override must preserve the allowance already spent.
    // catches: rebasing only repeating writes or dropping spending when today's override is superseded.
    @Test fun `dated today then repeating preserve spending through both scopes`() = runTest {
        val today = LocalDate.of(2026, 11, 18)
        val store = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(threeWindows(8)), SIX_SPENT_IN_NINE_AM_WINDOW))
        val writer = ScheduleWriter(store, TEN_AM_DENVER)
        writer.write(ScheduleWrite(listOf(window(0, 1440, 10)), ScheduleMode.DATED, today))
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(4, 2))
        writer.write(ScheduleWrite(threeWindows(10, 570)))
        assertThat(probe(store.snapshot, TEN_AM_DENVER)).isEqualTo(BudgetProbe(4, 2))
        assertThat(store.snapshot.book.dated).doesNotContainKey(today)
    }
    // why: a date is never ignored with default or explicit repeat; invalid requests are atomic.
    // catches: silently accepting date+repeat, including the default-mode path.
    @Test fun `automation rejects repeat plus date without any commit`() = runTest {
        val store = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(threeWindows(8)), SIX_SPENT_IN_NINE_AM_WINDOW))
        val automation = ScheduleAutomation(ScheduleWriter(store, TEN_AM_DENVER), FakeCredentials())
        for (mode in listOf(null, "repeating")) {
            val ack = automation.handle(ScheduleIntent(1, mode, "2026-11-18", listOf(IntentWindow(0, 1440, 10)), "s3cret"))
            assertThat(ack).isEqualTo(AutomationAck.Rejected(AutomationError.DATE_NOT_ALLOWED))
        }
        assertThat(store.commits).isEmpty()
        assertThat(store.snapshot.budget.spentPoints).isEqualTo(6)
    }
    // why: the fixed midpoint default is exactly 90+90 for a three-hour focused zone.
    // catches: always splitting off 60 minutes, which satisfies the old property-only red test.
    @Test fun `180 minute split uses exactly two 90 minute halves`() {
        val result = DaySchedule.split(threeWindows(8), 1) as ScheduleSplitResult.Split
        assertThat(result.schedule.subList(1, 3)).containsExactly(window(540, 630, 8), window(630, 720, 0)).inOrder()
    }
}
