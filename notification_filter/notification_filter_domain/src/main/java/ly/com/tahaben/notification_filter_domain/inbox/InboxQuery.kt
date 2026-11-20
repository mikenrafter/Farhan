package ly.com.tahaben.notification_filter_domain.inbox

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

data class InboxSelection(
    val cleared: Boolean = false,
    val priority: NotificationPriority? = null,
    val search: String = "",
    val standingCutoff: Long = 0,
)

/** One bounded SQL page. Filters apply before paging. */
data class InboxQuery(val selection: InboxSelection, val offset: Int = 0, val limit: Int = 100) {
    init { require(offset >= 0 && limit in 1..200) }
}

data class DashboardQuery(val dayStart: Long, val floodStart: Long, val standingCutoff: Long, val floodThreshold: Int)
