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
        assertThat(rig.routing.handleRemoval(source.id, 12, deadline.plusMillis(2).toEpochMilli(),
            farhanCancelledSummary = false, postTime = deadline.toEpochMilli())).isTrue()
        assertThat(rig.poster.refreshed).isEmpty()
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
        assertThat(rig.budget.entries).hasSize(1)
    }


    // why: S5. Android folds a follow-up into the hidden record of a cleared key and returns the updated record
    //      with no callback, so a cleared return whose text changed is a new message, not the owner's dismissed one.
    // proves: CLAIM(snooze-cleared-return-content)
    // catches: cancelling every cleared return blind (the follow-up is never shown, stored or proxied), or
    //          keeping the row cleared so the batch drops it.
    @Test
    fun `follow-up folded into a cleared snooze is stored and delivered once instead of cancelled`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline
        rig.snoozeEnabled = false

        assertThat(rig.listenerPost(source.title, FOLLOW_UP)).isEqualTo(Route.NORMAL)

        assertThat(rig.platform.canceled).isEmpty()
        val row = rig.rows.find(source.id)!!
        assertThat(row.isCleared).isFalse()
        assertThat(row.text).isEqualTo(FOLLOW_UP)
        assertThat(row.snoozeDeadline).isNull()
        assertThat(rig.budget.entries).isEmpty()
        assertThat(rig.coordinator.onBatchTick(deadline)).containsExactly(DeliveryOutcome.Delivered(source.id))
        assertThat(rig.poster.postedItems.map { it.text }).containsExactly(FOLLOW_UP)
        assertThat(rig.budget.entries).hasSize(1)
        assertThat(rig.coordinator.onBatchTick(deadline)).isEmpty()
        assertThat(rig.budget.entries).hasSize(1)
    }

    // why: with snooze on the follow-up is an ordinary arrival, so it is handed to Android again like any other.
    // catches: leaving the custody columns of the cleared row in place, which makes the pre-check claim the post.
    @Test
    fun `follow-up folded into a cleared snooze is snoozed again as a new arrival`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline

        assertThat(rig.listenerPost(source.title, FOLLOW_UP)).isEqualTo(Route.NORMAL)

        assertThat(rig.platform.canceled).isEmpty()
        val row = rig.rows.find(source.id)!!
        assertThat(row.isCleared).isFalse()
        assertThat(row.text).isEqualTo(FOLLOW_UP)
        assertThat(row.snoozeDeadline).isGreaterThan(deadline.toEpochMilli())
        assertThat(rig.platform.requests).hasSize(2)
        assertThat(rig.budget.entries).isEmpty()
    }

    // why: the title is half of what Farhan compares, and a chat app can rename the conversation title in place.
    // catches: comparing only the text.
    @Test
    fun `a changed title alone makes a cleared return a new arrival`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline
        rig.snoozeEnabled = false

        assertThat(rig.listenerPost("Someone else", source.text)).isEqualTo(Route.NORMAL)

        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.rows.find(source.id)?.isCleared).isFalse()
        assertThat(rig.rows.find(source.id)?.title).isEqualTo("Someone else")
    }

    // why: control for S5. An identical re-post is the owner's dismissed message and keeps today's disposal.
    // catches: delivering what the owner already cleared, or charging for it.
    @Test
    fun `identical cleared return is cancelled and the row stays cleared`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline

        rig.listenerPost(source.title, source.text)

        assertThat(rig.platform.canceled).containsExactly(source.id)
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.budget.entries).isEmpty()
        assertThat(rig.budget.state.spentPoints).isEqualTo(0)
        assertThat(rig.events.recent().first().kind).isEqualTo(ExperimentEventKind.REMOVED)
    }

    // why: after the scan drops the owned entry (or a restart), only the stored row decides, from the post's own fields.
    // catches: the stored path cancelling blind, or the scan-dropped entry being flagged anyway.
    @Test
    fun `restart variant - changed text after a scan dropped the owned entry is delivered`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.handler.onBatchTick(arrival.plusSeconds(1), arrival.minusSeconds(3600))
        assertThat(rig.handler.owns(source.id)).isFalse()
        rig.now = deadline
        rig.snoozeEnabled = false

        assertThat(rig.listenerPost(source.title, FOLLOW_UP)).isEqualTo(Route.NORMAL)

        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.rows.find(source.id)?.isCleared).isFalse()
        assertThat(rig.rows.find(source.id)?.text).isEqualTo(FOLLOW_UP)
        assertThat(rig.coordinator.onBatchTick(deadline)).containsExactly(DeliveryOutcome.Delivered(source.id))
        assertThat(rig.budget.entries).hasSize(1)
    }

    // why: control for the restart variant; the same words are still the cleared return.
    // catches: the restart path treating identical text as new.
    @Test
    fun `restart variant - identical text after a scan dropped the owned entry is cancelled`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.handler.onBatchTick(arrival.plusSeconds(1), arrival.minusSeconds(3600))
        rig.now = deadline

        rig.listenerPost(source.title, source.text)

        assertThat(rig.platform.canceled).containsExactly(source.id)
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.budget.entries).isEmpty()
    }

    // why: the owner's row clear withdraws the coordinator's vouch, and the flag route carries no fields.
    // catches: the listener flagging a cleared key (Work.Return) so the return is disposed without seeing its text.
    @Test
    fun `a cleared key leaves the flag route and a live key keeps it`() = runTest {
        val cleared = Rig()
        cleared.admit()
        cleared.clearInbox()
        cleared.now = deadline
        assertThat(cleared.listenerPost(source.title, source.text)).isEqualTo(Route.STORED_RETURN)

        val live = Rig()
        live.admit()
        live.now = deadline
        assertThat(live.listenerPost(source.title, source.text)).isEqualTo(Route.FLAG)
        assertThat(live.budget.entries).hasSize(1)
        assertThat(live.platform.canceled).isEmpty()
    }

    // why: S5 callers with no fields (stats off sends only key and post time) cannot tell a follow-up from the
    //      cleared return, so they must fail visible.
    // catches: cancelling a cleared return from a caller that never saw its text.
    @Test
    fun `stats-off custody check closes cleared custody and leaves the post visible`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline
        val post = rig.visibleOriginal()

        rig.routing.handleStoredPost(source.id, post.timestamp, rig.now)

        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.platform.active).containsKey(source.id)
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
        assertThat(rig.budget.entries).isEmpty()
    }

    // why: control, the same fieldless entry still settles a live row, so the test above is about cleared rows only.
    // catches: a fieldless caller that stops settling live returns.
    @Test
    fun `stats-off custody check still settles a live return`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.now = deadline
        val post = rig.visibleOriginal()

        assertThat(rig.routing.handleStoredPost(source.id, post.timestamp, rig.now)).isTrue()

        assertThat(rig.budget.entries).hasSize(1)
        assertThat(rig.platform.canceled).isEmpty()
    }

    // why: the reconcile scan only finds an active cleared return when the callback was dropped, and it has no fields.
    // catches: the scan cancelling a visible cleared return it cannot read.
    @Test
    fun `scan closes cleared custody of a visible return and does not cancel it`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.clearInbox()
        rig.now = deadline
        rig.visibleOriginal()

        rig.handler.onBatchTick(deadline.plusSeconds(1), deadline.minusSeconds(3600))

        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.platform.active).containsKey(source.id)
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.rows.find(source.id)?.isCleared).isTrue()
    }

    // why: a clear can land between the listener's flag decision and the drain; the settle has no fields either.
    // catches: the settle cancelling a cleared return from the flag route.
    @Test
    fun `settle of a flagged key cleared in the meantime leaves the post visible`() = runTest {
        val rig = Rig()
        rig.admit()
        rig.now = deadline
        val post = rig.visibleOriginal()
        assertThat(rig.handler.flagIfOwned(source.id, post.timestamp)).isTrue()
        rig.clearInbox()

        rig.routing.settle(SnoozeReturn(source.id, post.timestamp), rig.now)

        assertThat(rig.platform.canceled).isEmpty()
        assertThat(rig.platform.active).containsKey(source.id)
        assertThat(rig.rows.find(source.id)?.snoozeDeadline).isNull()
        assertThat(rig.budget.entries).isEmpty()
    }

    private companion object {
        const val FOLLOW_UP = "Body, and a follow-up"
    }

    private enum class Route { FLAG, STORED_RETURN, NORMAL }

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
            inputAt = { at -> input(at).returnContext() },
        )
        val routing = SnoozeServiceRouting(coordinator, handler, rows::find) { at -> input(at).returnContext() }
        val intake = SnoozeReturnIntake(handler, coordinator::vouchesForSnoozeCustody)

        /**
         * One post as NotificationService sees it: the flag route first (onNotificationPosted), else the stored
         * pre-check with the post's own fields, else the normal arrival. The proxy path's cancel of the source
         * is modelled by dropping it from the visible set without recording a disposal cancel.
         */
        suspend fun listenerPost(title: String?, text: String?): Route {
            val post = visibleOriginal().copy(title = title, text = text)
            var fresh = false
            intake.routePosted(post.id, post.timestamp, offer = { true }) { fresh = true }
            if (!fresh) {
                intake.drain { routing.settle(it, now) }
                return Route.FLAG
            }
            val order = coordinator.captureEvent(post.id)
            if (routing.handleStoredPost(post, order, now)) return Route.STORED_RETURN
            val snoozed = snoozeEnabled && coordinator.withSnoozeAdmission(post.id, order) { live, state ->
                handler.onPosted(post, input(now).copy(ownedRows = live, budget = state))
            }
            if (!snoozed) {
                coordinator.onArrival(post, HIGH, order)
                platform.active.remove(post.id)
            }
            return Route.NORMAL
        }

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
                snoozePriority = null, snoozeBootCount = null, snoozeGroupKey = null)
        }
        override suspend fun liveSnoozes() = liveRows()
        override suspend fun liveRows() = rows.values.filter { it.hasLiveSnooze }
        override suspend fun clearedRows() = rows.values.filter { it.isCleared && it.snoozeDeadline != null }
        override suspend fun clearSourceRemoval(key: String, postTime: Long) {
            val row = rows[key] ?: return
            if (row.timestamp <= postTime) {
                rows[key] = row.copy(isCleared = true, snoozeDeadline = null, snoozePriority = null,
                    snoozeBootCount = null, snoozeGroupKey = null)
            }
        }
        override suspend fun closeClearedSnooze(key: String, deadline: Long) {
            val row = rows[key] ?: return
            if (row.isCleared && row.snoozeDeadline == deadline) {
                rows[key] = row.copy(snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null, snoozeGroupKey = null)
            }
        }
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
        val postedItems = mutableListOf<NotificationItem>()
        override suspend fun post(item: NotificationItem): PostResult { posted += item.id; postedItems += item; return PostResult.Posted }
        override suspend fun refresh(item: NotificationItem): PostResult { refreshed += item.id; return PostResult.Posted }
    }
}
