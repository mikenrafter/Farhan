package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SnoozeTrackerTest {
    private val tracker = SnoozeTracker()

    // why: Android re-posts the snoozed key, and consuming it must leave unrelated keys available.
    // proves: CLAIM(snooze-return-once)
    // catches: ignoring a recorded return, or clearing the entire tracker on a successful match.
    @Test
    fun `consume a recorded return without dropping other keys`() {
        tracker.recordSnoozed("returning")
        tracker.recordSnoozed("still snoozed")

        assertThat(tracker.consumeIfSnoozed("untracked")).isFalse()
        assertThat(tracker.consumeIfSnoozed("returning")).isTrue()
        assertThat(tracker.consumeIfSnoozed("still snoozed")).isTrue()
    }

    // why: a stale key may consume one fresh post with the same key, then must be gone.
    // proves: CLAIM(snooze-return-once)
    // catches: checking membership without removing the key.
    @Test
    fun `a second post of the same key is not a return`() {
        tracker.recordSnoozed("same key")

        assertThat(tracker.consumeIfSnoozed("same key")).isTrue()
        assertThat(tracker.consumeIfSnoozed("same key")).isFalse()
    }

    // why: the 1025th tracked key must replace the oldest one while preserving every other key.
    // proves: CLAIM(snooze-tracker-bound)
    // catches: an unbounded set, eviction at 1023, or evicting the newest key.
    @Test
    fun `recording beyond the bound evicts only the oldest key`() {
        (0..1024).forEach { tracker.recordSnoozed("key-$it") }

        assertThat(tracker.consumeIfSnoozed("key-0")).isFalse()
        assertRetainedKeys(1..1024)
    }

    // why: a listener reconnect can return more than 1024 snoozed entries at once.
    // proves: CLAIM(snooze-tracker-bound)
    // catches: bypassing eviction in rehydrate or retaining the first 1024 keys instead of the last 1024.
    @Test
    fun `rehydration also caps the tracker at 1024`() {
        tracker.rehydrate((0..1026).map { "key-$it" })

        (0..2).forEach { assertThat(tracker.consumeIfSnoozed("key-$it")).isFalse() }
        assertRetainedKeys(3..1026)
    }

    // why: a successful re-snooze refreshes the key's position in the bounded cache.
    // catches: LinkedHashSet.add retaining the key's old insertion position.
    @Test
    fun `recording a snoozed key again makes it newest for eviction`() {
        (0..1023).forEach { tracker.recordSnoozed("key-$it") }
        tracker.recordSnoozed("key-0")
        tracker.recordSnoozed("key-1024")

        assertThat(tracker.consumeIfSnoozed("key-1")).isFalse()
        assertThat(tracker.consumeIfSnoozed("key-0")).isTrue()
        assertRetainedKeys(2..1024)
    }

    private fun assertRetainedKeys(indices: IntRange) {
        for (index in indices) {
            assertWithMessage("key-%s should still be tracked", index)
                .that(tracker.consumeIfSnoozed("key-$index"))
                .isTrue()
        }
    }
}
