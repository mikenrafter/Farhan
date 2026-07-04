package ly.com.tahaben.notification_filter_domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationSchedulePeriodTest {

    @Test
    fun `default schedule is valid and covers full day`() {
        assertThat(NotificationSchedulePeriod.isValid(NotificationSchedulePeriod.DEFAULT_SCHEDULE)).isTrue()
    }

    @Test
    fun `adjustBoundary updates adjacent periods without gaps`() {
        val adjusted = NotificationSchedulePeriod.adjustBoundary(
            schedule = NotificationSchedulePeriod.DEFAULT_SCHEDULE,
            boundaryIndex = 1,
            newHour = 11,
            newMinute = 30,
        )

        assertThat(adjusted).isNotNull()
        assertThat(adjusted!![1].endHour).isEqualTo(11)
        assertThat(adjusted[1].endMinute).isEqualTo(30)
        assertThat(adjusted[2].startHour).isEqualTo(11)
        assertThat(adjusted[2].startMinute).isEqualTo(30)
        assertThat(NotificationSchedulePeriod.isValid(adjusted)).isTrue()
    }

    @Test
    fun `adjustBoundary rejects times that overlap neighboring periods`() {
        val invalid = NotificationSchedulePeriod.adjustBoundary(
            schedule = NotificationSchedulePeriod.DEFAULT_SCHEDULE,
            boundaryIndex = 1,
            newHour = 8,
            newMinute = 0,
        )

        assertThat(invalid).isNull()
    }
}
