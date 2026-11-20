package ly.com.tahaben.notification_filter_data.sound

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SoundControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != SOUND_CONTROL_OFF && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sound = soundControl(context)
                if (action == SOUND_CONTROL_OFF) sound.off() else sound.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}

class SoundControlWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        soundControl(applicationContext).onWorker(inputData.getLong(WORK_GENERATION, -1))
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SoundControlEntryPoint {
    fun runtime(): SoundControlRuntime
}

private fun soundControl(context: Context): SoundControlRuntime =
    EntryPointAccessors.fromApplication(context, SoundControlEntryPoint::class.java).runtime()
