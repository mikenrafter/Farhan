package ly.com.tahaben.notification_filter_domain.repository

import kotlinx.coroutines.flow.Flow
import ly.com.tahaben.notification_filter_domain.model.NotificationItem

interface NotificationRepository {

    suspend fun insertNotificationItemEntity(notificationItem: NotificationItem)
    suspend fun deleteNotificationItemEntity(notificationItem: NotificationItem)
    suspend fun deleteAllNotificationsFromDB()
    fun getNotifications(): Flow<List<NotificationItem>>
    fun getClearedNotifications(): Flow<List<NotificationItem>>
    suspend fun markNotificationRead(id: String)
    suspend fun markAppNotificationsRead(packageName: String)
    suspend fun markNotificationAsCleared(id: String)
    suspend fun markNotificationAsDelivered(id: String)
    suspend fun getUndeliveredNotificationsUpToPriority(priorityLevel: Int): List<NotificationItem>
    suspend fun getPackageNotificationCountSince(packageName: String, since: Long): Int
    suspend fun getAllNotifications(): List<NotificationItem>

}