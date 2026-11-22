package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.InboxSelection
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class ClearInbox(
    private val repository: NotificationRepository,
    private val core: NotificationCore,
    private val service: ServiceUtil,
) {
    // CLAIM(inbox-clear-routing): a scoped clear removes actions only for IDs committed by storage [ClearInboxTest].
    suspend operator fun invoke(scope: InboxClearScope, standingCutoff: Long, search: String) = when (scope) {
        // "All" on the Inbox tab clears the Inbox rows only. The Cleared tab has its own scope for the history.
        InboxClearScope.All -> clearScoped(InboxSelection(search = search, standingCutoff = standingCutoff))
        is InboxClearScope.Priority -> clearScoped(InboxSelection(priority = scope.priority, search = search, standingCutoff = standingCutoff))
        InboxClearScope.Cleared -> clearScoped(InboxSelection(cleared = true, search = search, standingCutoff = standingCutoff))
    }

    // ↳ CLAIM(inbox-clear-routing)
    private suspend fun clearScoped(selection: InboxSelection) =
        core.coordinator.clearScoped(
            delete = { repository.deleteInboxNotifications(selection) },
            clearActions = service::clearNotificationActions,
        )

    suspend operator fun invoke() = core.coordinator.clearAll {
        repository.deleteAllNotificationsFromDB()
        service.clearNotificationActions()
    }
}
