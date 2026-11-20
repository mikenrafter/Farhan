package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ly.com.tahaben.notification_filter_data.R
import ly.com.tahaben.notification_filter_domain.sound.SoundStatusPort

internal class AndroidSoundStatus(private val context: Context) : SoundStatusPort {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun show(modesRecovery: Boolean) {
        if (Build.VERSION.SDK_INT < 35 || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.sound_control_status_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val modes = PendingIntent.getActivity(
            context, 0, Intent(context, SoundControlModeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getBroadcast(
            context, 0, Intent(context, SoundControlReceiver::class.java)
                .setAction(SOUND_CONTROL_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        val message = context.getString(if (modesRecovery) R.string.sound_control_status_recovery else R.string.sound_control_status_message)
        try {
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(ly.com.tahaben.core.R.drawable.ic_farhan_transparent)
                .setContentTitle(context.getString(R.string.sound_control_status_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(modes)
                .addAction(0, context.getString(R.string.sound_control_off), off)
                .addAction(0, context.getString(R.string.sound_control_modes), modes)
                .build())
        } catch (_: SecurityException) {
            // Android can revoke posting permission between the grant check and notify.
        }
    }

    override fun clear() {
        manager.cancel(ID)
    }

    private companion object {
        const val ID = 7402
        const val CHANNEL = "farhan_sound_control_status"
    }
}

internal const val SOUND_CONTROL_OFF = "ly.com.tahaben.farhan.action.SOUND_CONTROL_OFF"
