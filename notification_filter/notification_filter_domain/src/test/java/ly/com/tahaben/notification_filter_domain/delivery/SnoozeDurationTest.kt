package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnoozeDurationTest {
    // why: 1 minute is a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 1 to a different choice.
    @Test
    fun `parse 1 minute`() = assertChoice(1, SnoozeDuration.MINUTES_1)

    // why: 5 minutes are a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 5 to a different choice.
    @Test
    fun `parse 5 minutes`() = assertChoice(5, SnoozeDuration.MINUTES_5)

    // why: 15 minutes are a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 15 to a different choice.
    @Test
    fun `parse 15 minutes`() = assertChoice(15, SnoozeDuration.MINUTES_15)

    // why: 30 minutes are a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 30 to a different choice.
    @Test
    fun `parse 30 minutes`() = assertChoice(30, SnoozeDuration.MINUTES_30)

    // why: 60 minutes are a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 60 to a different choice.
    @Test
    fun `parse 60 minutes`() = assertChoice(60, SnoozeDuration.MINUTES_60)

    // why: 120 minutes are a duration choice in D4.
    // proves: CLAIM(snooze-duration)
    // catches: rejecting or mapping 120 to a different choice.
    @Test
    fun `parse 120 minutes`() = assertChoice(120, SnoozeDuration.MINUTES_120)

    // why: the picker has six supported choices and missing settings select 15 minutes.
    // proves: CLAIM(snooze-duration)
    // catches: keeping the old one-minute timing or exposing an unsupported choice.
    @Test
    fun `default is fifteen minutes`() {
        assertThat(SnoozeDuration.entries.map { it.minutes }).containsExactly(1, 5, 15, 30, 60, 120)
        assertThat(SnoozeDuration.DEFAULT.minutes).isEqualTo(15)
    }

    // why: zero would expire immediately.
    // proves: CLAIM(snooze-duration)
    // catches: accepting 0 or clamping it to a supported choice.
    @Test
    fun `reject 0 minutes`() = assertRejected(0)

    // why: three falls between supported choices.
    // proves: CLAIM(snooze-duration)
    // catches: accepting 3 or clamping it to a supported choice.
    @Test
    fun `reject 3 minutes`() = assertRejected(3)

    // why: 121 exceeds the longest choice.
    // proves: CLAIM(snooze-duration)
    // catches: accepting 121 or clamping it to a supported choice.
    @Test
    fun `reject 121 minutes`() = assertRejected(121)

    // why: negative minutes would produce an invalid platform duration.
    // proves: CLAIM(snooze-duration)
    // catches: accepting -1 or clamping it to a supported choice.
    @Test
    fun `reject -1 minutes`() = assertRejected(-1)

    private fun assertChoice(minutes: Int, expected: SnoozeDuration) {
        val parsed = SnoozeDuration.parse(minutes)
        assertThat(parsed).isEqualTo(expected)
        assertThat(parsed?.minutes).isEqualTo(minutes)
    }

    private fun assertRejected(minutes: Int) {
        assertChoice(15, SnoozeDuration.MINUTES_15)
        assertThat(SnoozeDuration.parse(minutes)).isNull()
    }
}
