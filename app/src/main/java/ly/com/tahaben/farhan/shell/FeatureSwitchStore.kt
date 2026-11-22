package ly.com.tahaben.farhan.shell

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The feature switches in the app's `shared_pref` file, beside the other settings the portable backup carries
 * (the keys are listed in the settings catalog). Everything defaults to on.
 *
 * The flow follows the file, so a restore or a write from elsewhere shows up without a restart.
 */
@Singleton
internal class FeatureSwitchStore @Inject constructor(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val switches: StateFlow<FeatureSwitches> = state.asStateFlow()

    // SharedPreferences holds listeners weakly; this field keeps the listener alive with the singleton.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in KEYS) state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setUsage(enabled: Boolean) = write(KEY_USAGE, enabled)
    fun setLauncher(enabled: Boolean) = write(KEY_LAUNCHER, enabled)
    fun setFocus(enabled: Boolean) = write(KEY_FOCUS, enabled)

    private fun write(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
        state.value = read()
    }

    private fun read() = FeatureSwitches(
        usage = prefs.getBoolean(KEY_USAGE, true),
        launcher = prefs.getBoolean(KEY_LAUNCHER, true),
        focus = prefs.getBoolean(KEY_FOCUS, true),
    )

    companion object {
        const val KEY_USAGE = "feature_switch_usage"
        const val KEY_LAUNCHER = "feature_switch_launcher"
        const val KEY_FOCUS = "feature_switch_focus"
        private val KEYS = setOf(KEY_USAGE, KEY_LAUNCHER, KEY_FOCUS)
    }
}
