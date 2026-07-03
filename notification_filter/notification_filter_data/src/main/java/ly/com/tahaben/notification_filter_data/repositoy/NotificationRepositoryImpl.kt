package ly.com.tahaben.notification_filter_data.repositoy

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ly.com.tahaben.notification_filter_data.local.NotificationDao
import ly.com.tahaben.notification_filter_data.mapper.toNotificationEntity
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class NotificationRepositoryImpl(private val dao: NotificationDao) : NotificationRepository {

    override suspend fun insertNotificationItemEntity(notificationItem: NotificationItem) {
        dao.insertNotificationItem(notificationItem.toNotificationEntity())
    }

    override suspend fun deleteNotificationItemEntity(notificationItem: NotificationItem) {
        dao.deleteNotificationItem(notificationItem.toNotificationEntity())
    }

    override suspend fun deleteAllNotificationsFromDB() {
        dao.deleteAllNotifications()
    }

    override fun getNotifications(): Flow<List<NotificationItem>> {
        return dao.getActiveNotifications()
            .map { entities -> entities.map { it.toNotificationItem() } }
    }

    override fun getClearedNotifications(): Flow<List<NotificationItem>> {
        return dao.getClearedNotifications()
            .map { entities -> entities.map { it.toNotificationItem() } }
    }

    override suspend fun markNotificationRead(id: String) {
        dao.markNotificationRead(id)
    }

    override suspend fun markAppNotificationsRead(packageName: String) {
        dao.markAppNotificationsRead(packageName)
    }

    override suspend fun markNotificationAsCleared(id: String) {
        dao.markNotificationAsCleared(id)
    }

    override suspend fun markNotificationAsDelivered(id: String) {
        dao.markNotificationAsDelivered(id)
    }

    override suspend fun getUndeliveredNotificationsUpToPriority(priorityLevel: Int): List<NotificationItem> {
        return dao.getUndeliveredNotificationsUpToPriority(priorityLevel)
            .map { it.toNotificationItem() }
    }

    override suspend fun getPackageNotificationCountSince(packageName: String, since: Long): Int {
        return dao.getPackageNotificationCountSince(packageName, since)
    }

    override suspend fun getAllNotifications(): List<NotificationItem> {
        return dao.getNotificationsAsList().map { it.toNotificationItem() }
    }
}