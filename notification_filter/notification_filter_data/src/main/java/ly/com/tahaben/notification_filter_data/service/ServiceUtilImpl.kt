package ly.com.tahaben.notification_filter_data.service

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import ly.com.tahaben.core.R
import ly.com.tahaben.core.util.BroadcastReceiverNotification
import ly.com.tahaben.core.util.MESSAGE_EXTRA
import ly.com.tahaben.core.util.NOTIFICATION_ID
import ly.com.tahaben.core.util.TITLE_EXTRA
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import timber.log.Timber
import java.util.Calendar

class ServiceUtilImpl(
    private val context: Context,
    private val sharedPref: Preferences
) : ServiceUtil {

    private val NOTIFICATION_SERVICES_SPLITTER = ":"

    override fun isNotificationAccessPermissionGranted(): Boolean {
        val notificationListenerName =
            context.packageName + "/" + NotificationService::class.java.name
        Timber.d("notificationListener Name: $notificationListenerName")
        val enabledNotificationListeners =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")

        return enabledNotificationListeners != null && enabledNotificationListeners.split(
            NOTIFICATION_SERVICES_SPLITTER
        ).contains(notificationListenerName)
    }

    override suspend fun isNotificationServiceEnabled(): Boolean {
        return sharedPref.isServiceEnabled()
    }

    override fun startNotificationListenerService() {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }

    override fun clearNotificationActions() = NotificationService.cancelAllProxies(context)

    override fun clearNotificationActions(notificationIds: List<String>) =
        NotificationService.cancelProxies(context, notificationIds)

    override fun getNotificationIntent(notificationKey: String): PendingIntent? {
        val pendingIntent = NotificationService.getIntentForNotification(notificationKey)
        return pendingIntent
    }

    override fun deleteNotificationIntent(notificationKey: String) {
        NotificationService.removeActionsForNotification(notificationKey)
        NotificationService.cancelProxy(context, notificationKey)
    }

    override fun getNotificationActionTitles(notificationId: String): List<String> =
        NotificationService.getActionsForNotification(notificationId).map { it.title.toString() }

    override fun fireNotificationAction(notificationId: String, actionIndex: Int) {
        try {
            NotificationService.getActionsForNotification(notificationId)
                .getOrNull(actionIndex)
                ?.actionIntent
                ?.send()
        } catch (e: android.app.PendingIntent.CanceledException) {
            Timber.e("Action intent cancelled for $notificationId[$actionIndex]: ${e.message}")
        }
    }

    override fun getVisibleNotificationSources(): List<VisibleNotificationSource> =
        NotificationDiagnostics.trace("farhan.catalog.discovery") { NotificationService.getVisibleNotificationSources() }

    override fun getOwnPackageName(): String = context.packageName

    override fun getNotificationChannelsForPackage(packageName: String): List<String> =
        NotificationService.getNotificationChannelsForPackage(packageName)

    override fun areAppNotificationsEnabled(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val uid = try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(POST_NOTIFICATION_OP, uid, packageName)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_ERRORED -> true
            else -> false
        }
    }

    override fun createNotifyMeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                context.getString(R.string.notify_me_channel_id),
                context.getString(R.string.notify_me_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.notify_me_channel_description)
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


    override fun scheduleNotifyMeNotification(hour: Int, minute: Int) {
        if (!sharedPref.isNotifyMeScheduledToday()) {
            try {

                val intent = Intent(context, BroadcastReceiverNotification::class.java).apply {
                    val title = context.getString(R.string.check_filtered_notifications)
                    val message = context.getString(R.string.you_have_unchecked_filtered_notifications)
                    putExtra(TITLE_EXTRA, title)
                    putExtra(MESSAGE_EXTRA, message)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    NOTIFICATION_ID,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.SECOND, 0)
                Timber.d("scheduling remind me for ${calendar.time}")
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
                sharedPref.setNotifyMeScheduledDate(calendar.timeInMillis)
            } catch (e: Exception) {
                Timber.e("e: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun openAppSettings() {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }

    override fun canScheduleExactAlarms(): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun openExactAlarmsPermissionScreen() {
        Timber.d("open exact alarms permission")
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }

    override fun launchAppInfo(packageName: String) {
        val appInfoIntent = Intent()
        appInfoIntent.action =
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        appInfoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appInfoIntent.data =
            Uri.fromParts("package", packageName, null)
        context.startActivity(appInfoIntent)
    }

    companion object {
        private const val POST_NOTIFICATION_OP = "android:post_notification"
    }
}