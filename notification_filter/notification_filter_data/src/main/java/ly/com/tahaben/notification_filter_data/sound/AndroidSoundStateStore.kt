package ly.com.tahaben.notification_filter_data.sound

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import ly.com.tahaben.notification_filter_domain.sound.SoundClock
import ly.com.tahaben.notification_filter_domain.sound.SoundIntent
import ly.com.tahaben.notification_filter_domain.sound.SoundStateStore
import ly.com.tahaben.notification_filter_domain.sound.SoundTime

internal class AndroidSoundStateStore(context: Context, private val clock: SoundClock) : SoundStateStore {
    private val preferences = context.getSharedPreferences(SOUND_PREFS, Context.MODE_PRIVATE)

    override fun read(): SoundIntent {
        val values = preferences.all
        return SoundIntent(
            desiredOn = values[PHASE] == "on",
            generation = values[GENERATION] as? Long ?: 0,
            ruleId = values[RULE_ID] as? String,
        )
    }

    override fun commit(intent: SoundIntent): Boolean {
        val now = clock.now()
        return preferences.edit()
            .putString(PHASE, if (intent.desiredOn) "on" else "off")
            .putLong(GENERATION, intent.generation)
            .putString(RULE_ID, intent.ruleId)
            .putString("sound_control_boot_id", now.bootId)
            .putLong("sound_control_elapsed_millis", now.elapsedMillis)
            .commit()
    }

    private companion object {
        const val PHASE = "sound_control_phase"
        const val GENERATION = "sound_control_generation"
        const val RULE_ID = "sound_control_rule_id"
    }
}

internal class AndroidSoundClock(private val context: Context) : SoundClock {
    override fun now(): SoundTime = SoundTime(
        SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, "boot_count", -1).toString(),
    )
}
