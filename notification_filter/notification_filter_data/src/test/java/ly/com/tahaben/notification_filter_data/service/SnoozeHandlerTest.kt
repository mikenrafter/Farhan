package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_SNOOZED
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.FAILED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.REMOVED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RESNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RETURNED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.SNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.InMemoryExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import org.junit.Test

class SnoozeHandlerTest {
    private val packageName = "com.whatsapp"
    private val key = "0|com.whatsapp|42"
    private val arrival = Instant.parse("2026-11-20T08:55:00Z")
    private val boundary = Instant.parse("2026-11-20T09:00:00Z")
    private val nextHour = Instant.parse("2026-11-20T10:00:00Z")
    private val nextPeriod = Instant.parse("2026-11-20T12:00:00Z")
    private val input = SnoozeInput(
        packageName = packageName,
        ownPackageName = "ly.com.tahaben.farhan_alpha",
        snoozeEnabled = true,
        filterEnabled = true,
        platformSupportsSnooze = true,
        priority = HIGH,
        setting = PriorityDeliverySetting.defaultFor(HIGH),
        now = arrival,
        zone = ZoneOffset.UTC,
    )
    private val platform = FakeSnoozePlatform()
    private val events = InMemoryExperimentEventLog()
    private val budget = FakeReturnBudget(nextPeriod)
    private val handler = SnoozeHandler(platform, events, budget)

    // why: a fresh eligible arrival hands off to Android until its cadence tick with no charge before delivery.
    // proves: CLAIM(snooze-handled)
    // catches: charging when snooze starts, using a fixed duration, or allowing the proxy path to run.
    @Test
    fun `fresh eligible post snoozes until the next tick without a charge`() = runTest {
        assertThat(handler.onPosted(key, input)).isTrue()

        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(budget.checked).isEmpty()
        assertThat(budget.charges).isEmpty()
        assertThat(events.recent()).containsExactly(event(SNOOZED, arrival))
    }

    // why: the original is already visible on return, so an allowed return should charge once and stay visible.
    // proves: CLAIM(snooze-return-budget), CLAIM(snooze-return-once)
    // catches: authorizing without charging, retaining a charged key, or posting a proxy for the return.
    @Test
    fun `allowed return charges exactly once and leaves the original alone`() = runTest {
        startSnooze()

        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()
        assertThat(handler.onPosted(key, input.copy(now = boundary.plusSeconds(1), snoozeEnabled = false))).isFalse()

        assertThat(budget.checked).containsExactly(returnAt(boundary))
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(budget.state.spentPoints).isEqualTo(2)
        assertThat(budget.state.hourlyCount).isEqualTo(1)
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(event(RETURNED, boundary), event(SNOOZED, arrival)).inOrder()
    }

    // why: changing settings cannot release Android's snooze early or turn its eventual return into a fresh post.
    // proves: CLAIM(snooze-return-budget), CLAIM(snooze-return-once)
    // catches: testing the new toggle, exception, or cadence before checking tracked ownership.
    @Test
    fun `tracked return reaches the budget after snooze off app exemption and cadence change`() = runTest {
        startSnooze()
        val changed = input.copy(
            now = boundary,
            snoozeEnabled = false,
            isExceptedPackage = true,
            setting = input.setting.copy(cadence = DeliveryCadence.IMMEDIATELY),
        )

        assertThat(handler.onPosted(key, changed)).isTrue()
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent().first()).isEqualTo(event(RETURNED, boundary))
    }

    // why: I6 applies before native snooze, independently of how the priority acquired its cadence.
    // proves: CLAIM(snooze-exclusions)
    // catches: a handler that bypasses the policy or only excludes critical priority.
    @Test
    fun `critical and immediate high take the normal path without native snooze`() = runTest {
        assertThat(handler.onPosted(key, input.copy(priority = NotificationPriority.CRITICAL))).isFalse()
        assertThat(handler.onPosted(key, input.copy(setting = input.setting.copy(cadence = DeliveryCadence.IMMEDIATELY)))).isFalse()
        assertThat(platform.attempts).isEmpty()
        assertThat(budget.checked).isEmpty()
        assertThat(events.recent()).isEmpty()

        startSnooze()
    }

    // why: a drained period cannot deliver a return before the next funded period.
    // proves: CLAIM(snooze-return-budget)
    // catches: charging an exhausted budget or retrying at 10:00 while the same exhausted period is active.
    @Test
    fun `spent period resnoozes to the next funded period without a charge`() = runTest {
        budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 9 * 60)
        assertDeniedReturn(delayMillis = 10_800_000L) // 09:00 to 12:00
    }

    // why: the same period can deliver at the next hourly tick once its current hourly count resets.
    // proves: CLAIM(snooze-return-budget)
    // catches: charging over the hourly cap or deferring past the next hour when points remain.
    @Test
    fun `hourly cap resnoozes to the next hourly tick without a charge`() = runTest {
        budget.state = DeliveryBudgetState(hourlyCount = 10, passthroughWindowHour = 9, periodStartMinute = 9 * 60)
        budget.retryAt = nextHour
        assertDeniedReturn(delayMillis = 3_600_000L)
    }

    // why: a zero-point period cannot deliver even with an empty spend ledger.
    // proves: CLAIM(snooze-return-budget)
    // catches: treating zero points as unlimited or retrying inside the same zero-point period.
    @Test
    fun `zero point period resnoozes to the next funded period without a charge`() = runTest {
        budget.schedule = listOf(morning.copy(budgetPoints = 0), afternoon)
        assertDeniedReturn(delayMillis = 10_800_000L)
    }

    // why: a return outside an active period must wait for the next funded period.
    // proves: CLAIM(snooze-return-budget)
    // catches: treating the absence of a period as permission to charge or snoozing for a fixed hour.
    @Test
    fun `no active period resnoozes to the next funded period without a charge`() = runTest {
        budget.schedule = listOf(afternoon)
        assertDeniedReturn(delayMillis = 10_800_000L)
    }

    // why: a second callback in one boundary must not cause a second native call for that key.
    // proves: CLAIM(snooze-return-budget)
    // catches: retrying on every callback, allowing only one retry ever, or dropping the key after its first retry.
    @Test
    fun `repeat callback within a boundary resnoozes at most once and leaves the key tracked`() = runTest {
        budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 9 * 60)
        assertDeniedReturn(delayMillis = 10_800_000L)
        assertThat(handler.onPosted(key, input.copy(now = boundary.plusSeconds(30)))).isTrue()
        assertThat(platform.attempts).hasSize(2)
        assertThat(events.recent()).containsExactly(event(RESNOOZED, boundary), event(SNOOZED, arrival)).inOrder()

        assertThat(handler.onPosted(key, input.copy(now = nextHour))).isTrue()
        assertThat(platform.attempts).hasSize(3)
        assertThat(platform.attempts.last()).isEqualTo(SnoozeAttempt(key, 7_200_000L)) // 10:00 to 12:00
        assertThat(events.recent().first()).isEqualTo(event(RESNOOZED, nextHour))
        assertThat(budget.charges).isEmpty()

        assertThat(handler.onPosted(key, input.copy(now = nextPeriod))).isTrue()
        assertThat(budget.charges).containsExactly(returnAt(nextPeriod))
        assertThat(platform.attempts).hasSize(3)
        assertThat(events.recent().first()).isEqualTo(event(RETURNED, nextPeriod))
    }

    // why: multiple original notifications can return on the same cadence boundary.
    // proves: CLAIM(snooze-return-budget)
    // catches: throttling the whole boundary after the first key retries.
    @Test
    fun `different keys can each resnooze at the same boundary`() = runTest {
        val otherKey = "0|com.whatsapp|43"
        budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 9 * 60)
        startSnooze()
        assertThat(handler.onPosted(otherKey, input)).isTrue()

        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()
        assertThat(handler.onPosted(otherKey, input.copy(now = boundary))).isTrue()
        assertThat(platform.attempts).containsExactly(
            SnoozeAttempt(key, 300_000L),
            SnoozeAttempt(otherKey, 300_000L),
            SnoozeAttempt(key, 10_800_000L),
            SnoozeAttempt(otherKey, 10_800_000L),
        ).inOrder()
        assertThat(budget.charges).isEmpty()
        assertThat(events.recent().take(2)).containsExactly(event(RESNOOZED, boundary), event(RESNOOZED, boundary))
    }

    // why: a denied native call on arrival leaves ordinary delivery intact and creates no tracked return.
    // proves: CLAIM(snooze-handled)
    // catches: swallowing a failed post, leaving a tracked key after failure, or recording failure as success.
    @Test
    fun `platform security failure logs failure and leaves the fresh post unhandled`() = runTest {
        platform.rejectSnooze = true

        assertThat(handler.onPosted(key, input)).isFalse()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(event(FAILED, arrival))

        platform.rejectSnooze = false
        assertThat(handler.onPosted(key, input.copy(now = arrival.plusSeconds(1)))).isTrue()
        assertThat(budget.checked).isEmpty()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, 299_000L)).inOrder()
    }

    // why: pre-26 devices lack timed snooze and must leave the notification to ordinary delivery.
    // catches: invoking an unavailable platform method, swallowing the post, or missing the failure event.
    @Test
    fun `unsupported platform leaves the post unhandled and logs failure`() = runTest {
        assertThat(handler.onPosted(key, input.copy(platformSupportsSnooze = false))).isFalse()
        assertThat(events.recent()).containsExactly(event(FAILED, arrival))
        assertThat(platform.attempts).isEmpty()
        assertThat(budget.checked).isEmpty()
    }

    // why: a replacement listener must charge Android's return even though the original tracker was lost.
    // proves: CLAIM(snooze-return-budget)
    // catches: skipping rehydration or intercepting a return as a new snooze after restart.
    @Test
    fun `new handler rehydrates from Android before charging the return`() = runTest {
        platform.keysAtConnection = setOf(key)
        handler.onListenerConnected()

        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(events.recent()).containsExactly(event(RETURNED, boundary))
        assertThat(platform.attempts).isEmpty()
    }

    // why: the removal Android emits when snooze starts is not a source cancellation.
    // proves: CLAIM(snooze-removal), CLAIM(snooze-return-budget)
    // catches: forwarding the snooze removal, logging it as REMOVED, or losing the key before return.
    @Test
    fun `snooze removal is handled and leaves the return tracked`() = runTest {
        startSnooze()
        val removedAt = arrival.plusSeconds(1)
        assertThat(handler.onRemoved(key, packageName, REASON_SNOOZED, removedAt.toEpochMilli())).isTrue()
        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()

        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(event(RETURNED, boundary), event(SNOOZED, arrival)).inOrder()
    }

    // why: a source cancellation ends a tracked snooze and a subsequent post with that key is fresh.
    // proves: CLAIM(snooze-removal)
    // catches: ignoring a source cancellation or treating its key as a chargeable return.
    @Test
    fun `app cancellation logs removal and drops the key`() = runTest {
        startSnooze()
        val removedAt = arrival.plusSeconds(1)
        handler.onRemoved(key, packageName, REASON_APP_CANCEL, removedAt.toEpochMilli())

        assertThat(events.recent()).containsExactly(
            event(REMOVED, removedAt).copy(removalReason = REASON_APP_CANCEL),
            event(SNOOZED, arrival),
        ).inOrder()
        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, 3_600_000L)).inOrder()
        assertThat(budget.checked).isEmpty()
        assertThat(events.recent().first()).isEqualTo(event(SNOOZED, boundary))
    }

    // why: fresh posts use ordinary delivery when snooze is off (I4).
    // catches: intercepting fresh posts, touching the return budget, or logging events when the owner opted out.
    @Test
    fun `snooze off leaves fresh posts unhandled`() = runTest {
        val freshPosts = listOf(
            input,
            input.copy(filterEnabled = false),
            input.copy(platformSupportsSnooze = false),
            input.copy(packageName = input.ownPackageName),
            input.copy(isExceptedPackage = true),
            input.copy(priority = NotificationPriority.CRITICAL),
            input.copy(setting = input.setting.copy(cadence = DeliveryCadence.IMMEDIATELY)),
            input.copy(isPassthrough = true),
            input.copy(isSystemExemptCategory = true),
            input.copy(isOngoing = true),
            input.copy(isForegroundService = true),
            input.copy(isNonClearable = true),
            input.copy(isGroupSummary = true),
        )
        for ((index, post) in freshPosts.withIndex()) {
            assertThat(handler.onPosted("fresh-$index", post.copy(snoozeEnabled = false))).isFalse()
        }
        assertThat(platform.attempts).isEmpty()
        assertThat(budget.checked).isEmpty()
        assertThat(events.recent()).isEmpty()

        startSnooze()
    }

    private suspend fun startSnooze() {
        assertThat(handler.onPosted(key, input)).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
    }

    private suspend fun assertDeniedReturn(delayMillis: Long) {
        assertThat(budget.canDeliver(HIGH, boundary)).isFalse()
        assertThat(budget.canDeliver(HIGH, budget.retryAt)).isTrue()
        startSnooze()

        assertThat(handler.onPosted(key, input.copy(now = boundary))).isTrue()
        assertThat(budget.checked).containsExactly(returnAt(boundary))
        assertThat(budget.charges).isEmpty()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, delayMillis)).inOrder()
        assertThat(events.recent()).containsExactly(event(RESNOOZED, boundary), event(SNOOZED, arrival)).inOrder()
    }

    private fun event(kind: ExperimentEventKind, at: Instant) =
        ExperimentEvent(at.toEpochMilli(), kind, packageName = packageName)

    private fun returnAt(at: Instant) = Return(key, packageName, HIGH, at)

    private data class Return(val key: String, val packageName: String, val priority: NotificationPriority, val at: Instant)

    private class FakeReturnBudget(var retryAt: Instant) : SnoozeReturnBudget {
        var schedule = listOf(morning, afternoon)
        var state = DeliveryBudgetState()
        val checked = mutableListOf<Return>()
        val charges = mutableListOf<Return>()

        private fun timeAt(at: Instant) = DeliveryClock.current(false, Clock.fixed(at, ZoneOffset.UTC))

        fun canDeliver(priority: NotificationPriority, at: Instant): Boolean =
            NotificationDeliveryPolicy.canDeliverNow(priority, PriorityDeliverySetting.defaultFor(priority), schedule, timeAt(at), state).canDeliver

        override suspend fun settleReturn(key: String, packageName: String, priority: NotificationPriority, at: Instant): SnoozeReturnDecision {
            val returned = Return(key, packageName, priority, at)
            checked += returned
            val setting = PriorityDeliverySetting.defaultFor(priority)
            val time = timeAt(at)
            val decision = NotificationDeliveryPolicy.canDeliverNow(priority, setting, schedule, time, state)
            if (!decision.canDeliver) return SnoozeReturnDecision.RetryAt(retryAt)

            state = NotificationDeliveryPolicy.recordPassthrough(priority, setting, schedule, time.scheduleMinuteOfDay, decision.updatedBudget)
            charges += returned
            return SnoozeReturnDecision.Charged
        }
    }

    private data class SnoozeAttempt(val key: String, val millis: Long)

    private class FakeSnoozePlatform : SnoozePlatform {
        val attempts = mutableListOf<SnoozeAttempt>()
        var keysAtConnection: Set<String> = emptySet()
        var rejectSnooze = false

        override fun snooze(key: String, millis: Long) {
            attempts += SnoozeAttempt(key, millis)
            if (rejectSnooze) throw SecurityException("listener permission revoked")
        }

        override fun snoozedKeys(): Set<String> = keysAtConnection
    }

    private companion object {
        val morning = NotificationSchedulePeriod(9, 0, 12, 0, 8)
        val afternoon = NotificationSchedulePeriod(12, 0, 24, 0, 8)
    }
}
