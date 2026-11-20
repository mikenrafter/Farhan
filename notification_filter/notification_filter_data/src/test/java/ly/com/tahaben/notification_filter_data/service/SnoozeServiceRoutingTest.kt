package ly.com.tahaben.notification_filter_data.service

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.*
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod.Companion.DEFAULT_SCHEDULE
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import org.junit.Test

class SnoozeServiceRoutingTest {
    private val arrival = Instant.parse("2026-11-20T08:55:00Z")
    private val deadline = Instant.parse("2026-11-20T09:00:00Z")
    private val afternoon = Instant.parse("2026-11-20T12:00:00Z")
    private val source = NotificationItem("chat", "Chat", "Message", "Body", "", "com.chat", arrival.toEpochMilli(), HIGH)

    // why: the service's captured order must become accepted when Android takes custody.
    // catches: a returned original is cancelled, but the real batch defers its saved fallback forever.
    @Test
    fun `admitted native row can fall back through real proxy delivery`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.snoozeEnabled = false
        rig.now = deadline
        rig.budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 540)
        assertThat(rig.returnOriginal()).isFalse()
        assertThat(rig.platform.canceled).containsExactly(source.id)
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()

        rig.now = afternoon
        assertThat(rig.coordinator.onBatchTick(afternoon)).containsExactly(DeliveryOutcome.Delivered(source.id))
        assertThat(rig.poster.posted).containsExactly(source.id)
    }

    // why: a queued early callback can hit durable custody before the listener cache is populated.
    // catches: accepting only admission and leaving a BEFORE_DEADLINE fallback stranded by the pre-check order.
    @Test
    fun `stored custody precheck can fall back through real proxy delivery`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.now = deadline.minusSeconds(1)
        val early = rig.visibleOriginal()
        val order = rig.coordinator.captureEvent(source.id)
        assertThat(rig.routing.handleStoredPost(early, order, rig.now)).isTrue()
        assertThat(rig.events.recent().first().fallbackReason).isEqualTo(SnoozeFallbackReason.BEFORE_DEADLINE)
        assertThat(rig.platform.canceled).containsExactly(source.id)

        rig.now = deadline
        assertThat(rig.coordinator.onBatchTick(deadline)).containsExactly(DeliveryOutcome.Delivered(source.id))
        assertThat(rig.poster.posted).containsExactly(source.id)
    }

    // why: acceptance of one native arrival must leave a newer callback pending until its own work runs.
    // catches: accepting the latest callback in the fallback or admission completion instead of the captured order.
    @Test
    fun `admission and fallback do not accept a newer event`() = runTest {
        val rig = Rig()
        var newer = 0L
        rig.admit { newer = rig.coordinator.captureEvent(source.id) }
        rig.snoozeEnabled = false
        rig.now = deadline
        rig.budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 540)
        assertThat(rig.returnOriginal()).isFalse()
        rig.now = afternoon
        assertThat(rig.coordinator.onBatchTick(afternoon)).containsExactly(DeliveryOutcome.Deferred(source.id))
        assertThat(rig.poster.posted).isEmpty()

        rig.coordinator.onArrival(source.copy(timestamp = afternoon.toEpochMilli()), HIGH, newer)
        assertThat(rig.coordinator.onBatchTick(afternoon)).containsExactly(DeliveryOutcome.Delivered(source.id))
    }

    // why: an inbox clear must dispose of the actual returned original without charging it.
    // catches: refusing to settle a cleared row while leaving it visible and keeping its deadline.
    @Test
    fun `cleared native return is canceled and next post can arrive normally`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline
        assertThat(rig.returnOriginal()).isTrue()
        rig.assertClearedReturnAndReuse()
    }

    // why: after a scan discards the cache entry, only the stored row remembers that the owner cleared it.
    // catches: treating a returned cleared item as a fresh post after rehydration or clearing a live native deadline too soon.
    @Test
    fun `uncached cleared return is canceled from the durable row`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.handler.onBatchTick(arrival.plusSeconds(1), arrival.minusSeconds(3600))
        assertThat(rig.handler.owns(source.id)).isFalse()
        rig.now = deadline
        val returned = rig.visibleOriginal()
        val order = rig.coordinator.captureEvent(source.id)
        assertThat(rig.routing.handleStoredPost(returned, order, rig.now)).isTrue()
        rig.assertClearedReturnAndReuse()
    }

    // why: a source can cancel a hidden record without a removal callback, leaving a cleared custody tombstone.
    // catches: retaining a stale deadline and canceling a later unrelated reuse of that key.
    @Test
    fun `scan retires cleared custody missing from both Android lists`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.platform.hidden.clear()
        rig.handler.onBatchTick(afternoon, afternoon.minusSeconds(3600))
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
    }

    // why: a pre-deadline post using a cleared conversation key is a new arrival, not Android's return.
    // catches: letting either service or handler pre-check claim cleared custody just because the deadline exists.
    @Test
    fun `fresh reuse before a cleared deadline is unhandled by both custody prechecks`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = arrival.plusSeconds(1)
        rig.snoozeEnabled = false
        val fresh = rig.visibleOriginal()
        assertThat(rig.handler.onPosted(fresh, rig.input(rig.now))).isFalse()
        assertThat(rig.routing.handleStoredPost(fresh, rig.coordinator.captureEvent(source.id), rig.now)).isFalse()
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.platform.canceled).isEmpty()
    }

    // why: stats can stay enabled when the global main switch is off.
    // catches: building the service return context from the stats flag alone, charging or resnoozing while paused.
    @Test
    fun `main switch off releases the original through the routed return`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.mainSwitch = false
        rig.now = deadline
        assertThat(rig.returnOriginal()).isTrue()

        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.platform.active).containsEntry(source.id, deadline.toEpochMilli())
        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.platform.requests).hasSize(1)
        assertThat(rig.budget.entries).isEmpty()
        assertThat(rig.budget.state.spentPoints).isEqualTo(0)
    }

    // why: a source summary cancellation after a charged return ends that notification's visibility.
    // catches: refreshing an already charged original after the source has removed it.
    @Test
    fun `external summary cancellation never refreshes a charged native child`() = runTest {
        val rig = Rig()
        rig.admit(item = source.copy(snoozeGroupKey = "app-group"))
        rig.now = deadline
        assertThat(rig.returnOriginal()).isTrue()
        assertThat(rig.budget.entries).hasSize(1)

        rig.platform.active.remove(source.id)
        assertThat(rig.handler.onRemoved(source.id, source.packageName, 12, deadline.plusMillis(2).toEpochMilli())).isTrue()
        assertThat(rig.poster.refreshed).isEmpty()
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
        assertThat(rig.budget.entries).hasSize(1)
    }

    private inner class Rig {
        var now = arrival
        var mainSwitch = true
        var snoozeEnabled = true
        val rows = Rows()
        val budget = Budget()
        val platform = Platform()
        val poster = Poster()
        val events = InMemoryExperimentEventLog()
        val coordinator = NotificationDeliveryCoordinator(rows, budget, poster, object : DeliveryEnvironment {
            override fun schedule() = DEFAULT_SCHEDULE
            override fun scheduleAt(instant: Instant, zone: java.time.ZoneId) = DEFAULT_SCHEDULE
            override fun zone() = ZoneOffset.UTC
            override fun settings() = PriorityDeliverySetting.DEFAULTS
            override fun timeContext() = DeliveryClock.current(false, Clock.fixed(now, ZoneOffset.UTC))
            override fun queuePreferences() = emptyMap<String, AppQueuePreference>()
            override fun activeSourceKeys() = platform.active.keys
        })
        private val preferences = Proxy.newProxyInstance(
            Preferences::class.java.classLoader, arrayOf(Preferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isNotificationServiceStatsEnabled" -> true
                "isServiceEnabled" -> mainSwitch
                else -> error(method.name)
            }
        } as Preferences
        val handler = SnoozeHandler(
            platform, events, SnoozeReturnBudget(coordinator::settleSnoozeReturn), rows, { 5 },
            proxyArrival = { coordinator.onArrival(it, it.priority) },
            refreshProxy = { coordinator.refreshSnoozeProxy(it) },
            inputAt = ::input,
        )
        val routing = SnoozeServiceRouting(coordinator, handler, rows::find, ::input)

        suspend fun input(at: Instant) = SnoozeInput(
            packageName = source.packageName, ownPackageName = "farhan", snoozeEnabled = snoozeEnabled,
            filterEnabled = snoozeReturnFilterEnabled(preferences), platformSupportsSnooze = true,
            setting = PriorityDeliverySetting.defaultFor(HIGH), now = at, zone = ZoneOffset.UTC,
            isPassthrough = false, isExceptedPackage = false, priority = HIGH,
            exclusionFlags = SnoozeExclusionFlags.fromNotification(0, null, null),
            scheduleAt = { _, _ -> DEFAULT_SCHEDULE }, alreadyDelivered = false, liveSnoozes = 0,
        )

        suspend fun admit(item: NotificationItem = source, afterAdmission: () -> Unit = {}) {
            val order = coordinator.captureEvent(item.id)
            assertThat(coordinator.withSnoozeAdmission(item.id, order) { live, state ->
                handler.onPosted(item, input(now).copy(ownedRows = live, budget = state)).also { afterAdmission() }
            }).isTrue()
            assertThat(rows.find(item.id)?.snoozeDeadline).isEqualTo(deadline.toEpochMilli())
        }

        suspend fun clearInbox() = coordinator.clearInbox(source.id) { }

        fun visibleOriginal(): NotificationItem {
            platform.hidden.remove(source.id)
            platform.active[source.id] = now.toEpochMilli()
            return source.copy(timestamp = now.toEpochMilli())
        }

        suspend fun returnOriginal(): Boolean =
            routing.settle(SnoozeReturn(source.id, visibleOriginal().timestamp), now)

        suspend fun assertClearedReturnAndReuse() {
            assertThat(platform.canceled).containsExactly(source.id)
            assertThat(rows.find(source.id)?.isCleared).isTrue()
            assertThat(rows.find(source.id)?.snoozeDeadline).isNull()
            assertThat(budget.entries).isEmpty()
            assertThat(events.recent().first().kind).isEqualTo(ExperimentEventKind.REMOVED)
            now = deadline.plusSeconds(1)
            snoozeEnabled = false
            val fresh = visibleOriginal()
            val order = coordinator.captureEvent(source.id)
            assertThat(routing.handleStoredPost(fresh, order, now)).isFalse()
            coordinator.onArrival(fresh, HIGH, order)
            assertThat(rows.find(source.id)?.isCleared).isFalse()
            assertThat(rows.find(source.id)?.timestamp).isEqualTo(fresh.timestamp)
            assertThat(platform.canceled).containsExactly(source.id)
        }
    }

    private class Rows : DeliveryRowStore, SnoozeLedger {
        private val rows = mutableMapOf<String, NotificationItem>()
        override suspend fun find(id: String) = rows[id]
        override suspend fun upsert(item: NotificationItem) { rows[item.id] = item }
        override suspend fun markCleared(id: String) { rows[id] = rows.getValue(id).copy(isCleared = true) }
        override suspend fun markDelivered(id: String) { rows[id] = rows.getValue(id).copy(isDelivered = true) }
        override suspend fun completeSnoozeReturn(id: String) {
            rows[id] = rows.getValue(id).copy(isDelivered = true, snoozeDeadline = null,
                snoozePriority = null, snoozeBootCount = null)
        }
        override suspend fun liveSnoozes() = liveRows()
        override suspend fun liveRows() = rows.values.filter { !it.isCleared && !it.isDelivered && it.snoozeDeadline != null }
        override suspend fun pending(maxPriorityLevel: Int) =
            rows.values.filter { !it.isCleared && !it.isDelivered && it.snoozeDeadline == null && it.priority.level <= maxPriorityLevel }
        override suspend fun invalidateBefore(session: String, order: Long) = Unit
    }

    private class Budget : DeliveryBudgetStore {
        var state = DeliveryBudgetState()
        val entries = mutableListOf<BudgetSpendEntry>()
        override suspend fun read() = state
        override suspend fun write(state: DeliveryBudgetState) { this.state = state }
        override suspend fun appendSpend(entry: BudgetSpendEntry) { entries += entry }
    }

    private class Platform : SnoozePlatform {
        val active = mutableMapOf<String, Long>()
        val hidden = mutableSetOf<String>()
        val canceled = mutableListOf<String>()
        val requests = mutableListOf<Long>()
        override fun activePostTimes() = active.toMap()
        override fun snoozedKeys() = hidden.toSet()
        override fun snooze(key: String, millis: Long) { hidden += key; requests += millis }
        override fun cancelVisible(key: String) { active.remove(key); canceled += key }
    }

    private class Poster : ProxyNotificationPoster {
        val posted = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        override suspend fun post(item: NotificationItem): PostResult { posted += item.id; return PostResult.Posted }
        override suspend fun refresh(item: NotificationItem): PostResult { refreshed += item.id; return PostResult.Posted }
    }
}
