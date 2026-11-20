package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import java.time.Instant
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
    // catches: throwing, appending spend before the state write, or losing the pending completion.
    @Test
    fun `state write failure returns charged and next gated operation completes it`() = runTest {
        val rig = Rig(now)
        rig.budget.failWrite = true

        assertThat(rig.settle()).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.budget.writeAttempts).isEqualTo(1)
        assertThat(rig.budget.appendAttempts).isEmpty()
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

    private suspend fun effectId(key: String, postTime: Long): String {
        val rig = Rig(now, key)
        assertThat(rig.settle(postTime)).isEqualTo(SnoozeReturnDecision.Charged)
        assertThat(rig.budget.entries).hasSize(1)
        return rig.budget.entries.values.single().effectId
    }

    private class Rig(private val instant: Instant, private val key: String = "source") {
        val rows = FakeRowStore()
        val budget = FaultingBudget()
        val poster = FakePoster(EventLog())
        val gate = DeliveryGate()
        val environment = FakeEnvironment(DEFAULT_SCHEDULE, PriorityDeliverySetting.DEFAULTS)
        private val coordinator = NotificationDeliveryCoordinator(rows, budget, poster, environment, gate)

        init {
            rows.rows[key] = item(key, priority = LOW, timestamp = 50L).copy(
                snoozeDeadline = instant.toEpochMilli(), snoozePriority = HIGH, snoozeBootCount = 10,
            )
        }

        suspend fun settle(postTime: Long = instant.toEpochMilli()) = coordinator.settleSnoozeReturn(key, postTime, instant)
    }

    private class FaultingBudget : DeliveryBudgetStore {
        var state = DeliveryBudgetState()
        var failWrite = false
        var failAppendAfterSaving = false
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
            entries.putIfAbsent(entry.effectId, entry)
            if (failAppendAfterSaving) {
                failAppendAfterSaving = false
                error("spend append acknowledgement lost")
            }
        }
    }
}
