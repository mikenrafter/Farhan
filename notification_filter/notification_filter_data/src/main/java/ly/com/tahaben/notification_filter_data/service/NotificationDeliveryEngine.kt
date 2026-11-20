package ly.com.tahaben.notification_filter_data.service

import android.content.Context
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryCoordinator
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/** Alarm/listener adapter. All policy and accounting belongs to the injected coordinator. */
@Singleton
class NotificationDeliveryEngine @Inject constructor(
    private val preferences: Preferences,
    private val coordinator: NotificationDeliveryCoordinator,
    private val deliveryScheduler: NotificationDeliveryScheduler,
) {
    suspend fun maintainDeliverySchedule(context: Context) {
        deliverPendingNotifications(context)
        if (!preferences.isServiceEnabled() && !NotificationService.hasNativeSnoozes()) return
        deliveryScheduler.scheduleNextBoundaryDelivery(context)
    }
    suspend fun deliverPendingNotifications(context: Context) {
        NotificationService.reconcileNativeSnoozes()
        if (preferences.isServiceEnabled()) coordinator.onBatchTick()
    }
}
