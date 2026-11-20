package ly.com.tahaben.farhan.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ly.com.tahaben.notification_filter_data.local.entity.NotificationItemEntity

@Entity(
    tableName = "NotificationItemEntity",
    indices = [
        Index(value = ["creatorPackage", "timestamp"]),
        Index(value = ["isDelivered", "isCleared", "priority"]),
        Index(value = ["isCleared", "timestamp", "id"]),
        Index(value = ["isCleared", "isRead", "priority", "timestamp"]),
    ]
)
data class LegacyNotificationItemEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val appName: String?,
    val title: String?,
    val text: String?,
    val time: String,
    val creatorPackage: String,
    @ColumnInfo(defaultValue = "0") val timestamp: Long = 0L,
    @ColumnInfo(defaultValue = "3") val priority: Int = 3,
    @ColumnInfo(defaultValue = "'OTHER'") val category: String = "OTHER",
    @ColumnInfo(defaultValue = "0") val isRead: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val channelId: String? = null,
    @ColumnInfo(defaultValue = "0") val isCleared: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isDelivered: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val priorityReason: String? = null,
    @ColumnInfo(defaultValue = "''") val sourceSession: String = "",
    @ColumnInfo(defaultValue = "0") val sourceEventOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val deliveryInvalidated: Boolean = false,
) {
    fun toCurrent() = NotificationItemEntity(
        id, appName, title, text, time, creatorPackage, timestamp, priority, category, isRead, channelId,
        isCleared, isDelivered, priorityReason, sourceSession, sourceEventOrder, deliveryInvalidated,
    )
}
