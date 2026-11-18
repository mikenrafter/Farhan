package ly.com.tahaben.notification_filter_data.local.preferences

import android.content.SharedPreferences
import ly.com.tahaben.notification_filter_domain.schedule.AutomationCredentialOwner
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Local opt-in credential owner. UI may copy/rotate/revoke it; no credential enters logs or tracing. */
@Singleton
class ScheduleAutomationCredentials @Inject constructor(private val preferences: SharedPreferences) : AutomationCredentialOwner {
    override fun isEnabled(): Boolean = preferences.getBoolean(ENABLED, false)
    override fun matches(presented: String?): Boolean {
        val saved = credential() ?: return false
        if (presented?.length != 64) return false
        return MessageDigest.isEqual(saved.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }
    override fun credential(): String? = preferences.getString(CREDENTIAL, null)
    override fun rotate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val credential = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(preferences.edit().putString(CREDENTIAL, credential).commit())
        return credential
    }
    override fun setEnabled(enabled: Boolean) {
        require(!enabled || credential() != null) { "Create an automation credential before enabling" }
        check(preferences.edit().putBoolean(ENABLED, enabled).commit())
    }
    override fun revoke() { check(preferences.edit().putBoolean(ENABLED, false).remove(CREDENTIAL).commit()) }
    companion object {
        private const val ENABLED = "notification_automation_enabled_v1"
        private const val CREDENTIAL = "notification_automation_credential_v1"
    }
}
