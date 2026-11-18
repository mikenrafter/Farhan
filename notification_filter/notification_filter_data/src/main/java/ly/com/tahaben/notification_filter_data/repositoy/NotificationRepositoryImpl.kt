package ly.com.tahaben.notification_filter_data.repositoy

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import ly.com.tahaben.notification_filter_domain.model.*
import ly.com.tahaben.notification_filter_domain.inbox.*
import ly.com.tahaben.notification_filter_data.local.NotificationDao
import ly.com.tahaben.notification_filter_data.mapper.toNotificationEntity
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class NotificationRepositoryImpl(private val dao: NotificationDao) : NotificationRepository {
    override fun observeInbox(query: InboxQuery): Flow<List<NotificationItem>> =
        dao.observeInbox(query.cleared, query.priority?.level, query.standingCutoff, query.search, query.limit, query.offset)
            .map { rows -> rows.map { it.toNotificationItem() } }

    override fun observeDashboard(query: DashboardQuery): Flow<NotificationDashboardSummary> = combine(
        dao.observeCounts(query.dayStart, query.standingCutoff, query.floodStart), dao.observePriorities(), dao.observeCategories(), dao.observeChatters()
    ) { counts, priorities, categories, chatters ->
        val flooding = counts.recent >= query.floodThreshold
        NotificationDashboardSummary(counts.today, counts.unread, counts.standing, flooding,
            NotificationPriority.entries.associateWith { priority -> priorities.firstOrNull { it.label == priority.level }?.count ?: 0 },
            NotificationCategory.entries.associateWith { category -> categories.filter { NotificationCategory.fromName(it.label) == category }.sumOf { it.count } },
            chatters.map { TopChatter(it.appName, it.packageName, it.count) },
            (100 - counts.standing.coerceAtMost(20) * 2 - (if (flooding) 25 else 0) - counts.criticalUnread.coerceAtMost(5) * 5).coerceIn(0, 100))
    }

    override fun observeBatchHeaders(windowMillis: Long, limit: Int): Flow<List<NotificationBatch>> = dao.observeBatchHeaders(windowMillis.coerceAtLeast(1), limit.coerceIn(1, 100)).map { headers ->
        headers.map { header -> NotificationBatch(
            "${header.packageName}_${header.rangeStart}", header.appName ?: header.packageName, header.packageName,
            NotificationCategory.fromName(header.category), NotificationPriority.fromLevel(header.priority), header.count,
            header.latestTimestamp, header.oldestTimestamp, listOfNotNull(header.title, header.text).joinToString(" — ").takeIf { it.isNotBlank() },
            rangeStart = header.rangeStart, rangeEnd = header.rangeEnd)
        }
    }
    override fun observeBatchItems(batch: NotificationBatch, offset: Int, limit: Int): Flow<List<NotificationItem>> =
        dao.observeBatchItems(batch.packageName, batch.rangeStart, batch.rangeEnd, limit.coerceIn(1, 100), offset.coerceAtLeast(0)).map { rows -> rows.map { it.toNotificationItem() } }
    override fun observeStanding(cutoff: Long, limit: Int): Flow<List<NotificationItem>> = dao.observeStanding(cutoff, limit.coerceIn(1, 100)).map { rows -> rows.map { it.toNotificationItem() } }
    override suspend fun getStandingPage(cutoff: Long, limit: Int): List<NotificationItem> = dao.getStandingPage(cutoff, limit.coerceIn(1, 100)).map { it.toNotificationItem() }


    override suspend fun insertNotificationItemEntity(notificationItem: NotificationItem) {
        ly.com.tahaben.notification_filter_data.service.NotificationDiagnostics.suspending("farhan.inbox.write") {
            dao.insertNotificationItem(notificationItem.toNotificationEntity())
        }
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