package ly.com.tahaben.notification_filter_data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import org.junit.Test

class OtpPreferencesTest {
    private val dataStore = object : DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = transform(emptyPreferences())
    }

    // why: a fresh install must raise codes to CRITICAL for every app once the experiment is on.
    // catches: a different default priority, or an empty-set scope read as "no apps".
    @Test fun `defaults are critical and all apps`() {
        val preferences = DefaultPreferences(MemoryPreferences(), dataStore)
        assertThat(preferences.getOtpPriority()).isEqualTo(NotificationPriority.CRITICAL)
        assertThat(preferences.isOtpAllApps()).isTrue()
        assertThat(preferences.getOtpPackages()).isEmpty()
    }

    // why: the three values must survive a restart and use the exact keys the settings catalog lists.
    // catches: a key renamed in one place only, so a backup drops the setting.
    @Test fun `values round trip through the catalog keys`() {
        val shared = MemoryPreferences()
        val preferences = DefaultPreferences(shared, dataStore)
        preferences.setOtpPriority(NotificationPriority.HIGH)
        preferences.setOtpAllApps(false)
        preferences.setOtpPackages(setOf("com.bank", "com.mail"))
        val reread = DefaultPreferences(shared, dataStore)
        assertThat(reread.getOtpPriority()).isEqualTo(NotificationPriority.HIGH)
        assertThat(reread.isOtpAllApps()).isFalse()
        assertThat(reread.getOtpPackages()).containsExactly("com.bank", "com.mail")
        assertThat(shared.getString("notification_otp_priority", null)).isEqualTo("HIGH")
        assertThat(shared.getStringSet("notification_otp_packages", null)).containsExactly("com.bank", "com.mail")
        assertThat(shared.getBoolean("notification_otp_all_apps", true)).isFalse()
        assertThat(Preferences.KEY_OTP_PRIORITY).isEqualTo("notification_otp_priority")
    }

    // why: an imported backup can carry any string; an unknown priority must fall back to the default.
    // catches: valueOf throwing in the notification path.
    @Test fun `an unknown stored priority reads as critical`() {
        val shared = MemoryPreferences()
        shared.edit().putString("notification_otp_priority", "URGENT").commit()
        assertThat(DefaultPreferences(shared, dataStore).getOtpPriority()).isEqualTo(NotificationPriority.CRITICAL)
    }
}
