package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExperimentsTest {
    // why: CLAIM(snooze-allowlist). Native snooze cannot be undone and sits outside the budget, so it may touch
    //      only Farhan's demo apps. A real messenger, a look-alike and a longer package name must all be refused.
    // catches: a prefix match; an allowlist that includes a real app.
    @Test
    fun `only the exact demo packages may be snoozed`() {
        assertThat(Experiments.maySnoozeInDemo("ly.com.tahaben.test.messenger")).isTrue()
        assertThat(Experiments.maySnoozeInDemo("ly.com.tahaben.test.messenger.evil")).isFalse()
        assertThat(Experiments.maySnoozeInDemo("ly.com.tahaben.test")).isFalse()
        assertThat(Experiments.maySnoozeInDemo("com.whatsapp")).isFalse()
    }

    // why: the demo log is bounded and newest first; it never grows without limit while the service runs.
    // catches: an unbounded list; oldest-first order.
    @Test
    fun `the event log keeps the newest twenty`() {
        val log = InMemoryExperimentEventLog(capacity = 20)
        (1..25).forEach { log.record(ExperimentEvent(it.toLong(), ExperimentEventKind.REMOVED, removalReason = it)) }

        assertThat(log.recent()).hasSize(20)
        assertThat(log.recent().first().atEpochMillis).isEqualTo(25)
        assertThat(log.recent().last().atEpochMillis).isEqualTo(6)
    }
}
