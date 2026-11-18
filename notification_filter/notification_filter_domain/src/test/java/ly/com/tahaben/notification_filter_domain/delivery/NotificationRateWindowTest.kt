package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationRateWindowTest {
    // why: storms retain fixed buckets, old counts expire for idle packages, and a package cap bounds cardinality.
    // catches: timestamp-per-event storage, never pruning idle packages, wrong 10/60-minute boundaries, no capacity.
    @Test fun `minute buckets retain counts through storms then expire without a background poll`() {
        var now = 0L
        val window = NotificationRateWindow(2) { now }
        repeat(10_000) { window.record("a") }
        assertThat(window.record("a")).isEqualTo(NotificationRateWindow.Counts(10_001, 10_001))
        now = 600_000
        assertThat(window.record("a")).isEqualTo(NotificationRateWindow.Counts(1, 10_002))
        window.record("b"); window.record("c")
        assertThat(window.packageCount()).isEqualTo(2)
        now = 4_200_000
        assertThat(window.record("d")).isEqualTo(NotificationRateWindow.Counts(1, 1))
        assertThat(window.packageCount()).isEqualTo(1)
    }
}
