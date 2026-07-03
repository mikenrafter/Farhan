package ly.com.tahaben.notification_filter_data.mapper

import android.app.Notification
import android.service.notification.StatusBarNotification
import ly.com.tahaben.notification_filter_data.local.entity.NotificationItemEntity
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import java.text.SimpleDateFormat
import java.util.*

fun NotificationItem.toNotificationEntity(): NotificationItemEntity {
    return NotificationItemEntity(
        id = id,
        appName = appName,
        title = title,
        text = text,
        time = time,
        creatorPackage = packageName,
        timestamp = timestamp,
        priority = priority.level,
        category = category.name,
        isRead = isRead,
    )
}

fun NotificationItemEntity.toNotificationItem(): NotificationItem {
    return NotificationItem(
        id = id,
        appName = appName,
        title = title,
        text = text,
        time = time,
        packageName = creatorPackage,
        timestamp = timestamp,
        priority = ly.com.tahaben.notification_filter_domain.model.NotificationPriority.fromLevel(priority),
        category = ly.com.tahaben.notification_filter_domain.model.NotificationCategory.fromName(category),
        isRead = isRead,
    )
}

/*
Not needed for now
fun Notification.toNotificationEntity(): NotificationItemEntity{
    val notification = this
    val extras = notification.extras
    val title = extras?.getString(Notification.EXTRA_TITLE)?.toString()
    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val progress = extras?.getInt(Notification.EXTRA_PROGRESS)
    val progressMax = extras?.getInt(Notification.EXTRA_PROGRESS_MAX)
    val progressIndeterminate = extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    return NotificationItemEntity(
        title = title,
        text = text,
        progress = progress,
        progressMax = progressMax,
        progressIndeterminate = progressIndeterminate,
        pendingIntent = notification.contentIntent
    )
}*/

fun StatusBarNotification.toNotificationItem(
    appName: String?,
    priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority = ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM,
    category: ly.com.tahaben.notification_filter_domain.model.NotificationCategory = ly.com.tahaben.notification_filter_domain.model.NotificationCategory.OTHER
): NotificationItem {
    val notification = notification
    val extras = notification.extras
    val title = extras?.getString(Notification.EXTRA_TITLE)?.toString()
    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val time = sdf.format(postTime)

    return NotificationItem(
        id = key,
        appName = appName,
        title = title,
        text = text,
        time = time,
        packageName = packageName ?: "Unknown",
        timestamp = postTime,
        priority = priority,
        category = category,
        isRead = false,
    )
}