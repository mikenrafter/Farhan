package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryTimeContextTest {

    @Test
    fun `production mode uses hourly boundaries`() {
        val ctx = DeliveryTimeContext.from(
            calendarHour = 14,
            minuteOfDay = 14 * 60 + 30,
            dayOfYear = 1,
            testingMode = false,
        )

        assertThat(ctx.boundaryIndex).isEqualTo(14)
        assertThat(ctx.boundaryIntervalMinutes).isEqualTo(60)
        assertThat(ctx.boundariesPerDay).isEqualTo(24)
    }

    @Test
    fun `testing mode uses 3 minute boundaries`() {
        val ctx = DeliveryTimeContext.from(
            calendarHour = 1,
            minuteOfDay = 65,
            dayOfYear = 1,
            testingMode = true,
        )

        assertThat(ctx.boundaryIndex).isEqualTo(21)
        assertThat(ctx.boundaryIntervalMinutes).isEqualTo(3)
        assertThat(ctx.boundariesPerDay).isEqualTo(480)
    }

    @Test
    fun `testing mode schedule minute maps 3 minute slots to virtual hours`() {
        val ctx = DeliveryTimeContext.from(
            calendarHour = 0,
            minuteOfDay = 30,
            dayOfYear = 1,
            testingMode = true,
        )
        assertThat(ctx.boundaryIndex).isEqualTo(10)
        assertThat(ctx.scheduleMinuteOfDay).isEqualTo(600)
        assertThat(ctx.passthroughWindowKey).isEqualTo(10)
    }

    @Test
    fun `production mode schedule minute equals minute of day`() {
        val ctx = DeliveryTimeContext.from(
            calendarHour = 10,
            minuteOfDay = 630,
            dayOfYear = 1,
            testingMode = false,
        )
        assertThat(ctx.scheduleMinuteOfDay).isEqualTo(630)
        assertThat(ctx.passthroughWindowKey).isEqualTo(10)
    }

    @Test
    fun `msToNextBoundary in testing mode targets next 3 minute slot`() {
        val delay = DeliveryTimeContext.msToNextBoundary(
            minute = 4,
            second = 0,
            millis = 0,
            testingMode = true,
        )

        assertThat(delay).isEqualTo(120_000L)
    }
}
