package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which schedule is in force at an instant, on the phone's local wall clock, across both DST changes of 2026
 * in America/Denver. Explicit zone and instants; nothing reads the host clock or zone.
 * proves: CLAIM(local-date-selection)
 */
class ScheduleBookTest {

    private val denver = ZoneId.of("America/Denver")
    private val repeating = listOf(window(0, 1440, 1))
    private val novemberFirst = listOf(window(0, 720, 2), window(720, 1440, 2))
    private val marchEighth = listOf(window(0, 720, 3), window(720, 1440, 3))
    private val book = ScheduleBook(
        repeating = repeating,
        dated = mapOf(LocalDate.of(2026, 11, 1) to novemberFirst, LocalDate.of(2026, 3, 8) to marchEighth),
    )

    // why: the dated override applies for the local calendar date, not the UTC date or a fixed offset or 86,400 s.
    // catches: UTC date, a fixed -06:00 offset, and day = epochSecond / 86400.
    // Hand math, fall back Sun 2026-11-01 (MDT UTC-6 until 08:00Z, then MST UTC-7; the day is 25 hours):
    //   05:59Z = Oct 31 23:59 MDT -> repeating     06:30Z = Nov 1 00:30 MDT -> dated
    //   2026-11-02T06:59Z = Nov 1 23:59 MST -> dated (UTC date and a -06:00 offset both say Nov 2)
    //   2026-11-02T07:00Z = Nov 2 00:00 MST -> repeating
    // Spring forward Sun 2026-03-08 (MST UTC-7 until 09:00Z, then MDT UTC-6; the day is 23 hours):
    //   06:59Z = Mar 7 23:59 MST -> repeating      07:00Z = Mar 8 00:00 MST -> dated
    //   2026-03-09T05:59Z = Mar 8 23:59 MDT -> dated
    //   2026-03-09T06:00Z = Mar 9 00:00 MDT -> repeating (start of day + 86,400 s would still say Mar 8)
    @Test
    fun `the dated override follows the local calendar date across DST changes`() {
        val expectations = listOf(
            "2026-11-01T05:59:00Z" to repeating,
            "2026-11-01T06:30:00Z" to novemberFirst,
            "2026-11-02T06:59:00Z" to novemberFirst,
            "2026-11-02T07:00:00Z" to repeating,
            "2026-03-08T06:59:00Z" to repeating,
            "2026-03-08T07:00:00Z" to marchEighth,
            "2026-03-09T05:59:00Z" to marchEighth,
            "2026-03-09T06:00:00Z" to repeating,
        )

        for ((instant, expected) in expectations) {
            assertWithMessage(instant).that(book.scheduleAt(Instant.parse(instant), denver)).isEqualTo(expected)
        }
    }
}
