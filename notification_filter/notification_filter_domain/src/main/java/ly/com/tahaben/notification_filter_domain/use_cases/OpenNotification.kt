package ly.com.tahaben.notification_filter_domain.use_cases

import android.content.Context
import android.content.Intent
import android.widget.Toast
import ly.com.tahaben.core.R
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import timber.log.Timber

class OpenNotification(
    private val serviceUtil: ServiceUtil,
    private val context: Context
) {
    operator fun invoke(notificationKey: String, packageName: String) {
        val pendingIntent = serviceUtil.getNotificationIntent(notificationKey)
        if (pendingIntent != null) {
            try { pendingIntent.send(); return }
            catch (_: android.app.PendingIntent.CanceledException) { serviceUtil.deleteNotificationIntent(notificationKey) }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Toast.makeText(context, context.getString(R.string.package_not_found_error), Toast.LENGTH_LONG).show()
        }
    }
}