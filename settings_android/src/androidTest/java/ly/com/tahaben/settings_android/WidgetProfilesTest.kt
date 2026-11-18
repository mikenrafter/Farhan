package ly.com.tahaben.settings_android

import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core.model.UIModeAppearance
import ly.com.tahaben.settings_portability.*
import org.junit.Assert.*
import org.junit.Test

class WidgetProfilesTest {
    private val classic = "{\"themeColors\":\"Classic\",\"uiModeAppearance\":\"FOLLOW_SYSTEM\",\"style\":\"VERTICAL\",\"compareWithYesterday\":false,\"textSize\":32}"

    // WHY: independent wire literal pins field names, enum values, defaults and exact canonical bytes.
    // catches: reader/writer sharing the same wrong key or enum spelling.
    @Test fun defaultProfileMatchesLiteralWireContract() {
        assertEquals(listOf(WidgetProfile()), WidgetProfiles.decode("[$classic]"))
        assertEquals("[$classic]", WidgetProfiles.encode(listOf(WidgetProfile())))
    }

    // WHY: usage/loading-only states must not manufacture styles; duplicates must not expose widget counts/IDs.
    // catches: exporting entire Glance maps or treating any existing map as a configured widget.
    @Test fun excludesDerivedStateAndDeduplicatesUserChoices() {
        val configured = mapOf("widget_theme_colors" to SettingValue.Text("Classic"))
        val derived = mapOf("today_time_today" to SettingValue.Int64(123L),
            "yesterday_time_today" to SettingValue.Int64(456L), "is_loading" to SettingValue.Bool(true))
        val profiles = WidgetProfiles.fromLegacy(listOf(derived, configured + derived, configured))
        assertEquals(listOf(WidgetProfile()), profiles)
        assertEquals("[$classic]", WidgetProfiles.encode(profiles))
        assertFalse(WidgetProfiles.encode(profiles).contains("today"))
    }

    // WHY: older partial user maps use the same remaining defaults as getWidgetConfiguration.
    // catches: dropping partial settings or inventing a second set of defaults.
    @Test fun partialUserMapKeepsConfiguredValueAndDefaultsTheRest() {
        assertEquals(listOf(WidgetProfile(textSize = 48)), WidgetProfiles.fromLegacy(listOf(
            mapOf("text_size" to SettingValue.Int32(48)))))
    }

    // WHY: malformed profiles must fail the whole import/export, never silently omit one widget style.
    // catches: JSONObject coercion/last-value-wins and mapNotNull on invalid profiles.
    @Test fun rejectsUnknownEnumsWrongTypesDuplicateAndExtraFields() {
        val invalid = listOf(
            classic.replace("Classic", "Ocean"), classic.replace("VERTICAL", "ROUND"),
            classic.replace("FOLLOW_SYSTEM", "AUTO"), classic.replace("false", "\"false\""),
            classic.replace("32", "\"32\""), classic.replace("32", "49"),
            classic.replace("\"textSize\":32", "\"textSize\":32,\"textSize\":48"),
            classic.dropLast(1) + ",\"widgetId\":10}")
        for (literal in invalid) {
            try { WidgetProfiles.decode("[$literal]"); fail("Invalid profile was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    // WHY: canonical merging retains unused imported choices and makes output independent of OS widget order.
    // catches: export replacing imported profiles with only the currently installed widgets.
    @Test fun mergeRetainsUnusedChoicesAndSortsCanonically() {
        val vibrant = WidgetProfile(ThemeColors.Vibrant, UIModeAppearance.DARK_MODE, WidgetStyle.HORIZONTAL, true, 48)
        val expected = listOf(WidgetProfile(), vibrant)
        assertEquals(expected, WidgetProfiles.merge(listOf(vibrant), listOf(WidgetProfile(), vibrant)))
        assertEquals(WidgetProfiles.encode(expected), WidgetProfiles.encode(listOf(vibrant, WidgetProfile(), vibrant)))
    }
    // WHY: the main settings validator must inspect the stored profile string, not just the direct codec API.
    // catches: defining the profile codec but forgetting to wire it into AndroidSettingsValidator.
    @Test fun storedProfileKeyRoutesThroughSemanticValidation() {
        assertNull(AndroidSettingsValidator.firstProblem(SettingsConfiguration(dataStore = mapOf(
            "imported_widget_profiles_v1" to SettingValue.Text("[$classic]")))))
        assertEquals(SemanticProblem(SettingsStoreId.DATA_STORE, "imported_widget_profiles_v1"),
            AndroidSettingsValidator.firstProblem(SettingsConfiguration(dataStore = mapOf(
                "imported_widget_profiles_v1" to SettingValue.Text("[${classic.replace("Classic", "Ocean")}]")))))
    }

}
