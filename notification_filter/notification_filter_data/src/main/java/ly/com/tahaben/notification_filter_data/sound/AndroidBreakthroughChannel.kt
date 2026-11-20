package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import ly.com.tahaben.notification_filter_data.R
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannel
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort

internal class AndroidBreakthroughChannel(private val context: Context) : BreakthroughChannelPort {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun create() {
        if (Build.VERSION.SDK_INT < 35 || !manager.isNotificationPolicyAccessGranted || read() != null) return
        val channel = NotificationChannel(
            BREAKTHROUGH_CHANNEL_ID, context.getString(R.string.sound_control_alert_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setBypassDnd(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    override fun read(): BreakthroughChannel? {
        if (Build.VERSION.SDK_INT < 26) return null
        return manager.getNotificationChannel(BREAKTHROUGH_CHANNEL_ID)?.let {
            BreakthroughChannel(it.canBypassDnd(), it.importance)
        }
    }
}
