package ly.com.tahaben.notification_filter_data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDeliveryScheduler @Inject constructor(
    private val preferences: Preferences,
) {

    fun scheduleNextBoundaryDelivery(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = nextBoundaryTriggerAtMillis()
        val pendingIntent = deliveryPendingIntent(context)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
            Timber.d("Scheduled Farhan delivery alarm at $triggerAtMillis")
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to schedule exact delivery alarm — missing SCHEDULE_EXACT_ALARM?")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    fun cancelScheduledDelivery(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(deliveryPendingIntent(context))
    }

    private fun nextBoundaryTriggerAtMillis(): Long {
        val cal = Calendar.getInstance()
        val testingMode = preferences.isDeliveryTestingMode()
        val delayMs = DeliveryTimeContext.msToNextBoundary(
            minute = cal.get(Calendar.MINUTE),
            second = cal.get(Calendar.SECOND),
            millis = cal.get(Calendar.MILLISECOND),
            testingMode = testingMode,
        )
        return System.currentTimeMillis() + delayMs
    }

    companion object {
        const val ACTION_DELIVER_PENDING = "ly.com.tahaben.notification_filter.action.DELIVER_PENDING"

        fun deliveryPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, NotificationDeliveryReceiver::class.java).apply {
                action = ACTION_DELIVER_PENDING
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
