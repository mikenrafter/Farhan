package ly.com.tahaben.notification_filter_data.sound

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ly.com.tahaben.notification_filter_data.service.NotificationService
import ly.com.tahaben.notification_filter_domain.sound.GrantsPort
import ly.com.tahaben.notification_filter_domain.sound.SoundGrants

internal class AndroidSoundGrants(private val context: Context) : GrantsPort {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun read(): SoundGrants {
        if (Build.VERSION.SDK_INT < 35) return SoundGrants(false, false, false)
        return SoundGrants(
            listener = manager.isNotificationListenerAccessGranted(ComponentName(context, NotificationService::class.java)),
            policy = manager.isNotificationPolicyAccessGranted,
            post = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                manager.areNotificationsEnabled(),
        )
    }
}
