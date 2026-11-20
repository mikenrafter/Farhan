package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnoozeExclusionFlagsTest {
    private val clear = SnoozeExclusionFlags(false, false, false, false, false, false)

    // why: Android flags are independent bits, including no-clear and foreground service.
    // proves: CLAIM(snooze-raw-flags)
    // catches: default-false inputs, switched bits, equality testing a bit field, or schema drift.
    @Test
    fun `maps each Android exclusion bit and a combined bit field`() {
        assertThat(SnoozeExclusionFlags.fromNotification(0x2, null, null)).isEqualTo(clear.copy(isOngoing = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0x20, null, null)).isEqualTo(clear.copy(isNonClearable = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0x40, null, null)).isEqualTo(clear.copy(isForegroundService = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0x200, null, null)).isEqualTo(clear.copy(isGroupSummary = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0x262, null, null))
            .isEqualTo(clear.copy(isOngoing = true, isNonClearable = true, isForegroundService = true, isGroupSummary = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0x10, null, null)).isEqualTo(clear)
    }

    // why: Android exempt categories and actual group membership are independent of flag bits.
    // proves: CLAIM(snooze-raw-flags)
    // catches: looking only at the flags or confusing the summary flag with a grouped child.
    @Test
    fun `maps system categories and group membership without flags`() {
        for (category in listOf("service", "call", "alarm", "missed_call", "sys")) {
            assertThat(SnoozeExclusionFlags.fromNotification(0, category, null)).isEqualTo(clear.copy(isSystemExemptCategory = true))
        }
        assertThat(SnoozeExclusionFlags.fromNotification(0, "msg", "a-group")).isEqualTo(clear.copy(hasGroupKey = true))
        assertThat(SnoozeExclusionFlags.fromNotification(0, "msg", null)).isEqualTo(clear)
    }
}
