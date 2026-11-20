package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService.REASON_SNOOZED
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.FAILED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.LOST
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.REMOVED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RESNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RETURNED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.SNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.InMemoryExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeExclusionFlags
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturnDecision
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturn
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeFallbackReason
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeFallbackReason.*
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
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
        isPassthrough = false,
        isExceptedPackage = false,
        exclusionFlags = SnoozeExclusionFlags.fromNotification(0, null, null),
        scheduleAt = { _, _ -> listOf(morning, afternoon) },
        alreadyDelivered = false,
        liveSnoozes = 0,
    )
    private val platform = FakeSnoozePlatform { fallbackOrder += "cancel:$it" }
    private val events = InMemoryExperimentEventLog()
    private val ledger = FakeLedger()
    private val budget = FakeReturnBudget(nextPeriod, ledger)
    private val fallback = mutableListOf<NotificationItem>()
    private val refreshed = mutableListOf<NotificationItem>()
    private val fallbackOrder = mutableListOf<String>()
    private val handler = newHandler()

    private fun newHandler(bootCount: Int = 10) =
        SnoozeHandler(platform, events, budget, ledger, { bootCount },
            proxyArrival = { fallback += it; fallbackOrder += "arrive:${it.id}" },
            refreshProxy = { refreshed += it },
            inputAt = { input.copy(now = it) },
        )

    private fun item(key: String, input: SnoozeInput) = NotificationItem(
        id = key, appName = "WhatsApp", title = "A title", text = "A body", time = "",
        packageName = input.packageName, timestamp = input.now.toEpochMilli(), priority = input.priority,
    )

    private suspend fun post(key: String, input: SnoozeInput, target: SnoozeHandler = handler): Boolean {
        return target.onPosted(item(key, input), input)
    }

    private suspend fun returnPost(key: String, input: SnoozeInput, target: SnoozeHandler = handler): Boolean {
        platform.returned(key)
        platform.active[key] = input.now.toEpochMilli()
        return target.settle(SnoozeReturn(key, input.now.toEpochMilli()), input)
    }

    // why: a fresh eligible arrival hands off to Android until its cadence tick with no charge before delivery.
    // proves: CLAIM(snooze-handled)
    // catches: charging when snooze starts, using a fixed duration, or allowing the proxy path to run.
    @Test
    fun `fresh eligible post snoozes until the next tick without a charge`() = runTest {
        assertThat(post(key, input)).isTrue()

        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(budget.checked).isEmpty()
        assertThat(budget.charges).isEmpty()
        assertThat(events.recent()).containsExactly(event(SNOOZED, arrival))
        val saved = ledger.rows[key]
        assertThat(saved).isEqualTo(
            item(key, input).copy(snoozeDeadline = boundary.toEpochMilli(), snoozePriority = HIGH, snoozeBootCount = 10),
        )
    }

    // why: the original is already visible on return, so an allowed return should charge once and stay visible.
    // proves: CLAIM(snooze-return-budget), CLAIM(snooze-return-once)
    // catches: authorizing without charging, retaining a charged key, or posting a proxy for the return.
    @Test
    fun `allowed return charges exactly once and leaves the original alone`() = runTest {
        startSnooze()

        assertThat(returnPost(key, input.copy(now = boundary))).isTrue()
        assertThat(post(key, input.copy(now = boundary.plusSeconds(1), snoozeEnabled = false))).isFalse()

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

        assertThat(returnPost(key, changed)).isTrue()
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent().first()).isEqualTo(event(RETURNED, boundary))
    }

    // why: I6 applies before native snooze, independently of how the priority acquired its cadence.
    // proves: CLAIM(snooze-exclusions)
    // catches: a handler that bypasses the policy or only excludes critical priority.
    @Test
    fun `critical and immediate high take the normal path without native snooze`() = runTest {
        assertThat(post(key, input.copy(priority = NotificationPriority.CRITICAL))).isFalse()
        assertThat(post(key, input.copy(setting = input.setting.copy(cadence = DeliveryCadence.IMMEDIATELY)))).isFalse()
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

    // why: a second callback in one boundary has neither a charge nor a new native snooze and must fall through.
    // proves: CLAIM(snooze-handled)
    // catches: reporting the duplicate as handled, or treating it as another successful native snooze.
    @Test
    fun `repeat callback within the same boundary returns to the proxy path`() = runTest {
        budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 9 * 60)
        assertDeniedReturn(delayMillis = 10_800_000L)

        assertFallback(boundary.plusSeconds(30), nativeCalls = 2, reason = SAME_BOUNDARY)
    }

    // why: multiple original notifications can return on the same cadence boundary.
    // proves: CLAIM(snooze-return-budget)
    // catches: throttling the whole boundary after the first key retries.
    @Test
    fun `different keys can each resnooze at the same boundary`() = runTest {
        val otherKey = "0|com.whatsapp|43"
        budget.state = DeliveryBudgetState(spentPoints = 8, periodStartMinute = 9 * 60)
        startSnooze()
        assertThat(post(otherKey, input)).isTrue()

        assertThat(returnPost(key, input.copy(now = boundary))).isTrue()
        assertThat(returnPost(otherKey, input.copy(now = boundary))).isTrue()
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

        assertThat(post(key, input)).isFalse()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(event(FAILED, arrival))

        platform.rejectSnooze = false
        assertThat(post(key, input.copy(now = arrival.plusSeconds(1)))).isTrue()
        assertThat(budget.checked).isEmpty()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, 299_000L)).inOrder()
    }

    // why: pre-26 devices lack timed snooze and must leave the notification to ordinary delivery.
    // catches: invoking an unavailable platform method, swallowing the post, or missing the failure event.
    @Test
    fun `unsupported platform leaves the post unhandled and logs failure`() = runTest {
        assertThat(post(key, input.copy(platformSupportsSnooze = false))).isFalse()
        assertThat(events.recent()).containsExactly(event(FAILED, arrival))
        assertThat(platform.attempts).isEmpty()
        assertThat(budget.checked).isEmpty()
    }

    // why: a replacement listener must charge Android's return even though the original tracker was lost.
    // proves: CLAIM(snooze-return-budget)
    // catches: skipping rehydration or intercepting a return as a new snooze after restart.
    @Test
    fun `new handler rehydrates ledger ownership before charging the return`() = runTest {
        startSnooze()
        assertThat(platform.snoozedKeys()).containsExactly(key)
        val replacement = newHandler()
        replacement.onListenerConnected(arrival.plusSeconds(1), boundary.minusSeconds(3600))

        assertThat(returnPost(key, input.copy(now = boundary), replacement)).isTrue()
        assertThat(platform.snoozedKeys()).isEmpty()
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(events.recent()).containsExactly(event(RETURNED, boundary), event(SNOOZED, arrival)).inOrder()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
    }

    // why: the removal Android emits when snooze starts is not a source cancellation.
    // proves: CLAIM(snooze-removal), CLAIM(snooze-return-budget)
    // catches: forwarding the snooze removal, logging it as REMOVED, or losing the key before return.
    @Test
    fun `snooze removal is handled and leaves the return tracked`() = runTest {
        startSnooze()
        val removedAt = arrival.plusSeconds(1)
        assertThat(handler.onRemoved(key, packageName, REASON_SNOOZED, removedAt.toEpochMilli())).isTrue()
        assertThat(returnPost(key, input.copy(now = boundary))).isTrue()

        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(event(RETURNED, boundary), event(SNOOZED, arrival)).inOrder()
    }

    // why: source cancellation while Android owns the snooze sends no removal callback.
    // catches: clearing at the expiry tick during an Android snapshot gap, or never clearing on the same boot.
    @Test
    fun `scan waits a full tick before clearing an absent cancelled source`() = runTest {
        startSnooze()
        platform.keys.remove(key)

        handler.onBatchTick(boundary.plusMillis(1), boundary.minusSeconds(3600))
        assertThat(ledger.rows[key]?.snoozeDeadline).isEqualTo(boundary.toEpochMilli())
        assertThat(ledger.rows[key]?.isCleared).isFalse()
        assertThat(events.recent()).containsExactly(event(SNOOZED, arrival))

        handler.onBatchTick(nextPeriod, nextHour)
        assertThat(ledger.rows[key]?.isCleared).isTrue()
        assertThat(ledger.rows[key]?.snoozeDeadline).isNull()
        assertThat(budget.checked).isEmpty()
        assertThat(fallback).isEmpty()
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
            input.copy(exclusionFlags = input.exclusionFlags.copy(isSystemExemptCategory = true)),
            input.copy(exclusionFlags = input.exclusionFlags.copy(isOngoing = true)),
            input.copy(exclusionFlags = input.exclusionFlags.copy(isForegroundService = true)),
            input.copy(exclusionFlags = input.exclusionFlags.copy(isNonClearable = true)),
            input.copy(exclusionFlags = input.exclusionFlags.copy(isGroupSummary = true)),
        )
        for ((index, post) in freshPosts.withIndex()) {
            assertThat(post("fresh-$index", post.copy(snoozeEnabled = false))).isFalse()
        }
        assertThat(platform.attempts).isEmpty()
        assertThat(budget.checked).isEmpty()
        assertThat(events.recent()).isEmpty()

        startSnooze()
    }

    // why: a native refusal after a denied return must release the saved item to the ordinary proxy path.
    // catches: swallowing the failure, retaining snooze ownership, or charging a rejected return.
    @Test
    fun `native retry throwing falls through and releases the row`() = runTest {
        startSnooze()
        budget.forceDecision = { SnoozeReturnDecision.RetryAt(nextPeriod) }
        platform.rejectSnooze = true

        assertFallback(boundary, nativeCalls = 2, reason = NATIVE_FAILURE)
    }

    // why: a nonpositive retry would break I7 and could keep returning immediately.
    // catches: clamping to zero or scheduling a retry after its deadline has already passed.
    @Test
    fun `retry at or before now falls through without calling Android`() = runTest {
        for ((index, invalidRetry) in listOf(boundary, boundary.minusMillis(1)).withIndex()) {
            val sourceKey = "$key-$index"
            assertThat(post(sourceKey, input)).isTrue()
            budget.forceDecision = { SnoozeReturnDecision.RetryAt(invalidRetry) }

            assertFallback(boundary, nativeCalls = index + 1, reason = INVALID_RETRY, sourceKey = sourceKey)
        }
    }

    // why: without a funded tick, ownership must return to the existing proxy code.
    // catches: silently consuming the return or fabricating a retry delay.
    @Test
    fun `no funded return moment hands the saved notification to the proxy path`() = runTest {
        startSnooze()
        budget.forceDecision = { SnoozeReturnDecision.NoFundedMoment }

        assertFallback(boundary, nativeCalls = 1, reason = NO_FUNDED_MOMENT)
    }

    // why: the three-snooze limit repeats per local day, then hands the next denial to ordinary delivery.
    // catches: carrying yesterday's snooze into today's count, or allowing a fourth same-day retry.
    @Test
    fun `fourth denied return in one local day falls through`() = runTest {
        val midnight = Instant.parse("2026-11-21T07:00:00Z")
        val localInput = input.copy(
            now = midnight.minusSeconds(300),
            zone = ZoneId.of("America/Denver"),
            scheduleAt = { _, _ -> listOf(NotificationSchedulePeriod(0, 0, 24, 0, 8)) },
        )
        budget.forceDecision = { SnoozeReturnDecision.RetryAt(it.plusSeconds(3600)) }
        assertThat(post(key, localInput)).isTrue()
        repeat(3) { hour ->
            assertThat(returnPost(key, localInput.copy(now = midnight.plusSeconds(hour * 3600L)))).isTrue()
        }
        assertThat(events.recent().count { it.kind == RESNOOZED }).isEqualTo(3)

        assertFallback(midnight.plusSeconds(3 * 3600L), nativeCalls = 4, reason = DAILY_LIMIT, returnInput = localInput)
    }

    // why: Android's snoozed list contains other listeners' keys and cannot confer ownership.
    // catches: claiming foreign keys at connect and charging or suppressing their later posts with snooze off.
    @Test
    fun `replacement listener leaves foreign snoozes untouched with snooze off`() = runTest {
        startSnooze() // Owned positive control shares the same Android snapshot.
        val foreignKey = "foreign"
        platform.keys += foreignKey
        val replacement = newHandler()
        replacement.onListenerConnected(arrival.plusSeconds(1), boundary.minusSeconds(3600))

        assertThat(post(foreignKey, input.copy(snoozeEnabled = false, now = boundary), replacement)).isFalse()
        assertThat(budget.checked).isEmpty()
        assertThat(ledger.rows.keys).containsExactly(key)
        assertThat(returnPost(key, input.copy(snoozeEnabled = false, now = boundary), replacement)).isTrue()
        assertThat(budget.charges).containsExactly(returnAt(boundary))
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
    }

    // why: after reboot, an expired missing native record must be restored through its saved inbox row.
    // catches: silently losing the payload, clearing the row, or leaving it in the snoozed state after loss.
    @Test
    fun `reboot loss restores the saved text as pending and records lost`() = runTest {
        ledger.upsert(snoozedRow())
        val now = nextPeriod
        newHandler(bootCount = 11).onListenerConnected(now, nextHour)

        val row = ledger.rows.getValue(key)
        assertThat(row.snoozeDeadline).isNull()
        assertThat(row.isDelivered).isFalse()
        assertThat(row.isCleared).isFalse()
        assertThat(row.title).isEqualTo("A title")
        assertThat(row.text).isEqualTo("A body")
        assertThat(events.recent()).containsExactly(event(LOST, now))
        assertThat(fallback).isEmpty()
    }

    // why: an expired missing record on the same boot is a source cancellation, not a reboot loss.
    // catches: bringing a cancelled notification back as a pending proxy.
    @Test
    fun `expired missing snooze on the same boot clears the saved row`() = runTest {
        ledger.upsert(snoozedRow())

        newHandler().onListenerConnected(nextPeriod, nextHour)

        val row = ledger.rows.getValue(key)
        assertThat(row.isCleared).isTrue()
        assertThat(row.snoozeDeadline).isNull()
        assertThat(events.recent().any { it.kind == LOST }).isFalse()
        assertThat(fallback).isEmpty()
    }

    // why: a no-op native call is detected when exactly the same post is still active after a tick.
    // catches: suppressing a no-op, falling back to an obsolete version, or repeating fallback after late confirmation.
    @Test
    fun `unconfirmed active snooze falls back at the tick and a late callback cannot duplicate it`() = runTest {
        platform.hideOnSnooze = false
        platform.active[key] = arrival.toEpochMilli()
        startSnooze()
        val updatedKey = "0|com.whatsapp|44"
        assertThat(post(updatedKey, input)).isTrue()
        handler.onBatchTick(boundary, boundary.minusSeconds(3600))
        assertThat(fallback).isEmpty()
        platform.active[updatedKey] = boundary.plusMillis(1).toEpochMilli()
        handler.onBatchTick(nextHour, boundary)
        assertThat(fallback.map { it.id }).containsExactly(key)
        assertThat(ledger.rows[key]?.snoozeDeadline).isNull()
        assertThat(events.recent().any { it.fallbackReason == UNCONFIRMED }).isTrue()

        handler.onRemoved(key, packageName, REASON_SNOOZED, nextHour.plusSeconds(1).toEpochMilli())
        handler.onBatchTick(nextHour.plusSeconds(3600), nextHour)
        assertThat(fallback.map { it.id }).containsExactly(key)
        assertThat(ledger.rows[key]?.snoozeDeadline).isNull()
        assertThat(budget.charges).containsExactly(Return(updatedKey, boundary.plusMillis(1).toEpochMilli(), nextHour))
        assertThat(fallbackOrder).containsExactly("cancel:$key", "arrive:$key").inOrder()
    }

    // why: a return still visible under a different post time is settled even if the binder offer was lost.
    // catches: treating the return as a failed native snooze or charging it during both connect and the next tick.
    @Test
    fun `rescan settles a changed active post time exactly once`() = runTest {
        ledger.upsert(snoozedRow())
        platform.active[key] = boundary.toEpochMilli()
        val now = boundary.plusSeconds(1)
        handler.onListenerConnected(now, boundary.minusSeconds(3600))
        handler.onBatchTick(nextHour, boundary)

        assertThat(budget.charges).containsExactly(Return(key, boundary.toEpochMilli(), now))
        assertThat(ledger.rows[key]?.isDelivered).isTrue()
        assertThat(fallbackOrder).isEmpty()
        assertThat(platform.attempts).isEmpty()
    }

    // why: ownership can persist past the deadline if Android still lists the key as snoozed.
    // catches: declaring loss from a passed time alone, including after reboot.
    @Test
    fun `rescan keeps an expired row that Android still reports snoozed`() = runTest {
        val held = snoozedRow()
        ledger.upsert(held)
        platform.keys += key
        ledger.upsert(held.copy(id = "missing"))
        val replacement = newHandler(bootCount = 11)
        replacement.onListenerConnected(nextPeriod, nextHour)

        assertThat(ledger.rows[key]).isEqualTo(held)
        assertThat(ledger.rows["missing"]?.snoozeDeadline).isNull() // Scan positive control.
        assertThat(budget.checked).isEmpty()
        assertThat(fallback).isEmpty()
    }

    // why: disabling the filter releases a visible return without snoozing, charging, or creating a proxy.
    // catches: dropping the callback before ending custody or invoking the ordinary fallback while filtering is off.
    @Test
    fun `filter off releases the visible return and closes custody`() = runTest {
        startSnooze()
        assertThat(returnPost(key, input.copy(now = boundary, filterEnabled = false))).isTrue()
        assertThat(ledger.rows[key]?.snoozeDeadline).isNull()
        assertThat(ledger.rows[key]?.isDelivered).isTrue()
        assertThat(platform.active).containsEntry(key, boundary.toEpochMilli())
        assertThat(platform.attempts).hasSize(1)
        assertThat(budget.checked).isEmpty()
        assertThat(fallbackOrder).isEmpty()
    }

    // why: disabling snooze must stop further native requests when an owned return is denied.
    // catches: issuing a new snooze after opting out or leaving the denied source visible while filtering is on.
    @Test
    fun `snooze off and budget denial cancel the original and use proxy arrival`() = runTest {
        startSnooze()
        budget.forceDecision = { SnoozeReturnDecision.RetryAt(nextPeriod) }

        assertFallback(boundary, nativeCalls = 1, reason = SNOOZE_OFF, returnInput = input.copy(snoozeEnabled = false))
        assertThat(budget.checked).containsExactly(returnAt(boundary))
        assertThat(platform.active).doesNotContainKey(key)
        assertThat(fallback.single().text).isEqualTo("A body")
    }

    // why: the worker can see an old flag after custody has already closed.
    // catches: treating a rejected ownership check as a retry or omitting its diagnostic reason.
    @Test
    fun `not owned decision records the exact not a return fallback`() = runTest {
        startSnooze()
        budget.forceDecision = { SnoozeReturnDecision.NotOwned }

        assertFallback(boundary, nativeCalls = 1, reason = NOT_A_RETURN)
    }

    // why: an owned key posted before its deadline is not an authorized native return.
    // catches: charging a premature callback or replacing the saved payload with an update to the hidden record.
    @Test
    fun `owned callback before deadline falls back with the saved original`() = runTest {
        startSnooze()
        assertFallback(arrival.plusSeconds(1), nativeCalls = 1, reason = BEFORE_DEADLINE)
        assertThat(fallback.single().timestamp).isEqualTo(arrival.toEpochMilli())
        assertThat(budget.checked).isEmpty()
    }

    // why: cancelling a summary can cascade to its children, so the snooze route must leave it in Android's tray.
    // catches: treating the summary as an ordinary proxy arrival or storing it in the ledger.
    @Test
    fun `snooze on leaves a group summary visible without saving a row`() = runTest {
        val grouped = input.copy(
            exclusionFlags = input.exclusionFlags.copy(isGroupSummary = true, hasGroupKey = true),
        )
        platform.active[key] = arrival.toEpochMilli()
        assertThat(post(key, grouped.copy(snoozeEnabled = false))).isFalse()
        assertThat(post(key, grouped)).isTrue()
        assertThat(platform.active).containsEntry(key, arrival.toEpochMilli())
        assertThat(ledger.rows).isEmpty()
        assertThat(fallbackOrder).isEmpty()
        assertThat(platform.attempts).isEmpty()
    }

    // why: group-summary cancellation can collateral-remove an owned child without ending its logical delivery.
    // catches: clearing the child or charging it during recovery.
    @Test
    fun `reason twelve restores an uncharged grouped child as pending`() = runTest {
        ledger.upsert(snoozedRow().copy(snoozeGroupKey = "group"))
        val at = boundary.toEpochMilli()
        assertThat(handler.onRemoved(key, packageName, 12, at)).isTrue()
        val row = ledger.rows.getValue(key)
        assertThat(row.snoozeDeadline).isNull()
        assertThat(row.isDelivered).isFalse()
        assertThat(row.isCleared).isFalse()
        assertThat(budget.checked).isEmpty()
        assertThat(refreshed).isEmpty()
    }

    // why: collateral removal of an already charged return needs a refresh with no second spend.
    // catches: sending the child to normal accounting or dropping the saved notification.
    @Test
    fun `reason twelve refreshes an already charged grouped child without spending again`() = runTest {
        val row = snoozedRow().copy(snoozeGroupKey = "group", isDelivered = true,
            snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null)
        ledger.upsert(row)
        assertThat(handler.onRemoved(key, packageName, 12, boundary.toEpochMilli())).isTrue()
        assertThat(refreshed).containsExactly(row)
        assertThat(budget.checked).isEmpty()
        assertThat(fallback).isEmpty()
    }

    // why: a missing grouped child on the same boot can be a cascade and keeps its saved pending copy.
    // catches: applying the ordinary same-boot cancellation rule to grouped children.
    @Test
    fun `missing grouped child on the same boot returns to pending after the guard`() = runTest {
        ledger.upsert(snoozedRow().copy(snoozeGroupKey = "group"))
        handler.onBatchTick(nextPeriod, nextHour)
        val row = ledger.rows.getValue(key)
        assertThat(row.snoozeDeadline).isNull()
        assertThat(row.isCleared).isFalse()
        assertThat(row.isDelivered).isFalse()
        assertThat(row.text).isEqualTo("A body")
    }

    private fun snoozedRow() = item(key, input).copy(
        snoozeDeadline = boundary.toEpochMilli(), snoozePriority = HIGH, snoozeBootCount = 10,
    )

    private suspend fun assertFallback(
        now: Instant,
        nativeCalls: Int,
        reason: SnoozeFallbackReason,
        sourceKey: String = key,
        returnInput: SnoozeInput = input,
    ) {
        assertThat(returnPost(sourceKey, returnInput.copy(now = now))).isFalse()
        assertThat(platform.attempts).hasSize(nativeCalls)
        assertThat(budget.charges).isEmpty()
        assertThat(ledger.rows[sourceKey]?.snoozeDeadline).isNull()
        val event = events.recent().first()
        assertThat(event.atEpochMillis).isEqualTo(now.toEpochMilli())
        assertThat(event.fallbackReason).isEqualTo(reason)
        assertThat(fallbackOrder.takeLast(2)).containsExactly("cancel:$sourceKey", "arrive:$sourceKey").inOrder()
    }

    private suspend fun startSnooze() {
        assertThat(post(key, input)).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
    }

    private suspend fun assertDeniedReturn(delayMillis: Long) {
        assertThat(budget.canDeliver(HIGH, boundary)).isFalse()
        assertThat(budget.canDeliver(HIGH, budget.retryAt)).isTrue()
        startSnooze()

        assertThat(returnPost(key, input.copy(now = boundary))).isTrue()
        assertThat(budget.checked).containsExactly(returnAt(boundary))
        assertThat(budget.charges).isEmpty()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, delayMillis)).inOrder()
        assertThat(events.recent()).containsExactly(event(RESNOOZED, boundary), event(SNOOZED, arrival)).inOrder()
    }

    private fun event(kind: ExperimentEventKind, at: Instant) =
        ExperimentEvent(at.toEpochMilli(), kind, packageName = packageName)

    private fun returnAt(at: Instant) = Return(key, at.toEpochMilli(), at)

    private data class Return(val key: String, val postTime: Long, val at: Instant)

    private class FakeLedger : SnoozeLedger {
        val rows = linkedMapOf<String, NotificationItem>()
        override suspend fun find(key: String) = rows[key]
        override suspend fun liveRows() = rows.values.filter { it.snoozeDeadline != null }
        override suspend fun upsert(item: NotificationItem) { rows[item.id] = item }
    }

    private class FakeReturnBudget(var retryAt: Instant, private val ledger: FakeLedger) : SnoozeReturnBudget {
        var forceDecision: ((Instant) -> SnoozeReturnDecision)? = null
        var schedule = listOf(morning, afternoon)
        var state = DeliveryBudgetState()
        val checked = mutableListOf<Return>()
        val charges = mutableListOf<Return>()

        private fun timeAt(at: Instant) = DeliveryClock.current(false, Clock.fixed(at, ZoneOffset.UTC))

        fun canDeliver(priority: NotificationPriority, at: Instant): Boolean =
            NotificationDeliveryPolicy.canDeliverNow(priority, PriorityDeliverySetting.defaultFor(priority), schedule, timeAt(at), state).canDeliver

        override suspend fun settleReturn(key: String, postTime: Long, now: Instant): SnoozeReturnDecision {
            val returned = Return(key, postTime, now)
            checked += returned
            forceDecision?.let { return it(now) }
            val row = ledger.find(key) ?: return SnoozeReturnDecision.NoFundedMoment
            val priority = row.snoozePriority ?: return SnoozeReturnDecision.NoFundedMoment
            val setting = PriorityDeliverySetting.defaultFor(priority)
            val time = timeAt(now)
            val decision = NotificationDeliveryPolicy.canDeliverNow(priority, setting, schedule, time, state)
            if (!decision.canDeliver) return SnoozeReturnDecision.RetryAt(retryAt)

            state = NotificationDeliveryPolicy.recordPassthrough(priority, setting, schedule, time.scheduleMinuteOfDay, decision.updatedBudget)
            charges += returned
            ledger.upsert(row.copy(isDelivered = true, snoozeDeadline = null, snoozePriority = null, snoozeBootCount = null))
            return SnoozeReturnDecision.Charged
        }
    }

    private data class SnoozeAttempt(val key: String, val millis: Long)

    private class FakeSnoozePlatform(private val onCancel: (String) -> Unit) : SnoozePlatform {
        val attempts = mutableListOf<SnoozeAttempt>()
        val keys = mutableSetOf<String>()
        val active = mutableMapOf<String, Long>()
        fun returned(key: String) { keys.remove(key) }
        var rejectSnooze = false
        var hideOnSnooze = true

        override fun snooze(key: String, millis: Long) {
            attempts += SnoozeAttempt(key, millis)
            if (rejectSnooze) throw SecurityException("listener permission revoked")
            if (hideOnSnooze) keys += key
        }

        override fun snoozedKeys(): Set<String> = keys.toSet()
        override fun activePostTimes(): Map<String, Long> = active.toMap()
        override fun cancelVisible(key: String) { active.remove(key); onCancel(key) }
    }

    private companion object {
        val morning = NotificationSchedulePeriod(9, 0, 12, 0, 8)
        val afternoon = NotificationSchedulePeriod(12, 0, 24, 0, 8)
    }
}
