package ly.com.tahaben.notification_filter_domain.use_cases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationDashboardSummary
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.TopChatter
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import java.util.Calendar

class GetNotificationDashboardSummary(
    private val repository: NotificationRepository,
    private val preferences: Preferences
) {

    operator fun invoke(): Flow<NotificationDashboardSummary> {
        return repository.getNotifications()
            .debounce(DEBOUNCE_MS)
            .map { notifications ->
            val nowMillis = System.currentTimeMillis()
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val floodWindowStart = nowMillis - 10 * 60 * 1000L
            val standingCutoff = nowMillis - preferences.getStandingThresholdHours() * 3600_000L

            val todayNotifications = notifications.filter { it.timestamp >= dayStart }
            val unread = notifications.filter { !it.isRead }
            val standing = notifications.filter {
                it.timestamp < standingCutoff && !it.isRead && it.priority != NotificationPriority.CRITICAL
            }
            val recent = notifications.filter { it.timestamp >= floodWindowStart }
            val isFlooding = recent.size >= preferences.getFloodThreshold()

            val priorityBreakdown = NotificationPriority.entries.associateWith { p ->
                notifications.count { it.priority == p }
            }
            val categoryBreakdown = NotificationCategory.entries.associateWith { c ->
                notifications.count { it.category == c }
            }
            val topChatters = notifications
                .groupBy { it.packageName }
                .map { (packageName, items) ->
                    TopChatter(
                        appName = items.first().appName ?: packageName,
                        packageName = packageName,
                        count = items.size
                    )
                }
                .sortedByDescending { it.count }
                .take(5)

            val healthScore = computeHealthScore(
                standingCount = standing.size,
                total = notifications.size,
                isFlooding = isFlooding,
                criticalUnread = unread.count { it.priority == NotificationPriority.CRITICAL }
            )

            NotificationDashboardSummary(
                totalToday = todayNotifications.size,
                unreadCount = unread.size,
                standingCount = standing.size,
                isFlooding = isFlooding,
                priorityBreakdown = priorityBreakdown,
                categoryBreakdown = categoryBreakdown,
                topChatters = topChatters,
                healthScore = healthScore
            )
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    private fun computeHealthScore(
        standingCount: Int,
        total: Int,
        isFlooding: Boolean,
        criticalUnread: Int
    ): Int {
        var score = 100
        if (total > 0) score -= (standingCount.coerceAtMost(20) * 2)
        if (isFlooding) score -= 25
        score -= (criticalUnread.coerceAtMost(5) * 5)
        return score.coerceIn(0, 100)
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
    }
}
