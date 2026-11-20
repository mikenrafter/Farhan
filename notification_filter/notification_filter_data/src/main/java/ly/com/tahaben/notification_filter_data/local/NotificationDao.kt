package ly.com.tahaben.notification_filter_data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ly.com.tahaben.notification_filter_data.local.entity.NotificationItemEntity

@Dao
interface NotificationDao {
    @Query(NotificationQueries.INVALIDATE)
    suspend fun invalidateBefore(session: String, order: Long)

    @Query(NotificationQueries.PENDING_GROUPS)
    suspend fun pendingGroups(maxPriority: Int): List<PendingGroup>
    @Query(NotificationQueries.PENDING_HEAD)
    suspend fun pendingHead(packageName: String, priority: Int, oldest: Boolean): NotificationItemEntity?

    @Query(NotificationQueries.INBOX)
    fun observeInbox(cleared: Boolean, priority: Int?, standingCutoff: Long, search: String, limit: Int, offset: Int): Flow<List<NotificationItemEntity>>
    @Query(NotificationQueries.COUNTS)
    fun observeCounts(dayStart: Long, standingCutoff: Long, floodStart: Long): Flow<DashboardCounts>
    @Query(NotificationQueries.PRIORITIES)
    fun observePriorities(): Flow<List<PriorityCount>>
    @Query(NotificationQueries.CATEGORIES)
    fun observeCategories(): Flow<List<CategoryCount>>
    @Query(NotificationQueries.CHATTERS)
    fun observeChatters(): Flow<List<ChatterCount>>
    @Query(NotificationQueries.BATCHES)
    fun observeBatchHeaders(windowMillis: Long, limit: Int): Flow<List<BatchHeader>>
    @Query(NotificationQueries.BATCH_ITEMS)
    fun observeBatchItems(packageName: String, start: Long, end: Long, limit: Int, offset: Int): Flow<List<NotificationItemEntity>>
    @Query(NotificationQueries.STANDING)
    fun observeStanding(cutoff: Long, limit: Int): Flow<List<NotificationItemEntity>>
    @Query(NotificationQueries.STANDING)
    suspend fun getStandingPage(cutoff: Long, limit: Int): List<NotificationItemEntity>

    @Query("SELECT * FROM notificationitementity WHERE id = :id LIMIT 1")
    suspend fun find(id: String): NotificationItemEntity?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationItem(notificationItemEntity: NotificationItemEntity)

    @Delete
    suspend fun deleteNotificationItem(notificationItemEntity: NotificationItemEntity)

    @Query("DELETE  FROM notificationitementity")
    suspend fun deleteAllNotifications()

    @Query("SELECT * FROM notificationitementity")
    fun getNotifications(): Flow<List<NotificationItemEntity>>

    @Query("SELECT * FROM notificationitementity")
    suspend fun getNotificationsAsList(): List<NotificationItemEntity>

    @Query("UPDATE notificationitementity SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationRead(id: String)

    @Query("UPDATE notificationitementity SET isRead = 1 WHERE creatorPackage = :packageName")
    suspend fun markAppNotificationsRead(packageName: String)

    @Query("SELECT * FROM notificationitementity WHERE isCleared = 0")
    fun getActiveNotifications(): Flow<List<NotificationItemEntity>>

    @Query("SELECT * FROM notificationitementity WHERE isCleared = 1")
    fun getClearedNotifications(): Flow<List<NotificationItemEntity>>

    @Query("UPDATE notificationitementity SET isCleared = 1 WHERE id = :id")
    suspend fun markNotificationAsCleared(id: String)

    @Query("UPDATE notificationitementity SET isDelivered = 1 WHERE id = :id")
    suspend fun markNotificationAsDelivered(id: String)

    @Query(NotificationQueries.PENDING_UP_TO_PRIORITY)
    suspend fun getUndeliveredNotificationsUpToPriority(priorityLevel: Int): List<NotificationItemEntity>

    @Query("SELECT COUNT(*) FROM notificationitementity WHERE creatorPackage = :packageName AND timestamp >= :since AND isCleared = 0")
    suspend fun getPackageNotificationCountSince(packageName: String, since: Long): Int
}
data class PendingGroup(val packageName: String, val priority: Int)
