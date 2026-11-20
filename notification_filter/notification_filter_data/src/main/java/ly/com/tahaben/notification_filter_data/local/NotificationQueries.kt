package ly.com.tahaben.notification_filter_data.local

/** SQL shared with executable SQLite query fixtures. All row results are bounded before domain mapping. */
object NotificationQueries {
    const val LIVE_SNOOZES = "SELECT * FROM NotificationItemEntity WHERE snoozeDeadline IS NOT NULL AND isDelivered = 0 AND isCleared = 0 AND deliveryInvalidated = 0"
    const val COMPLETE_SNOOZE_RETURN = "UPDATE notificationitementity SET isDelivered = 1, snoozeDeadline = NULL, snoozePriority = NULL, snoozeBootCount = NULL WHERE id = :id"
    const val PENDING_UP_TO_PRIORITY = "SELECT * FROM notificationitementity WHERE isDelivered = 0 AND isCleared = 0 AND deliveryInvalidated = 0 AND snoozeDeadline IS NULL AND priority <= :priorityLevel ORDER BY priority ASC, timestamp ASC"
    const val PENDING_HEAD = "SELECT * FROM notificationitementity WHERE creatorPackage = :packageName AND priority = :priority AND isDelivered = 0 AND isCleared = 0 AND deliveryInvalidated = 0 AND snoozeDeadline IS NULL ORDER BY CASE WHEN :oldest THEN timestamp END ASC, CASE WHEN NOT :oldest THEN timestamp END DESC, id ASC LIMIT 1"
    const val PENDING_GROUPS = "SELECT creatorPackage AS packageName, priority FROM notificationitementity WHERE isDelivered = 0 AND isCleared = 0 AND deliveryInvalidated = 0 AND snoozeDeadline IS NULL AND priority <= :maxPriority GROUP BY creatorPackage, priority ORDER BY priority ASC, creatorPackage ASC LIMIT 4096"
    const val INVALIDATE = "UPDATE notificationitementity SET deliveryInvalidated = 1, priorityReason = 'Not proxied after intake overflow. Open the source app.' WHERE sourceSession = :session AND sourceEventOrder > 0 AND sourceEventOrder <= :order AND isDelivered = 0 AND isCleared = 0"
    const val INBOX = """SELECT * FROM notificationitementity WHERE isCleared = :cleared
        AND (:priority IS NULL OR (CASE WHEN isRead = 0 AND priority != 1 AND timestamp < :standingCutoff THEN MIN(priority + 1, 4) ELSE priority END) = :priority)
        AND (:search = '' OR instr(lower(coalesce(title,'') || ' ' || coalesce(text,'') || ' ' || coalesce(appName,'') || ' ' || creatorPackage), lower(:search)) > 0)
        ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset"""
    const val COUNTS = """SELECT COUNT(*) AS total,
        coalesce(SUM(CASE WHEN timestamp >= :dayStart THEN 1 ELSE 0 END),0) AS today,
        coalesce(SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END),0) AS unread,
        coalesce(SUM(CASE WHEN isRead = 0 AND priority != 1 AND timestamp < :standingCutoff THEN 1 ELSE 0 END),0) AS standing,
        coalesce(SUM(CASE WHEN timestamp >= :floodStart THEN 1 ELSE 0 END),0) AS recent,
        coalesce(SUM(CASE WHEN isRead = 0 AND priority = 1 THEN 1 ELSE 0 END),0) AS criticalUnread
        FROM notificationitementity WHERE isCleared = 0"""
    const val PRIORITIES = "SELECT priority AS label, COUNT(*) AS count FROM notificationitementity WHERE isCleared = 0 GROUP BY priority"
    const val CATEGORIES = "SELECT category AS label, COUNT(*) AS count FROM notificationitementity WHERE isCleared = 0 GROUP BY category"
    const val CHATTERS = "SELECT creatorPackage AS packageName, coalesce(MAX(appName), creatorPackage) AS appName, COUNT(*) AS count FROM notificationitementity WHERE isCleared = 0 GROUP BY creatorPackage ORDER BY count DESC, creatorPackage ASC LIMIT 5"
    const val BATCHES = """WITH groups AS (SELECT creatorPackage, timestamp / :windowMillis AS bucket, COUNT(*) AS count,
        MIN(priority) AS priority, MAX(timestamp) AS latestTimestamp, MIN(timestamp) AS oldestTimestamp
        FROM notificationitementity WHERE isCleared = 0 GROUP BY creatorPackage, timestamp / :windowMillis
        ORDER BY priority ASC, latestTimestamp DESC, creatorPackage ASC LIMIT :limit)
        SELECT groups.creatorPackage AS packageName, groups.bucket * :windowMillis AS rangeStart,
        (groups.bucket + 1) * :windowMillis AS rangeEnd, groups.count, groups.priority, groups.latestTimestamp, groups.oldestTimestamp,
        latest.appName, latest.category, latest.title, latest.text
        FROM groups JOIN notificationitementity AS latest ON latest.id = (SELECT id FROM notificationitementity AS item
        WHERE item.isCleared = 0 AND item.creatorPackage = groups.creatorPackage AND item.timestamp / :windowMillis = groups.bucket
        ORDER BY item.timestamp DESC, item.id DESC LIMIT 1)
        ORDER BY groups.priority ASC, groups.latestTimestamp DESC, groups.creatorPackage ASC"""
    const val BATCH_ITEMS = """SELECT * FROM notificationitementity WHERE isCleared = 0 AND creatorPackage = :packageName
        AND timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset"""
    const val STANDING = """SELECT * FROM notificationitementity WHERE isCleared = 0 AND isRead = 0 AND priority != 1 AND timestamp < :cutoff
        ORDER BY timestamp ASC, id ASC LIMIT :limit"""
}

data class DashboardCounts(val total: Int, val today: Int, val unread: Int, val standing: Int, val recent: Int, val criticalUnread: Int)
data class PriorityCount(val label: Int, val count: Int)
data class CategoryCount(val label: String, val count: Int)
data class ChatterCount(val packageName: String, val appName: String, val count: Int)
data class BatchHeader(val packageName: String, val rangeStart: Long, val rangeEnd: Long, val count: Int, val priority: Int,
    val latestTimestamp: Long, val oldestTimestamp: Long, val appName: String?, val category: String, val title: String?, val text: String?)
