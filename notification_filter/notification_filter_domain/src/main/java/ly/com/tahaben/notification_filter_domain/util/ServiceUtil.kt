package ly.com.tahaben.notification_filter_domain.util

import android.app.PendingIntent

interface ServiceUtil {

    suspend fun isNotificationServiceEnabled(): Boolean
    fun isNotificationAccessPermissionGranted(): Boolean
    fun startNotificationListenerService()
    fun getNotificationIntent(notificationKey: String): PendingIntent?
    fun deleteNotificationIntent(notificationKey: String)
    fun createNotifyMeNotificationChannel()
    fun scheduleNotifyMeNotification(hour: Int, minute: Int)
    fun openAppSettings()
    fun canScheduleExactAlarms(): Boolean
    fun openExactAlarmsPermissionScreen()
    fun launchAppInfo(packageName: String)
    fun getNotificationActionTitles(notificationId: String): List<String>
    fun fireNotificationAction(notificationId: String, actionIndex: Int)
    fun getVisibleNotificationSources(): List<ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource>
    fun getOwnPackageName(): String
    fun getNotificationChannelsForPackage(packageName: String): List<String>
    fun areAppNotificationsEnabled(packageName: String): Boolean
}