package ly.com.tahaben.notification_filter_domain.delivery

enum class QueueOrder { NEWEST_FIRST, OLDEST_FIRST }

/**
 * Per-app queue preference. Applies only within one notification priority.
 * [rank]: a larger rank is delivered earlier (default 0). Newest first is the default order.
 */
data class AppQueuePreference(val order: QueueOrder = QueueOrder.NEWEST_FIRST, val rank: Int = 0)
