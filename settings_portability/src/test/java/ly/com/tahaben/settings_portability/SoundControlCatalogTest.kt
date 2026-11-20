package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SoundControlCatalogTest {
    private fun shared(key: String) = SettingsCatalog.dispositionOf(SettingsStoreId.SHARED_PREFERENCES, key)

    // why: v2.9 keeps the enable switch local. Existing experiments prove the prefix still exports configuration.
    // catches: enabling sound control through the notification_experiment_ key family or exporting its dedicated key.
    @Test
    fun `dedicated enable key is unsupported while the old experiment prefix is configuration`() {
        assertThat(shared("notification_sound_control_enabled")).isEqualTo(SettingDisposition.Unsupported)
        assertThat(shared("notification_experiment_source_icon"))
            .isEqualTo(SettingDisposition.Configuration(SettingType.BOOLEAN))
    }

    // why: v2.9 explicitly classifies the device's journal fields as internal.
    // catches: an omitted field falling through to Unsupported, or a broad notification prefix exporting rule state.
    @Test
    fun `journal fields each have the internal exclusion`() {
        val expected = SettingDisposition.Excluded(ExclusionReason.INTERNAL)
        val actual = listOf("sound_control_rule_id", "sound_control_generation", "sound_control_phase").associateWith(::shared)
        assertThat(actual).containsExactly(
            "sound_control_rule_id", expected, "sound_control_generation", expected, "sound_control_phase", expected,
        )
    }
}
