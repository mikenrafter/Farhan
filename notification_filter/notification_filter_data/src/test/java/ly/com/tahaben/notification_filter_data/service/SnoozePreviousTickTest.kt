package ly.com.tahaben.notification_filter_data.service

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class SnoozePreviousTickTest {
    // why: loss reconciliation needs the previous tick even when only 30 seconds have passed since this tick.
    // catches: subtracting only minute % interval, or assuming the device zone has whole-hour offsets.
    @Test
    fun `previous tick is one full boundary behind the current tick`() {
        assertThat(previousSnoozeTick(Instant.parse("2026-11-20T10:00:30Z"), ZoneOffset.UTC, false))
            .isEqualTo(Instant.parse("2026-11-20T09:00:00Z"))
        assertThat(previousSnoozeTick(Instant.parse("2026-11-20T10:25:00Z"), ZoneOffset.UTC, false))
            .isEqualTo(Instant.parse("2026-11-20T09:00:00Z"))
        assertThat(previousSnoozeTick(Instant.parse("2026-11-20T10:25:00Z"), ZoneOffset.UTC, true))
            .isEqualTo(Instant.parse("2026-11-20T10:21:00Z"))
        assertThat(previousSnoozeTick(Instant.parse("2026-11-20T04:55:00Z"), ZoneOffset.ofHoursMinutes(5, 30), false))
            .isEqualTo(Instant.parse("2026-11-20T03:30:00Z")) // 10:25 -> 09:00 local.
    }
}
