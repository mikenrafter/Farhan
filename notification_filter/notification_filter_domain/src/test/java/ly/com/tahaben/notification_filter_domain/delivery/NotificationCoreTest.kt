package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.schedule.*
import ly.com.tahaben.notification_filter_domain.testsupport.item
import ly.com.tahaben.notification_filter_domain.testsupport.productionContext
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.time.Clock
import java.time.Instant

class NotificationCoreTest {
    // why: composition must serialize an accepted edit behind a real in-flight delivery, preserving its charge.
    // catches: separately constructed coordinator/writer gates; writer reading zero before the suspended post commits.
    @Test fun `production owner serializes edit and clear against an in-flight post`() = runTest {
        val store = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(threeWindows(8)), DeliveryBudgetState()))
        val budget = object : DeliveryBudgetStore {
            override suspend fun read() = store.snapshot.budget
            override suspend fun write(state: DeliveryBudgetState) { store.snapshot = store.snapshot.copy(budget = state) }
            override suspend fun appendSpend(entry: ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry) = Unit
        }
        val env = object : DeliveryEnvironment {
            override fun schedule() = store.snapshot.book.repeating
            override fun settings() = PriorityDeliverySetting.DEFAULTS + (HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING))
            override fun timeContext() = DeliveryClock.current(false, TEN_AM_DENVER)
            override fun queuePreferences() = emptyMap<String, AppQueuePreference>()
        }
        val poster = FakePoster(EventLog()).also { it.gate = CompletableDeferred() }
        val core = NotificationCore(store, FakeRowStore(), budget, poster, env, TEN_AM_DENVER) {
            store.snapshot = store.snapshot.copy(budget = DeliveryBudgetState())
        }
        val delivery = async { core.coordinator.onArrival(item("K", 100), HIGH) }
        runCurrent()
        val edit = async { core.writer.write(ScheduleWrite(listOf(window(0, 630, 10), window(630, 1440, 4)))) }
        runCurrent()
        assertThat(edit.isCompleted).isFalse()
        poster.gate!!.complete(Unit)
        delivery.await(); edit.await()
        assertThat(core.status().spentPoints).isEqualTo(2)
        assertThat(core.status().remainingPoints).isEqualTo(8)
        poster.gate = CompletableDeferred()
        val next = async { core.coordinator.onArrival(item("J", 200), HIGH) }
        runCurrent()
        val clear = async { core.clearBudget() }
        runCurrent()
        assertThat(clear.isCompleted).isFalse()
        poster.gate!!.complete(Unit)
        next.await(); clear.await()
        assertThat(core.status().spentPoints).isEqualTo(0)
    }

    // why: edits may move now across a boundary but elapsed future boundaries and dates grant a new allowance.
    // catches: keying spend only by the old start; carrying across all future boundaries; reusing yesterday's spend.
    @Test fun `moving now to a different zone carries spend until a natural boundary or day`() = runTest {
        val store = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(threeWindows(8)), SIX_SPENT_IN_NINE_AM_WINDOW))
        val changed = listOf(window(0, 630, 10), window(630, 1440, 8))
        ScheduleWriter(store, TEN_AM_DENVER).write(ScheduleWrite(changed))
        val time = DeliveryClock.current(false, TEN_AM_DENVER)
        val first = NotificationDeliveryPolicy.canDeliverNow(HIGH, PriorityDeliverySetting.defaultFor(HIGH), changed, time, store.snapshot.budget)
        assertThat(first.updatedBudget.spentPoints).isEqualTo(6)
        assertThat(first.canDeliver).isTrue()
        val atBoundary = time.copy(minuteOfDay = 630)
        assertThat(NotificationDeliveryPolicy.canDeliverNow(HIGH, PriorityDeliverySetting.defaultFor(HIGH), changed, atBoundary, store.snapshot.budget).updatedBudget.spentPoints).isEqualTo(0)
        val tomorrow = DeliveryClock.current(false, Clock.fixed(Instant.parse("2026-11-19T17:00:00Z"), TEN_AM_DENVER.zone))
        assertThat(tomorrow.localDay).isEqualTo("2026-11-19")
        assertThat(NotificationDeliveryPolicy.canDeliverNow(HIGH, PriorityDeliverySetting.defaultFor(HIGH), changed, tomorrow, store.snapshot.budget).updatedBudget.spentPoints).isEqualTo(0)
    }

    // why: callback sequence, not wall-clock postTime, distinguishes an out-of-order stale arrival from a repost.
    // catches: comparing only timestamps, or dropping cancellation when its queued handler has not run yet.
    @Test fun `callback ordering defeats misleading post times and preserves a real repost`() = runTest {
        val h = DeliveryHarness(threeWindows(8), PriorityDeliverySetting.DEFAULTS + (HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING)))
        val old = h.coordinator.captureEvent("K")
        val removal = h.coordinator.captureEvent("K", removed = true)
        var captures = 0
        assertThat(h.coordinator.onArrival(item("K", Long.MAX_VALUE), HIGH, old, captureActions = { captures++ })).isEqualTo(DeliveryOutcome.Cleared("K"))
        assertThat(captures).isEqualTo(0)
        assertThat(h.rows.rows["K"]).isNull()
        h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, 100, removal)
        val fresh = h.coordinator.captureEvent("K")
        assertThat(h.coordinator.onArrival(item("K", 1), HIGH, fresh)).isEqualTo(DeliveryOutcome.Delivered("K"))
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
    }

    // why: stricter pilot validation cannot throw away previously valid saved half-hour windows or their budgets.
    // catches: returning defaults, dropping points, leaving a 30-minute zone or moving the day endpoint incorrectly.
    @Test fun `legacy short windows merge without losing budgets or outer coverage`() {
        val old = listOf(window(0, 30, 7), window(30, 90, 3), window(90, 1410, 4), window(1410, 1439, 6))
        val repaired = DaySchedule.repairLegacy(old)
        assertThat(repaired).isEqualTo(listOf(window(0, 90, 10), window(90, 1440, 10)))
        assertThat(DaySchedule.validate(repaired)).isEmpty()
        assertThat(DaySchedule.repairLegacy(repaired)).isEqualTo(repaired)
    }
}
