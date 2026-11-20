package ly.com.tahaben.farhan.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Query

// TODO: Remove on v 1.0
@Database(
    entities = [LegacyNotificationItemEntity::class],
    version = 2,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)]
)
abstract class NotificationDatabase : RoomDatabase() {
    abstract val dao: LegacyNotificationDao
}

@Dao
interface LegacyNotificationDao {
    @Query("SELECT * FROM NotificationItemEntity")
    suspend fun getNotificationsAsList(): List<LegacyNotificationItemEntity>
}
