package ly.com.tahaben.notification_filter_data.sound

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Links the explicit Android rule to its configuration screen. */
class SoundControlModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(SOUND_MODES_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        finish()
    }
}

internal const val SOUND_MODES_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
