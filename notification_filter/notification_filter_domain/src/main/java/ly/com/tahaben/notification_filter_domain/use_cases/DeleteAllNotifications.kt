package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class DeleteAllNotifications(
    private val repository: NotificationRepository,
    private val core: NotificationCore,
    private val service: ServiceUtil,
) {
    // CLAIM(inbox-clear-routing): a scoped clear removes actions only for IDs committed by storage [DeleteAllNotificationsTest].
    suspend operator fun invoke(scope: InboxClearScope, standingCutoff: Long) = when (scope) {
        InboxClearScope.All -> invoke()
        is InboxClearScope.Priority -> clearScoped(false, scope.priority, standingCutoff)
        is InboxClearScope.Cleared -> clearScoped(true, scope.priority, standingCutoff)
    }

    // ↳ CLAIM(inbox-clear-routing)
    private suspend fun clearScoped(cleared: Boolean, priority: NotificationPriority?, standingCutoff: Long) =
        core.coordinator.clearScoped(
            delete = { repository.deleteInboxNotifications(cleared, priority, standingCutoff) },
            clearActions = service::clearNotificationActions,
        )

    suspend operator fun invoke() = core.coordinator.clearAll {
        repository.deleteAllNotificationsFromDB()
        service.clearNotificationActions()
    }
}
