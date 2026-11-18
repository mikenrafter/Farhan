package ly.com.tahaben.notification_filter_domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ly.com.tahaben.notification_filter_domain.model.NotificationItem

interface NotificationRepository {
    fun observeInbox(query: ly.com.tahaben.notification_filter_domain.inbox.InboxQuery): Flow<List<NotificationItem>> =
        (if (query.cleared) getClearedNotifications() else getNotifications()).map { rows -> rows.sortedByDescending { it.timestamp }.drop(query.offset).take(query.limit) }
    fun observeDashboard(query: ly.com.tahaben.notification_filter_domain.inbox.DashboardQuery): Flow<ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary>? = null
    fun observeBatchHeaders(windowMillis: Long, limit: Int): Flow<List<ly.com.tahaben.notification_filter_domain.model.NotificationBatch>>? = null
    fun observeBatchItems(batch: ly.com.tahaben.notification_filter_domain.model.NotificationBatch, offset: Int = 0, limit: Int = 100): Flow<List<NotificationItem>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
    fun observeStanding(cutoff: Long, limit: Int = 100): Flow<List<NotificationItem>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun getStandingPage(cutoff: Long, limit: Int = 100): List<NotificationItem> = emptyList()


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