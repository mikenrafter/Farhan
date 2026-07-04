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

    @Query("SELECT * FROM notificationitementity WHERE isDelivered = 0 AND isCleared = 0 AND priority <= :priorityLevel ORDER BY priority ASC, timestamp ASC")
    suspend fun getUndeliveredNotificationsUpToPriority(priorityLevel: Int): List<NotificationItemEntity>

    @Query("SELECT COUNT(*) FROM notificationitementity WHERE creatorPackage = :packageName AND timestamp >= :since AND isCleared = 0")
    suspend fun getPackageNotificationCountSince(packageName: String, since: Long): Int
}