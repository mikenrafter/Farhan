package ly.com.tahaben.settings_android

import ly.com.tahaben.settings_portability.*
import org.junit.Assert.*
import org.junit.Test

class AndroidSettingsValidatorTest {
    private fun shared(key: String, text: String) = SettingsConfiguration(sharedPreferences = mapOf(key to SettingValue.Text(text)))

    // WHY: the app repairs the old 23:59 endpoint; rejecting it would strand real old backups.
    // catches: validating legacy input directly without DaySchedule.repairLegacy.
    @Test fun acceptsLegacyEndpointWithoutChangingSourceText() {
        val literal = "[{\"sh\":0,\"sm\":0,\"eh\":23,\"em\":59,\"b\":9}]"
        val configuration = shared("notification_delivery_schedule", literal)
        assertNull(AndroidSettingsValidator.firstProblem(configuration))
        assertEquals(SettingValue.Text(literal), configuration.sharedPreferences["notification_delivery_schedule"])
    }

    // WHY: malformed, coerced and duplicate embedded JSON cannot silently select app defaults.
    // catches: permissive JSONObject coercion and fallback-to-default decoders.
    @Test fun refusesBadQueueAndScheduleLiterals() {
        for (literal in listOf("{\"pkg\":{\"order\":\"UNKNOWN\",\"rank\":0}}",
            "{\"pkg\":{\"order\":\"NEWEST_FIRST\",\"rank\":\"0\"}}",
            "{\"pkg\":{\"order\":\"NEWEST_FIRST\",\"rank\":0,\"rank\":1}}")) {
            assertEquals(SemanticProblem(SettingsStoreId.SHARED_PREFERENCES, "notification_app_queue_v1"),
                AndroidSettingsValidator.firstProblem(shared("notification_app_queue_v1", literal)))
        }
        assertNotNull(AndroidSettingsValidator.firstProblem(shared("notification_delivery_schedule",
            "[{\"sh\":1,\"sm\":0,\"eh\":24,\"em\":0,\"b\":9}]")))
    }

    // WHY: onboarding's sentinel is legitimate app state, but a synthetic test theme is not a real theme.
    // catches: guessing accepted theme names or rejecting the app's existing Unknown sentinel.
    @Test fun acceptsExistingThemeNamesAndRejectsUnknownEnum() {
        for (name in listOf("Unknown", "Vibrant", "Classic", "Dynamic"))
            assertNull(AndroidSettingsValidator.firstProblem(SettingsConfiguration(dataStore = mapOf("app_theme_colors" to SettingValue.Text(name)))))
        assertEquals(SemanticProblem(SettingsStoreId.DATA_STORE, "app_theme_colors"),
            AndroidSettingsValidator.firstProblem(SettingsConfiguration(dataStore = mapOf("app_theme_colors" to SettingValue.Text("Ocean")))))
    }
}
