package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleBook
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod.Companion.DEFAULT_SCHEDULE
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.testsupport.item
import ly.com.tahaben.notification_filter_domain.testsupport.productionContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SnoozeReturnCoordinatorTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")

    // why: the append can report failure after committing and the next gated operation must finish it.
    // proves: CLAIM(snooze-return-budget)
    // catches: returning failure after successful native delivery, writing points twice, or retrying with a new effect id.
    @Test
    fun `append failure after the write retries the same spend without charging twice`() = runTest {
        val rig = Rig(now)
        rig.budget.failAppendAfterSaving = true

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.budget.writeAttempts).isEqualTo(1)
        assertThat(rig.budget.appendAttempts).hasSize(1)
        rig.gate.run { }

        assertThat(rig.budget.writeAttempts).isEqualTo(1)
        assertThat(rig.budget.appendAttempts).hasSize(2)
        assertThat(rig.budget.appendAttempts.toSet()).hasSize(1)
        assertThat(rig.budget.entries).hasSize(1)
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
        assertThat(rig.budget.state.hourlyCount).isEqualTo(1)
        assertThat(rig.rows.rows.getValue("source").isDelivered).isTrue()
    }

    // why: a transient state write failure must not send an already visible notification through the proxy path.
    // proves: CLAIM(snooze-return-budget)
    // catches: throwing, writing state before the row is closed, appending spend early, or losing pending completion.
    @Test
    fun `state write failure returns charged and next gated operation completes it`() = runTest {
        val rig = Rig(now)
        rig.budget.failWrite = true

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.budget.writeAttempts).isEqualTo(1)
        assertThat(rig.budget.appendAttempts).isEmpty()
        assertClosed(rig.rows.rows.getValue("source"))
        rig.gate.run { }

        assertThat(rig.budget.writeAttempts).isEqualTo(2)
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
        assertThat(rig.budget.state.hourlyCount).isEqualTo(1)
        assertThat(rig.budget.entries).hasSize(1)
        assertThat(rig.rows.rows.getValue("source").isDelivered).isTrue()
    }

    // why: a denied visible return must not write budget, spend or delivery state.
    // proves: CLAIM(snooze-return-budget)
    // catches: committing normalized budget on denial, charging, or marking a denied return delivered.
    @Test
    fun `denial does no writes and selects the first funded cadence tick`() = runTest {
        val night = Instant.parse("2026-11-20T22:30:00Z")
        val rig = Rig(night)
        rig.environment.time = productionContext(22, 30)
        val before = rig.rows.rows.getValue("source")

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.RetryAt(Instant.parse("2026-11-21T00:00:00Z")))
        assertThat(rig.budget.writeAttempts).isEqualTo(0)
        assertThat(rig.budget.appendAttempts).isEmpty()
        assertThat(rig.rows.rows.getValue("source")).isEqualTo(before)
        assertThat(rig.poster.posted).isEmpty()
    }

    // why: the original row stores the priority and payload at snooze time and is the only row completed.
    // proves: CLAIM(snooze-return-budget)
    // catches: charging the reclassified LOW, using a different proxy row id, or posting a new proxy.
    @Test
    fun `return charges stored high priority and completes only its own row`() = runTest {
        val rig = Rig(now)
        val proxy = item("proxy", timestamp = 49L)
        rig.rows.upsert(proxy)

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        val returned = rig.rows.rows.getValue("source")
        assertThat(returned.isDelivered).isTrue()
        assertThat(returned.snoozeDeadline).isNull()
        assertThat(rig.rows.rows.getValue("proxy")).isEqualTo(proxy)
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
        assertThat(rig.budget.state.hourlyCount).isEqualTo(1)
        assertThat(rig.budget.entries.values.map { it.priority to it.pointCost }).containsExactly(HIGH to 2)
        assertThat(rig.poster.posted).isEmpty()
    }

    // why: stable ids let the existing spend ledger de-duplicate replay across coordinator instances.
    // proves: CLAIM(snooze-return-budget)
    // catches: random ids, or hashing only the key or only the post time.
    @Test
    fun `effect id is deterministic and distinguishes key and post time`() = runTest {
        val first = effectId("source", 100L)
        assertThat(first).isNotEmpty()
        assertThat(effectId("source", 100L)).isEqualTo(first)
        assertThat(listOf(first, effectId("another", 100L), effectId("source", 101L))).containsNoDuplicates()
    }

    // why: a row outside native custody is never eligible for return accounting.
    // catches: charging a delivered, cleared, ordinary or missing row on a replay.
    @Test
    fun `each nonlive row returns not owned without spending`() = runTest {
        val rig = Rig(now)
        val live = rig.rows.rows.getValue("source")
        val cases = listOf(live.copy(isDelivered = true), live.copy(isCleared = true), live.copy(snoozeDeadline = null), null)
        for (row in cases) {
            if (row == null) rig.rows.rows.remove("source") else rig.rows.upsert(row)
            assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.NotOwned)
        }
        assertThat(rig.budget.writeAttempts).isEqualTo(0)
        assertThat(rig.budget.appendAttempts).isEmpty()
        assertThat(rig.rows.completed).isEmpty()
    }

    // why: interruption before the row step keeps the live row available to the restarted scan.
    // catches: spending before the row step or marking the restarted return counted twice.
    @Test
    fun `death before row completion leaves a live return that settles once after rebuild`() = runTest {
        val rig = Rig(now)
        rig.rows.failBeforeComplete = true
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.rows.rows.getValue("source").snoozeDeadline).isEqualTo(now.toEpochMilli())
        assertThat(rig.budget.writeAttempts).isEqualTo(0)

        rig.rebuild()
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.NotOwned)
        assertClosed(rig.rows.rows.getValue("source"))
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
        assertThat(rig.budget.entries).hasSize(1)
    }

    // why: if death follows the atomic row update, accepting one uncharged return prevents double charging.
    // catches: replaying a delivered row or writing budget state ahead of the row.
    @Test
    fun `death after row completion and before state leaves one uncharged return`() = runTest {
        val rig = Rig(now)
        rig.budget.failWrite = true
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertClosed(rig.rows.rows.getValue("source"))

        rig.rebuild()
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.NotOwned)
        assertThat(rig.budget.state.spentPoints).isEqualTo(0)
        assertThat(rig.budget.entries).isEmpty()
        assertThat(rig.budget.writeAttempts).isEqualTo(1)
    }

    // why: a crash after budget state may omit the ledger line but cannot replay the charge.
    // catches: requiring spend-ledger presence for ownership or appending before writing budget state.
    @Test
    fun `death after state and before spend leaves the charge and no ledger line`() = runTest {
        val rig = Rig(now)
        rig.budget.failBeforeAppend = true
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertClosed(rig.rows.rows.getValue("source"))
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)

        rig.rebuild()
        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.NotOwned)
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
        assertThat(rig.budget.state.hourlyCount).isEqualTo(1)
        assertThat(rig.budget.appendAttempts).hasSize(1)
        assertThat(rig.budget.entries).isEmpty()
    }

    // why: the next date can have a different funded period than the repeating schedule.
    // catches: using today's schedule for the whole retry search.
    @Test
    fun `return denial uses tomorrows dated schedule to find the first funded tick`() = runTest {
        val night = Instant.parse("2026-11-20T22:30:00Z")
        val rig = Rig(night)
        rig.environment.time = productionContext(22, 30)
        rig.environment.schedulesByDate = ScheduleBook(dated = mapOf(
            LocalDate.parse("2026-11-21") to listOf(
                NotificationSchedulePeriod(0, 0, 1, 30, 0),
                NotificationSchedulePeriod(1, 30, 24, 0, 8),
            ),
        ))

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.RetryAt(Instant.parse("2026-11-21T02:00:00Z")))
        assertThat(rig.budget.writeAttempts).isEqualTo(0)
        assertThat(rig.budget.entries).isEmpty()
    }

    // why: a stale batch snapshot can contain an unfinished snoozed row even after its deadline.
    // catches: delivering that row from the batch when the ordinary pending control can still post.
    @Test
    fun `a batch never proxies a past deadline row still in snooze custody`() = runTest {
        val rig = Rig(now)
        rig.rows.rows["source"] = rig.rows.rows.getValue("source").copy(
            priority = HIGH, snoozeDeadline = now.minusSeconds(3600).toEpochMilli(),
        )
        rig.rows.upsert(item("ordinary", timestamp = 49L))

        rig.batch()
        assertThat(rig.poster.posted.map { it.id }).containsExactly("ordinary")
        assertThat(rig.budget.state.spentPoints).isEqualTo(2)
    }

    // why: the funded batch must leave enough points for owned returns due at this tick.
    // catches: offering the full period to proxies or counting a MEDIUM reservation as HIGH.
    @Test
    fun `batch proxies leave live due snooze points available to the original returns`() = runTest {
        val rig = Rig(now)
        rig.rows.rows["source"] = rig.rows.rows.getValue("source").copy(
            snoozePriority = ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM,
        )
        rig.rows.rows["later"] = rig.rows.rows.getValue("source").copy(
            id = "later", snoozeDeadline = now.plusSeconds(3600).toEpochMilli(),
        )
        rig.rows.excludeSnoozesFromPending = true
        rig.budget.state = DeliveryBudgetState(spentPoints = 2, periodStartMinute = 9 * 60)
        rig.rows.upsert(item("newest", timestamp = 51L))
        rig.rows.upsert(item("older", timestamp = 49L))

        rig.batch()
        assertThat(rig.poster.posted.map { it.id }).containsExactly("newest")
        assertThat(rig.budget.state.spentPoints).isEqualTo(4)
    }

    private fun assertClosed(row: NotificationItem) {
        assertThat(row.isDelivered).isTrue()
        assertThat(row.snoozeDeadline).isNull()
        assertThat(row.snoozePriority).isNull()
        assertThat(row.snoozeBootCount).isNull()
    }

    private suspend fun effectId(key: String, postTime: Long): String {
        val rig = Rig(now, key)
        assertThat(rig.settle(postTime)).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.budget.entries).hasSize(1)
        return rig.budget.entries.values.single().effectId
    }

    private class Rig(private val instant: Instant, private val key: String = "source") {
        val rows = FaultingRows()
        val budget = FaultingBudget()
        val poster = FakePoster(EventLog())
        var gate = DeliveryGate()
            private set
        val environment = FakeEnvironment(DEFAULT_SCHEDULE, PriorityDeliverySetting.DEFAULTS)
        private var coordinator = createCoordinator()

        private fun createCoordinator() = NotificationDeliveryCoordinator(rows, budget, poster, environment, gate)

        fun rebuild() {
            gate = DeliveryGate()
            coordinator = createCoordinator()
        }

        suspend fun batch() = coordinator.onBatchTick(instant)

        init {
            rows.rows[key] = item(key, priority = LOW, timestamp = 50L).copy(
                snoozeDeadline = instant.toEpochMilli(), snoozePriority = HIGH, snoozeBootCount = 10,
            )
        }

        suspend fun settle(postTime: Long = instant.toEpochMilli()) = coordinator.settleSnoozeReturn(key, postTime, instant)
    }

    private class FaultingRows(private val backing: FakeRowStore = FakeRowStore()) : DeliveryRowStore by backing {
        val rows get() = backing.rows
        val completed = mutableListOf<String>()
        var failBeforeComplete = false
        var excludeSnoozesFromPending = false

        override suspend fun completeSnoozeReturn(id: String) {
            if (failBeforeComplete) {
                failBeforeComplete = false
                error("row write failed")
            }
            backing.completeSnoozeReturn(id)
            completed += id
        }

        override suspend fun pending(maxPriorityLevel: Int) = backing.pending(maxPriorityLevel).filter {
            !excludeSnoozesFromPending || it.snoozeDeadline == null
        }
    }

    private class FaultingBudget : DeliveryBudgetStore {
        var state = DeliveryBudgetState()
        var failWrite = false
        var failAppendAfterSaving = false
        var failBeforeAppend = false
        var writeAttempts = 0
        val appendAttempts = mutableListOf<BudgetSpendEntry>()
        val entries = linkedMapOf<String, BudgetSpendEntry>()

        override suspend fun read() = state

        override suspend fun write(state: DeliveryBudgetState) {
            writeAttempts++
            if (failWrite) {
                failWrite = false
                error("budget write failed")
            }
            this.state = state
        }

        override suspend fun appendSpend(entry: BudgetSpendEntry) {
            appendAttempts += entry
            if (failBeforeAppend) {
                failBeforeAppend = false
                error("spend append failed")
            }
            entries.putIfAbsent(entry.effectId, entry)
            if (failAppendAfterSaving) {
                failAppendAfterSaving = false
                error("spend append acknowledgement lost")
            }
        }
    }
}
