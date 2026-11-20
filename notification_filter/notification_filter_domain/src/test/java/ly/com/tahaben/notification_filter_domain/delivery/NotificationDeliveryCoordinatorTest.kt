package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryOutcome.Cleared
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryOutcome.Deferred
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryOutcome.Delivered
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.notification_filter_domain.testsupport.item
import ly.com.tahaben.notification_filter_domain.testsupport.productionContext
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test

/**
 * Behavior of the one delivery coordinator, driven through its production entry points
 * (onArrival, onRemoved, onBatchTick). Only the ports (Room, budget storage, NotificationManager)
 * are fakes, and the row fake copies Room's REPLACE / no-op UPDATE semantics.
 *
 * UNWIRED: NotificationService and NotificationDeliveryEngine do not call this class yet. These tests
 * prove the coordinator; they do not prove the Android adapters route through it.
 *
 * Cost model (NotificationPriority.pointCost): HIGH 2, MEDIUM 4. Defaults: HIGH batches every hour.
 * Time is 10:00, inside the 09:00-12:00 window unless a test says otherwise.
 *
 * proves: CLAIM(cleared-before-delivery-free), CLAIM(serialized-budget), CLAIM(post-failure-no-spend)
 */
class NotificationDeliveryCoordinatorTest {

    private val dayWindows = listOf(window(0, 540, 0), window(540, 720, 8), window(720, 1440, 0))

    private val immediateHigh = PriorityDeliverySetting.DEFAULTS +
        (HIGH to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING))

    // why: CLAIM(scoped-delivery-clear) discards stale arrivals for cleared ids and preserves other pending deliveries.
    // catches: global invalidation, evicting High to track an older Low, or allowing a cleared row's stale callback.
    @Test
    fun `clearing Low preserves pending High and rejects a stale Low callback`() = runTest {
        val h = DeliveryHarness(dayWindows)
        val coordinator = NotificationDeliveryCoordinator(h.rows, h.budget, h.poster, h.environment, eventCapacity = 2)
        val high = item("high", timestamp = 200, priority = HIGH)
        val low = item("low", timestamp = 100, priority = LOW)
        assertThat(coordinator.onArrival(high, HIGH, coordinator.captureEvent(high.id))).isEqualTo(Deferred(high.id))
        assertThat(coordinator.onArrival(low, LOW, coordinator.captureEvent(low.id))).isEqualTo(Deferred(low.id))
        val staleLow = coordinator.captureEvent(low.id)

        val olderLow = item("older-low", timestamp = 50, priority = LOW)
        h.rows.upsert(olderLow)
        coordinator.clearScoped(
            delete = {
                h.rows.rows.remove(low.id)
                h.rows.rows.remove(olderLow.id)
                listOf(low.id, olderLow.id)
            },
            clearActions = { },
        )

        assertThat(coordinator.onArrival(low, LOW, staleLow)).isEqualTo(Cleared(low.id))
        assertThat(h.rows.rows.keys).containsExactly(high.id)
        assertThat(coordinator.onBatchTick()).containsExactly(Delivered(high.id))
        assertThat(h.poster.posted.map { it.id }).containsExactly(high.id)
    }

    // why: CLAIM(scoped-delivery-clear) preserves a fresh arrival captured while the delete is in progress.
    // catches: removing the event.order <= clearOrder guard, which invalidates the fresh callback.
    @Test
    fun `callback captured during scoped delete can deliver afterward`() = runTest {
        val h = DeliveryHarness(dayWindows, immediateHigh)
        val id = "reposted"
        val old = item(id, timestamp = 100, priority = LOW)
        assertThat(h.coordinator.onArrival(old, LOW, h.coordinator.captureEvent(id))).isEqualTo(Deferred(id))
        var freshOrder = 0L

        h.coordinator.clearScoped(
            delete = {
                h.rows.rows.remove(id)
                freshOrder = h.coordinator.captureEvent(id)
                listOf(id)
            },
            clearActions = {},
        )

        val fresh = item(id, timestamp = 200, priority = HIGH)
        assertThat(freshOrder).isGreaterThan(0L)
        assertThat(h.coordinator.onArrival(fresh, HIGH, freshOrder)).isEqualTo(Delivered(id))
        assertThat(h.poster.posted.map { it.id }).containsExactly(id)
    }

    // why: a source app posts then cancels in quick succession; the removal callback's coroutine can run before
    //      the posting coroutine. A REPLACE upsert of the late arrival would resurrect the row and spend on it.
    // catches: arrival that upserts and delivers without consulting earlier removals; permanent-or-absent tombstone.
    // proves: CLAIM(cleared-before-delivery-free). Hand math: only J posts: 1 x HIGH = 2 points.
    @Test
    fun `arrival processed after an explicit cancellation costs nothing and stays cleared`() = runTest {
        val h = DeliveryHarness(dayWindows, immediateHigh)

        h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, atMillis = 1_000)
        val stale = h.coordinator.onArrival(item("K", timestamp = 900), HIGH)
        val control = h.coordinator.onArrival(item("J", timestamp = 1_500), HIGH)

        assertThat(stale).isEqualTo(Cleared("K"))
        assertThat(control).isEqualTo(Delivered("J"))
        assertThat(h.poster.posted.map { it.id }).containsExactly("J")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries.map { it.title }).containsExactly("J")
        assertThat(h.rows.pendingIds()).isEmpty()
    }

    // why: the same key posted again AFTER a cancellation is a new notification, not the stale one.
    // catches: a tombstone that never expires, so a genuine repost is silently dropped.
    @Test
    fun `repost after a cancellation is delivered as a new notification`() = runTest {
        val h = DeliveryHarness(dayWindows, immediateHigh)

        h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, atMillis = 1_000)
        val outcome = h.coordinator.onArrival(item("K", timestamp = 1_100), HIGH)

        assertThat(outcome).isEqualTo(Delivered("K"))
        assertThat(h.poster.posted.map { it.id }).containsExactly("K")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
    }

    // why: a pending row the owner cancelled must never be posted or charged by the next batch.
    // catches: batch that works from a cached list; delete-vs-clear mixup; clear that is overwritten by the next upsert.
    // proves: CLAIM(cleared-before-delivery-free). Hand math: only J (2 points) is spent.
    @Test
    fun `cancelling a pending row before the batch costs nothing`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("K", timestamp = 100), HIGH)
        h.coordinator.onArrival(item("J", timestamp = 200), HIGH)

        h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, atMillis = 1_000)
        h.coordinator.onBatchTick()

        assertThat(h.poster.posted.map { it.id }).containsExactly("J")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries.map { it.title }).containsExactly("J")
        val cleared = h.rows.rows.getValue("K")
        assertThat(cleared.isCleared).isTrue()
        assertThat(cleared.isDelivered).isFalse()
    }

    // why: the batch reads its snapshot, then a cancellation completes, then the batch authorizes. The invariant
    //      is "no post of K after K's cancellation returned"; posting K before the cancel returned is also legal
    //      (serialized order), so this asserts the invariant, not one particular order.
    // catches: snapshot read outside the serialization point and not rechecked at authorization.
    // proves: CLAIM(cleared-before-delivery-free). Positive control: J is still posted and charged.
    @Test
    fun `batch cannot post a key whose cancellation completed during its snapshot read`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("K", timestamp = 100), HIGH)
        h.coordinator.onArrival(item("J", timestamp = 200), HIGH)
        val gate = CompletableDeferred<Unit>()
        h.rows.pendingGate = gate

        val batch = async { h.coordinator.onBatchTick() }
        runCurrent()
        val clear = async {
            h.coordinator.onRemoved("K", NotificationRemovalReason.SOURCE_APP_CANCELLED, atMillis = 1_000)
            h.log.add("clear-complete:K")
        }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        batch.await()
        clear.await()

        val events = h.log.events
        val clearAt = events.indexOf("clear-complete:K")
        val postK = events.indexOf("post:K")
        assertThat(clearAt).isAtLeast(0)
        assertWithMessage("K was posted after its cancellation completed: $events")
            .that(postK == -1 || postK < clearAt).isTrue()
        assertThat(h.poster.posted.map { it.id }).contains("J")
        assertThat(h.budget.state.spentPoints).isEqualTo(2 * h.poster.posted.size)
        assertThat(h.budget.entries).hasSize(h.poster.posted.size)
        assertThat(h.rows.pendingIds()).isEmpty()
    }

    // why: owner decision: clearing a delivered notification does not refund spending.
    // catches: clear handler that rolls back spend or the ledger entry.
    @Test
    fun `cancelling a delivered notification keeps its spend`() = runTest {
        val h = DeliveryHarness(dayWindows, immediateHigh)
        h.coordinator.onArrival(item("K", timestamp = 100), HIGH)

        h.coordinator.onRemoved("K", NotificationRemovalReason.USER_DISMISSED, atMillis = 2_000)

        assertThat(h.poster.posted.map { it.id }).containsExactly("K")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries).hasSize(1)
        assertThat(h.rows.rows.getValue("K").isCleared).isTrue()
    }

    // why: Farhan cancels the original itself when it intercepts, and opening the source app is not a cancellation.
    //      Neither may drop a queued row (owner decision: only explicit cancellation clears).
    // catches: removal handler that clears on any listener callback; clear-on-app-open.
    // Hand math: both rows post newest first (K2 ts200, K1 ts100), 2 x HIGH = 4 points.
    @Test
    fun `Farhan interception and opening the source app do not clear a pending row`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("K1", timestamp = 100), HIGH)
        h.coordinator.onArrival(item("K2", timestamp = 200), HIGH)

        h.coordinator.onRemoved("K1", NotificationRemovalReason.FARHAN_INTERCEPTED, atMillis = 1_000)
        h.coordinator.onRemoved("K2", NotificationRemovalReason.SOURCE_APP_OPENED, atMillis = 1_000)
        h.coordinator.onBatchTick()

        assertThat(h.poster.posted.map { it.id }).containsExactly("K2", "K1").inOrder()
        assertThat(h.budget.state.spentPoints).isEqualTo(4)
    }

    // why: two arrivals race for the last allowance; each reading the balance before the other writes overspends it.
    // catches: read-decide-post-write without one serialization point. Window budget 2 = exactly one HIGH.
    // proves: CLAIM(serialized-budget)
    @Test
    fun `two overlapping immediate arrivals spend one allowance once`() = runTest {
        val h = DeliveryHarness(listOf(window(0, 540, 0), window(540, 720, 2), window(720, 1440, 0)), immediateHigh)
        val gate = CompletableDeferred<Unit>()
        h.poster.gate = gate

        val a = async { h.coordinator.onArrival(item("A", timestamp = 100), HIGH) }
        val b = async { h.coordinator.onArrival(item("B", timestamp = 200), HIGH) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(listOf(a.await(), b.await())).containsExactly(Delivered("A"), Deferred("B")).inOrder()
        assertThat(h.poster.posted.map { it.id }).containsExactly("A")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries).hasSize(1)
        assertThat(h.rows.pendingIds()).containsExactly("B")
    }

    // why: an immediate arrival and the hourly batch can run together; they must share one balance.
    // catches: separate immediate and batch paths each authorizing against the same stored spend.
    // Hand math: window budget 4; MEDIUM immediate costs 4, queued HIGH costs 2; 4+2 > 4 so only M posts.
    // proves: CLAIM(serialized-budget)
    @Test
    fun `an arrival overlapping a batch cannot overspend the window`() = runTest {
        val settings = PriorityDeliverySetting.DEFAULTS +
            (MEDIUM to PriorityDeliverySetting(DeliveryCadence.IMMEDIATELY, BudgetBehavior.RESPECTING))
        val h = DeliveryHarness(listOf(window(0, 540, 0), window(540, 720, 4), window(720, 1440, 0)), settings)
        h.coordinator.onArrival(item("H", timestamp = 50, priority = HIGH), HIGH)
        val gate = CompletableDeferred<Unit>()
        h.poster.gate = gate

        val arrival = async { h.coordinator.onArrival(item("M", timestamp = 100, priority = MEDIUM), MEDIUM) }
        runCurrent()
        val batch = async { h.coordinator.onBatchTick() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(arrival.await()).isEqualTo(Delivered("M"))
        assertThat(batch.await().filterIsInstance<Delivered>()).isEmpty()
        assertThat(h.poster.posted.map { it.id }).containsExactly("M")
        assertThat(h.budget.state.spentPoints).isEqualTo(4)
        assertThat(h.rows.pendingIds()).containsExactly("H")
    }

    // why: a failed post must not be reported as delivered, mark the row delivered, or spend; the allowance
    //      must survive so a later retry delivers once.
    // catches: markDelivered/spend before the post; swallowing a Failed result; a failure that burns the row.
    // proves: CLAIM(post-failure-no-spend). Hand math: failed attempt 0 points; retry 2 points, 1 ledger entry.
    @Test
    fun `a failed post is reported as failed, spends nothing and retries once`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("K", timestamp = 100), HIGH)
        h.poster.mode = FakePoster.Mode.FAIL

        val failed = h.coordinator.onBatchTick()

        assertThat(failed).containsExactly(DeliveryOutcome.PostFailed("K", "blocked"))
        assertThat(h.budget.state.spentPoints).isEqualTo(0)
        assertThat(h.budget.entries).isEmpty()
        assertThat(h.rows.pendingIds()).containsExactly("K")

        h.poster.mode = FakePoster.Mode.OK
        val retried = h.coordinator.onBatchTick()

        assertThat(retried).containsExactly(Delivered("K"))
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries).hasSize(1)
        assertThat(h.poster.posted.map { it.id }).containsExactly("K")
    }

    // why: NotificationManager can throw (revoked permission). That must surface as a failure outcome, not a
    //      crash that leaves the lock held, and not as a success.
    // catches: no try/catch around the post; success reported before the post returns.
    @Test
    fun `a post that throws is not delivered, not spent and does not wedge the coordinator`() = runTest {
        val h = DeliveryHarness(dayWindows, immediateHigh)
        h.poster.mode = FakePoster.Mode.THROW

        val outcome = h.coordinator.onArrival(item("K", timestamp = 100), HIGH)

        assertThat(outcome).isInstanceOf(DeliveryOutcome.PostFailed::class.java)
        assertThat(h.budget.state.spentPoints).isEqualTo(0)
        assertThat(h.rows.pendingIds()).containsExactly("K")

        h.poster.mode = FakePoster.Mode.OK
        val next = h.coordinator.onArrival(item("J", timestamp = 200), HIGH)

        assertThat(next).isEqualTo(Delivered("J"))
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
    }

    // why: owner decision: a delivered key must not spend again when its contents update. Room REPLACE resets
    //      isDelivered to false, so a naive upsert makes the next batch deliver and charge it again.
    // catches: update path that upserts the incoming item verbatim.
    // Hand math: spend stays 2 (one HIGH) with one ledger entry; the row keeps the new text.
    @Test
    fun `updating a delivered key does not spend or redeliver it`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("K", timestamp = 100, text = "v1"), HIGH)
        h.coordinator.onBatchTick()
        assertThat(h.budget.state.spentPoints).isEqualTo(2)

        h.coordinator.onArrival(item("K", timestamp = 300, text = "v2"), HIGH)
        h.environment.time = productionContext(11)
        val second = h.coordinator.onBatchTick()

        assertThat(second.filterIsInstance<Delivered>()).isEmpty()
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.budget.entries).hasSize(1)
        assertThat(h.rows.rows.getValue("K").text).isEqualTo("v2")
    }

    // why: owner decisions: an update replaces a pending notification's contents and moves it by its new
    //      recency; distinct older notifications are retained.
    // catches: update that keeps the old timestamp (a2 would go first); one-row-per-app collapse (a2 lost).
    // Hand math: a1 re-arrives at ts300 as v2; newest first gives a1(300) then a2(200); 2 x HIGH = 4 points.
    @Test
    fun `a pending update jumps to its new recency and distinct notifications are kept`() = runTest {
        val h = DeliveryHarness(dayWindows)
        h.coordinator.onArrival(item("a1", timestamp = 100, text = "v1"), HIGH)
        h.coordinator.onArrival(item("a2", timestamp = 200), HIGH)
        h.coordinator.onArrival(item("a1", timestamp = 300, text = "v2"), HIGH)

        h.coordinator.onBatchTick()

        assertThat(h.poster.posted.map { it.id }).containsExactly("a1", "a2").inOrder()
        assertThat(h.poster.posted.first().text).isEqualTo("v2")
        assertThat(h.budget.state.spentPoints).isEqualTo(4)
    }

    // why: owner decision: notification priority outranks per-app queue preference. A FIFO, high-rank app with a
    //      lower-priority item must not bypass a higher-priority item, even though the budget fits only one.
    // catches: rank or FIFO applied before priority. Hand math: window budget 4; n1 HIGH costs 2, f1 MEDIUM costs 4.
    //      Priority first: n1 (2), then f1 would need 6 > 4, skipped. Rank first would post f1 (4) and then skip n1.
    @Test
    fun `a FIFO high-rank app cannot bypass a higher-priority item`() = runTest {
        val h = DeliveryHarness(listOf(window(0, 540, 0), window(540, 720, 4), window(720, 1440, 0)))
        h.environment.preferences = mapOf("com.fifo" to AppQueuePreference(QueueOrder.OLDEST_FIRST, rank = 5))
        h.coordinator.onArrival(item("f1", timestamp = 10, packageName = "com.fifo", priority = MEDIUM), MEDIUM)
        h.coordinator.onArrival(item("n1", timestamp = 999, packageName = "com.n", priority = HIGH), HIGH)

        h.coordinator.onBatchTick()

        assertThat(h.poster.posted.map { it.id }).containsExactly("n1")
        assertThat(h.budget.state.spentPoints).isEqualTo(2)
        assertThat(h.rows.pendingIds()).containsExactly("f1")
    }
}
