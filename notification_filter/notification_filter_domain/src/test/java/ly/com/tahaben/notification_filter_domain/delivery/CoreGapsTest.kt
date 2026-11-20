package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.model.*
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.schedule.threeWindows
import ly.com.tahaben.notification_filter_domain.testsupport.item
import org.junit.Test

class CoreGapsTest {
    // why: production queries one app head at a time; the coordinator must refill that head after each delivery.
    // catches: treating the bounded head snapshot as the whole batch or draining one app before comparing heads.
    @Test fun `bounded row heads refill and interleave FIFO with newest first`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        h.environment.preferences = mapOf("fifo" to AppQueuePreference(QueueOrder.OLDEST_FIRST))
        val candidates = listOf(item("f1", 100, "fifo"), item("f2", 300, "fifo"), item("n1", 200, "new"), item("n2", 250, "new"))
        candidates.forEach { h.rows.upsert(it) }
        val rows = object : DeliveryRowStore by h.rows {
            override suspend fun pending(maxPriorityLevel: Int) = listOf(candidates[0], candidates[3])
            override suspend fun nextHead(after: NotificationItem): NotificationItem? = when (after.id) {
                "n2" -> candidates[2]
                "f1" -> candidates[1]
                else -> null
            }
        }
        NotificationDeliveryCoordinator(rows, h.budget, h.poster, h.environment).onBatchTick()
        assertThat(h.poster.posted.map { it.id }).containsExactly("n2", "n1", "f1", "f2").inOrder()
        assertThat(h.budget.state.spentPoints).isEqualTo(8)
    }

    // why: a failed delivered-marker write after the external effect must recover storage without alerting twice.
    // catches: retaining only budget failures, or forgetting a successful post while its row still looks pending.
    @Test fun `failed row marker after successful post recovers without a second post`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        var fail = true
        val rows = object : DeliveryRowStore by h.rows {
            override suspend fun markDelivered(id: String) {
                if (fail) { fail = false; throw IllegalStateException("disk") }
                h.rows.markDelivered(id)
            }
        }
        val coordinator = NotificationDeliveryCoordinator(rows, h.budget, h.poster, h.environment)
        coordinator.onArrival(item("K", 100), HIGH)
        assertThat(coordinator.onBatchTick()).containsExactly(DeliveryOutcome.CommitPending("K"))
        coordinator.onBatchTick()
        assertThat(h.poster.posted.map { it.id }).containsExactly("K")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries).hasSize(1)
    }

    // why: a source cancel is a new lifetime even if its queued removal handler runs after the repost handler.
    // catches: overwriting a removal token with the next arrival and incorrectly treating the repost as a free update.
    @Test fun `delivered key repost after callback cancellation is a new charged lifetime`() = runTest {
        val h = DeliveryHarness(threeWindows(8), PriorityDeliverySetting.DEFAULTS + (HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING)))
        val first = h.coordinator.captureEvent("K")
        h.coordinator.onArrival(item("K", 100), HIGH, first)
        val removed = h.coordinator.captureEvent("K", true)
        val reposted = h.coordinator.captureEvent("K")
        h.coordinator.onArrival(item("K", 1), HIGH, reposted)
        h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, 900, removed)
        assertThat(h.poster.posted.map { it.id }).containsExactly("K", "K")
        assertThat(h.budget.state.spentPoints).isEqualTo(4)
        assertThat(h.rows.rows.getValue("K").isCleared).isFalse()
    }

    // why: a full intake can leave a newer update in the source tray; an obsolete callback must not intercept it.
    // catches: storing the old text or posting a pending proxy while the source key remains visible.
    @Test fun `source version guard and live tray defer obsolete intake and pending proxies`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        var visible = emptySet<String>()
        val environment = object : DeliveryEnvironment by h.environment { override fun activeSourceKeys() = visible }
        val coordinator = NotificationDeliveryCoordinator(h.rows, h.budget, h.poster, environment)
        val rejected = coordinator.onArrival(item("old", 100), HIGH, sourceStillCurrent = { false })
        assertThat(rejected).isEqualTo(DeliveryOutcome.Deferred("old"))
        assertThat(h.rows.rows).isEmpty()
        coordinator.onArrival(item("K", 100), HIGH)
        visible = setOf("K")
        coordinator.onBatchTick()
        assertThat(h.poster.posted).isEmpty()
        assertThat(h.budget.state.spentPoints).isEqualTo(0)
        assertThat(h.rows.pendingIds()).containsExactly("K")
    }

    // why: a clear in Farhan must suppress work captured earlier, even when its row did not exist at clear time.
    // catches: deleting only the row, leaving an older callback free to recreate and deliver it.
    @Test fun `single inbox clear and clear all invalidate earlier callbacks`() = runTest {
        val settings = PriorityDeliverySetting.DEFAULTS + (HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING))
        val h = DeliveryHarness(threeWindows(8), settings)
        val first = h.coordinator.captureEvent("K")
        val second = h.coordinator.captureEvent("J")
        h.coordinator.markCleared("K") { h.rows.rows.remove("K") }
        assertThat(h.coordinator.onArrival(item("K", 100), HIGH, first)).isEqualTo(DeliveryOutcome.Cleared("K"))
        h.coordinator.clearAll { h.rows.rows.clear() }
        assertThat(h.coordinator.onArrival(item("J", 200), HIGH, second)).isEqualTo(DeliveryOutcome.Cleared("J"))
        assertThat(h.poster.posted).isEmpty()
        assertThat(h.budget.state.spentPoints).isEqualTo(0)
    }

    // why: clear-all may run while Room is returning an old pending snapshot; absent rows are never authorized.
    // catches: cached-list delivery after deletion, and clear-all bypassing the same decision owner.
    @Test fun `clear all during batch cannot post after clear returned`() = runTest {
        val h = DeliveryHarness(threeWindows(8))
        h.coordinator.onArrival(item("K", 100), HIGH)
        h.rows.pendingGate = CompletableDeferred()
        val readGate = h.rows.pendingGate!!
        val batch = async { h.coordinator.onBatchTick() }
        runCurrent()
        val clear = async { h.coordinator.clearAll { h.rows.rows.clear(); h.log.add("clear-all") } }
        runCurrent()
        readGate.complete(Unit)
        batch.await(); clear.await()
        val post = h.log.events.indexOf("post:K")
        assertThat(post == -1 || post < h.log.events.indexOf("clear-all")).isTrue()
        assertThat(h.rows.rows).isEmpty()
        assertThat(h.budget.state.spentPoints).isEqualTo(h.poster.posted.size * 2)
    }

    // why: the external post succeeded, so a storage retry must never post or charge that effect a second time.
    // catches: budget-before-delivered ordering, and abandoning the failed accounting write with a pending row.
    @Test fun `budget write failure after post retries accounting without posting twice`() = runTest {
        val rows = FakeRowStore()
        val budget = FakeBudgetStore()
        var fail = true
        val failing = object : DeliveryBudgetStore {
            override suspend fun read() = budget.read()
            override suspend fun write(state: DeliveryBudgetState) {
                if (fail) { fail = false; throw IllegalStateException("disk") }
                budget.write(state)
            }
            override suspend fun appendSpend(entry: BudgetSpendEntry) = budget.appendSpend(entry)
        }
        val poster = FakePoster(EventLog())
        val coordinator = NotificationDeliveryCoordinator(rows, failing, poster, FakeEnvironment(threeWindows(8), PriorityDeliverySetting.DEFAULTS))
        coordinator.onArrival(item("K", 100), HIGH)
        runCatching { coordinator.onBatchTick() }
        coordinator.onBatchTick()
        assertThat(poster.posted.map { it.id }).containsExactly("K")
        assertThat(rows.rows.getValue("K").isDelivered).isTrue()
        assertThat(budget.state.spentPoints).isEqualTo(2)
        assertThat(budget.entries).hasSize(1)
    }

    // why: updates refresh proxy content without another alert or budget spend, through the coordinator's poster port.
    // catches: routing a delivered update through the alerting post operation.
    @Test fun `delivered update uses the silent refresh operation`() = runTest {
        val rows = FakeRowStore()
        val budget = FakeBudgetStore()
        val posted = mutableListOf<NotificationItem>()
        val refreshed = mutableListOf<NotificationItem>()
        val poster = object : ProxyNotificationPoster {
            override suspend fun post(item: NotificationItem): PostResult { posted += item; return PostResult.Posted }
            override suspend fun refresh(item: NotificationItem): PostResult { refreshed += item; return PostResult.Posted }
        }
        val coordinator = NotificationDeliveryCoordinator(rows, budget, poster, FakeEnvironment(threeWindows(8), PriorityDeliverySetting.DEFAULTS))
        coordinator.onArrival(item("K", 100, text = "v1"), HIGH)
        coordinator.onBatchTick()
        coordinator.onArrival(item("K", 200, text = "v2"), HIGH)
        assertThat(posted.map { it.text }).containsExactly("v1")
        assertThat(refreshed.map { it.text }).containsExactly("v2")
        assertThat(budget.state.spentPoints).isEqualTo(2)
    }

    // why: intake overflow is visible synchronously, allowing the listener to leave that arrival in its source tray.
    // catches: an unbounded queue that accepts the overflow event, or dropping already accepted work.
    @Test fun `bounded intake refuses overflow and consumes accepted fields in order`() = runTest {
        val intake = ListenerIntake<String>(2)
        assertThat(intake.offer("first")).isTrue()
        assertThat(intake.offer("second")).isTrue()
        assertThat(intake.offer("overflow")).isFalse()
        intake.close()
        val consumed = mutableListOf<String>()
        intake.consume { consumed += it }
        assertThat(consumed).containsExactly("first", "second").inOrder()
    }

    // why: actions belong to live relevant keys; clearing one/all must release them without touching queued neighbors.
    // catches: unrelated-key removal, no-op clear, stale update content, and unbounded/never-expiring capabilities.
    @Test fun `action store replaces releases bounds and expires capabilities`() {
        var now = 0L
        val store = NotificationActionStore<String>(2, 100) { now }
        store.put("queued", "reply1"); store.put("other", "tap")
        store.put("queued", "reply2"); store.remove("other")
        assertThat(store.get("queued")).isEqualTo("reply2")
        assertThat(store.get("other")).isNull()
        store.put("new", "n"); store.put("newer", "nn")
        assertThat(store.size()).isEqualTo(2)
        assertThat(store.get("queued")).isNull()
        now = 100
        assertThat(store.size()).isEqualTo(0)
        store.put("again", "a"); store.clear()
        assertThat(store.size()).isEqualTo(0)
    }
}
