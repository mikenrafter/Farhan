package ly.com.tahaben.notification_filter_domain.model

data class NotificationDashboardSummary(
    val totalToday: Int = 0,
    val unreadCount: Int = 0,
    val standingCount: Int = 0,
    val isFlooding: Boolean = false,
    val priorityBreakdown: Map<NotificationPriority, Int> = emptyMap(),
    val categoryBreakdown: Map<NotificationCategory, Int> = emptyMap(),
    val topChatters: List<Pair<String, Int>> = emptyList(),
    val healthScore: Int = 100
)
