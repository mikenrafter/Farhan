package ly.com.tahaben.notification_filter_domain.summary

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class HourlyArrivalsTest {
    private val zone = ZoneId.of("America/Denver")
    private val day = LocalDate.of(2026, 11, 21)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) =
        ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun `arrivals land in their local hour`() {
        // why: the timeline's bars are read as hours of the owner's day.
        // catches: bucketing by UTC hour instead of the zone's hour.
        val hours = HourlyArrivals.count(listOf(at(7, 5), at(7, 59), at(18, 0)), day, zone)
        assertThat(hours[7]).isEqualTo(2)
        assertThat(hours[18]).isEqualTo(1)
        assertThat(hours.sum()).isEqualTo(3)
    }

    @Test
    fun `other days and rows with no arrival time are left out`() {
        // why: Summary is "today"; yesterday's rows still in the database must not count.
        // catches: counting every row, or counting the model's default timestamp of 0.
        val hours = HourlyArrivals.count(
            listOf(at(23, 59, day.minusDays(1)), at(0, 0, day.plusDays(1)), 0L, -5L, at(9)), day, zone,
        )
        assertThat(hours.sum()).isEqualTo(1)
        assertThat(hours[9]).isEqualTo(1)
    }

    @Test
    fun `midnight belongs to the new day`() {
        // why: the boundary hour 0 is the easiest to put on the wrong side.
        // catches: an exclusive start or inclusive end on the day window.
        val hours = HourlyArrivals.count(listOf(at(0, 0), at(23, 59)), day, zone)
        assertThat(hours[0]).isEqualTo(1)
        assertThat(hours[23]).isEqualTo(1)
    }

    @Test
    fun `a clock change day still yields 24 slots and counts every arrival`() {
        // why: 2026-11-01 repeats 01:00 in Denver; the chart has a fixed 24 slots.
        // catches: indexing by elapsed hours since midnight, which reaches slot 24.
        val fallBack = LocalDate.of(2026, 11, 1)
        val first = ZonedDateTime.of(fallBack, java.time.LocalTime.of(1, 30), zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = ZonedDateTime.of(fallBack, java.time.LocalTime.of(1, 30), zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val hours = HourlyArrivals.count(listOf(first, second), fallBack, zone)
        assertThat(hours).hasLength(24)
        assertThat(hours[1]).isEqualTo(2)
    }

    @Test
    fun `scaling fills the chart with the busiest hour and keeps an empty day flat`() {
        // why: bar heights are relative; an empty day must not divide by zero.
        // catches: NaN heights from dividing by a zero peak.
        val scaled = HourlyArrivals.scaled(IntArray(24).also { it[3] = 4; it[4] = 2 })
        assertThat(scaled[3]).isEqualTo(1f)
        assertThat(scaled[4]).isEqualTo(0.5f)
        assertThat(HourlyArrivals.scaled(IntArray(24)).all { it == 0f }).isTrue()
    }
}
