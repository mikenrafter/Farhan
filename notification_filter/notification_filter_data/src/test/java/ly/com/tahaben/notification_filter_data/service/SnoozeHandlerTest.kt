package ly.com.tahaben.notification_filter_data.service

import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_SNOOZED
import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.FAILED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.REMOVED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.RETURNED
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind.SNOOZED
import ly.com.tahaben.notification_filter_domain.delivery.InMemoryExperimentEventLog
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeDuration
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeInput
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import org.junit.Test

class SnoozeHandlerTest {
    private val packageName = "com.whatsapp"
    private val key = "0|com.whatsapp|42"
    private val input = SnoozeInput(
        packageName = packageName,
        ownPackageName = "ly.com.tahaben.farhan_alpha",
        snoozeEnabled = true,
        filterEnabled = true,
        platformSupportsSnooze = true,
        duration = SnoozeDuration.MINUTES_5,
    )
    private val platform = FakeSnoozePlatform()
    private val events = InMemoryExperimentEventLog()
    private val handler = SnoozeHandler(platform, events)

    // why: an eligible post must use the platform path and leave only app-level event data.
    // proves: CLAIM(snooze-handled)
    // catches: forwarding a successful snooze to ordinary delivery, using the wrong duration, or omitting app identity.
    @Test
    fun `a successful snooze is handled and logs the source package`() {
        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isTrue()

        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L)) // 5 × 60 × 1000
        assertThat(events.recent()).containsExactly(ExperimentEvent(100L, SNOOZED, packageName = packageName))
    }

    // why: the first re-post is Android's return, and a later fresh post may be snoozed for the new duration.
    // proves: CLAIM(snooze-handled), CLAIM(snooze-return-once)
    // catches: snoozing a return again, leaving the key tracked after return, or caching the first duration.
    @Test
    fun `Android return consumes the key once and does not call snooze`() {
        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isTrue()
        assertThat(handler.onPosted(key, input, atEpochMillis = 200L)).isTrue()

        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(
            ExperimentEvent(200L, RETURNED, packageName = packageName),
            ExperimentEvent(100L, SNOOZED, packageName = packageName),
        ).inOrder()

        val newDuration = input.copy(duration = SnoozeDuration.MINUTES_30)
        assertThat(handler.onPosted(key, newDuration, atEpochMillis = 300L)).isTrue()
        assertThat(platform.attempts).containsExactly(
            SnoozeAttempt(key, 300_000L),
            SnoozeAttempt(key, 1_800_000L), // 30 × 60 × 1000
        ).inOrder()
        assertThat(events.recent().first()).isEqualTo(ExperimentEvent(300L, SNOOZED, packageName = packageName))
    }

    // why: Android owns existing snoozes, so new eligibility rules apply only to fresh posts.
    // proves: CLAIM(snooze-handled), CLAIM(snooze-return-once)
    // catches: consulting the policy before the tracker and sending the return through proxy or budget handling.
    @Test
    fun `a return is consumed after snooze turns off and the app becomes exempt`() {
        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isTrue()
        val changed = input.copy(
            snoozeEnabled = false,
            isExceptedPackage = true,
            duration = SnoozeDuration.MINUTES_30,
        )

        assertThat(handler.onPosted(key, changed, atEpochMillis = 200L)).isTrue()
        assertThat(handler.onPosted(key, changed, atEpochMillis = 300L)).isFalse()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(
            ExperimentEvent(200L, RETURNED, packageName = packageName),
            ExperimentEvent(100L, SNOOZED, packageName = packageName),
        ).inOrder()
    }

    // why: a denied platform call leaves ordinary delivery intact and must never create a tracked return.
    // proves: CLAIM(snooze-handled)
    // catches: swallowing the post, leaving a tracked key after failure, or recording failure as success.
    @Test
    fun `platform security failure logs failure and leaves the post unhandled`() {
        platform.rejectSnooze = true

        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isFalse()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(ExperimentEvent(100L, FAILED, packageName = packageName))

        platform.rejectSnooze = false
        assertThat(handler.onPosted(key, input, atEpochMillis = 200L)).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, 300_000L))
        assertThat(events.recent().first()).isEqualTo(ExperimentEvent(200L, SNOOZED, packageName = packageName))
    }

    // why: pre-26 devices lack timed snooze and must leave the notification to ordinary delivery.
    // catches: invoking an unavailable platform method, swallowing the post, or missing the failure event.
    @Test
    fun `unsupported platform leaves the post unhandled and logs failure`() {
        assertThat(handler.onPosted(key, input.copy(platformSupportsSnooze = false), atEpochMillis = 100L)).isFalse()
        assertThat(events.recent()).containsExactly(ExperimentEvent(100L, FAILED, packageName = packageName))
        assertThat(platform.attempts).isEmpty()
    }

    // why: a connected replacement listener can identify Android's return even though local memory was lost.
    // proves: CLAIM(snooze-return-once)
    // catches: skipping rehydration, or treating a snoozed key as a new post after restart.
    @Test
    fun `new handler rehydrates from Android on connection`() {
        platform.keysAtConnection = setOf(key)
        handler.onListenerConnected()

        assertThat(handler.onPosted(key, input, atEpochMillis = 200L)).isTrue()
        assertThat(events.recent()).containsExactly(ExperimentEvent(200L, RETURNED, packageName = packageName))
        assertThat(platform.attempts).isEmpty()
    }

    // why: the removal Android emits when snooze starts is not a source cancellation.
    // proves: CLAIM(snooze-removal), CLAIM(snooze-return-once)
    // catches: forwarding the snooze removal, logging it as REMOVED, or dropping the key before Android returns it.
    @Test
    fun `snooze removal is handled and leaves the return tracked`() {
        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isTrue()
        assertThat(handler.onRemoved(key, packageName, REASON_SNOOZED, atEpochMillis = 200L)).isTrue()
        assertThat(handler.onPosted(key, input, atEpochMillis = 300L)).isTrue()

        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L))
        assertThat(events.recent()).containsExactly(
            ExperimentEvent(300L, RETURNED, packageName = packageName),
            ExperimentEvent(100L, SNOOZED, packageName = packageName),
        ).inOrder()
    }

    // why: a source cancellation ends a tracked snooze and a subsequent post with that key is fresh.
    // proves: CLAIM(snooze-removal)
    // catches: ignoring a source cancellation or leaving its key in the tracker.
    @Test
    fun `app cancellation logs removal and drops the key`() {
        assertThat(handler.onPosted(key, input, atEpochMillis = 100L)).isTrue()
        handler.onRemoved(key, packageName, REASON_APP_CANCEL, atEpochMillis = 200L)

        assertThat(events.recent()).containsExactly(
            ExperimentEvent(200L, REMOVED, removalReason = REASON_APP_CANCEL, packageName = packageName),
            ExperimentEvent(100L, SNOOZED, packageName = packageName),
        ).inOrder()
        assertThat(handler.onPosted(key, input, atEpochMillis = 300L)).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt(key, 300_000L), SnoozeAttempt(key, 300_000L))
        assertThat(events.recent().first()).isEqualTo(ExperimentEvent(300L, SNOOZED, packageName = packageName))
    }

    // why: the handler must leave every fresh post to the ordinary path when the experiment is off (I4).
    // catches: intercepting fresh posts or logging events even when the owner has opted out.
    @Test
    fun `snooze off leaves every fresh post unhandled`() {
        val freshPosts = listOf(
            input,
            input.copy(filterEnabled = false),
            input.copy(platformSupportsSnooze = false),
            input.copy(packageName = input.ownPackageName),
            input.copy(isExceptedPackage = true),
            input.copy(priority = NotificationPriority.CRITICAL),
            input.copy(isSystemExemptCategory = true),
            input.copy(isOngoing = true),
            input.copy(isForegroundService = true),
            input.copy(isNonClearable = true),
            input.copy(isGroupSummary = true),
        )
        for ((index, post) in freshPosts.withIndex()) {
            assertThat(handler.onPosted("fresh-$index", post.copy(snoozeEnabled = false), atEpochMillis = 100L)).isFalse()
        }
        assertThat(platform.attempts).isEmpty()
        assertThat(events.recent()).isEmpty()

        assertThat(handler.onPosted("fresh-0", input, atEpochMillis = 200L)).isTrue()
        assertThat(platform.attempts).containsExactly(SnoozeAttempt("fresh-0", 300_000L))
        assertThat(events.recent()).containsExactly(ExperimentEvent(200L, SNOOZED, packageName = packageName))
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
}
