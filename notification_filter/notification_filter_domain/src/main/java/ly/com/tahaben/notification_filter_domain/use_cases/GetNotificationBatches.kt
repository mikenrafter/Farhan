package ly.com.tahaben.notification_filter_domain.use_cases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import ly.com.tahaben.notification_filter_domain.model.NotificationBatch
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository

class GetNotificationBatches(
    private val repository: NotificationRepository,
    private val preferences: Preferences
) {

    operator fun invoke(): Flow<List<NotificationBatch>> {
        repository.observeBatchHeaders((preferences.getBatchWindowMinutes().coerceAtLeast(1) * 60_000L), 5)?.let { return it }
        return repository.getNotifications()
            .debounce(DEBOUNCE_MS)
            .map { notifications ->
            val windowMillis = preferences.getBatchWindowMinutes() * 60 * 1000L
            notifications
                .sortedByDescending { it.timestamp }
                .groupBy { item ->
                    val bucket = if (windowMillis > 0) item.timestamp / windowMillis else item.timestamp
                    "${item.packageName}_$bucket"
                }
                .map { (key, items) ->
                    val sorted = items.sortedByDescending { it.timestamp }
                    val highestPriority = sorted.minByOrNull { it.priority.level }?.priority
                        ?: NotificationPriority.MEDIUM
                    NotificationBatch(
                        key = key,
                        appName = sorted.first().appName ?: sorted.first().packageName,
                        packageName = sorted.first().packageName,
                        category = sorted.first().category,
                        priority = highestPriority,
                        count = sorted.size,
                        latestTimestamp = sorted.first().timestamp,
                        oldestTimestamp = sorted.last().timestamp,
                        preview = sorted.first().title?.let { title ->
                            val text = sorted.first().text
                            if (text != null) "$title — $text" else title
                        } ?: sorted.first().text,
                        items = sorted
                    )
                }
                .sortedWith(
                    compareBy<NotificationBatch> { it.priority.level }
                        .thenByDescending { it.latestTimestamp }
                )
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
    }
}
