package ly.com.tahaben.notification_filter_data.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnoozeSummaryCancellationsTest {
    // why: Farhan can refresh collateral only once Android confirms the recorded summary was canceled by a listener.
    // catches: treating an app cancel, replacement summary, or unrelated group as Farhan's cascade.
    @Test
    fun `only the confirmed listener cancellation authorizes collateral`() {
        val cancellations = SnoozeSummaryCancellations { 100L }
        val groupKey = "0|app|g:group"
        cancellations.requested(groupKey, "summary", 50L)
        assertThat(cancellations.causedCascade(groupKey)).isFalse()

        cancellations.summaryRemoved(groupKey, "summary", 50L, listenerCancelled = false)
        assertThat(cancellations.causedCascade(groupKey)).isFalse()

        cancellations.requested(groupKey, "summary", 50L)
        cancellations.summaryRemoved(groupKey, "summary", 60L, listenerCancelled = true)
        assertThat(cancellations.causedCascade(groupKey)).isFalse()

        cancellations.requested(groupKey, "summary", 50L)
        cancellations.summaryRemoved(groupKey, "summary", 50L, listenerCancelled = true)
        assertThat(cancellations.causedCascade(groupKey)).isTrue()
        assertThat(cancellations.causedCascade("10|app|g:group")).isFalse()
    }

    // why: a reused group key must stop authorizing collateral and a burst must remain bounded.
    // catches: unbounded retention or extending the authorization indefinitely.
    @Test
    fun `old confirmed groups are evicted and expired`() {
        var now = 0L
        val cancellations = SnoozeSummaryCancellations { now }
        for (index in 0..128) {
            cancellations.requested("group-$index", "summary-$index", 0L)
            cancellations.summaryRemoved("group-$index", "summary-$index", 0L, listenerCancelled = true)
        }
        assertThat(cancellations.causedCascade("group-0")).isFalse()
        assertThat(cancellations.causedCascade("group-128")).isTrue()
        now = 2_000L
        assertThat(cancellations.causedCascade("group-128")).isFalse()
    }
}
