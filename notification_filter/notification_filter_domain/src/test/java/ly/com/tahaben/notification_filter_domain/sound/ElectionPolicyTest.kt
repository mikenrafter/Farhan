package ly.com.tahaben.notification_filter_domain.sound

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.CRITICAL
import ly.com.tahaben.notification_filter_domain.testsupport.item
import org.junit.Test

class ElectionPolicyTest {
    private val message = item("K", timestamp = 100)
    private val elected = ElectionInput(configured = true)

    // why: v2.10–11 and the enable disclosures reserve breakthrough for eligible personal alerts.
    // catches: treating election configuration alone as sufficient for Critical, exceptions or work items.
    @Test
    fun `election excludes critical exceptions and work even when configured`() {
        assertThat(ElectionPolicy.alert(message.copy(priority = CRITICAL), elected)).isEqualTo(ProxyAlert.Silent)
        assertThat(ElectionPolicy.alert(message, elected.copy(exception = true))).isEqualTo(ProxyAlert.Silent)
        assertThat(ElectionPolicy.alert(message, elected.copy(workProfile = true))).isEqualTo(ProxyAlert.Silent)
        assertThat(ElectionPolicy.alert(message, ElectionInput(configured = false))).isEqualTo(ProxyAlert.Silent)
        assertThat(ElectionPolicy.alert(message, elected)).isEqualTo(ProxyAlert.Breakthrough)
    }

    // why: the draft state edges dedupe key plus post time. v2 keeps updates silent via the existing refresh path.
    // catches: key-only dedupe or dedupe on post time alone, suppressing a genuinely different post.
    @Test
    fun `same key and time alerts once while other identities may alert`() {
        val history = setOf(AlertIdentity("K", 100))
        assertThat(ElectionPolicy.alert(message, elected, history)).isEqualTo(ProxyAlert.Silent)
        assertThat(ElectionPolicy.alert(message.copy(timestamp = 101), elected, history)).isEqualTo(ProxyAlert.Breakthrough)
        assertThat(ElectionPolicy.alert(message.copy(id = "L"), elected, history)).isEqualTo(ProxyAlert.Breakthrough)
    }
}
