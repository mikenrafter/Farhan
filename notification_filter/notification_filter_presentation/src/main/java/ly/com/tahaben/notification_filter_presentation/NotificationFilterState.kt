package ly.com.tahaben.notification_filter_presentation

import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSection

data class InboxPageKey(
    val cleared: Boolean,
    val priority: NotificationPriority?,
    val page: Int,
)

data class NotificationFilterState(
    val isServiceEnabled: Boolean = false,
    val isPermissionGranted: Boolean = true,
    val isFirstTimeOpened: Boolean = false,
    val filteredNotifications: List<NotificationItem> = emptyList(),
    val allNotifications: List<NotificationItem> = emptyList(),
    val clearedNotifications: List<NotificationItem> = emptyList(),
    val priorityFilter: NotificationPriority? = null,
    val showCleared: Boolean = false,
    val isLoading: Boolean = true,
    val shownKey: InboxPageKey? = null,
    val inboxSearch: String = "",
    val standingCutoff: Long = 0,
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val notificationActions: Map<String, List<String>> = emptyMap(),
    val displayPriorities: Map<String, NotificationPriority> = emptyMap(),
    /** Waiting, not-sent and sent sections of the shown page, with chats folded into threads. */
    val sections: List<InboxSection> = emptyList(),
    /** Row ids and thread keys that are opened up. */
    val expandedThreads: Set<String> = emptySet(),
    /** Ids of the rows the user has ticked, on the Inbox tab only. */
    val selected: Set<String> = emptySet(),
)

internal val NotificationFilterState.clearScope: InboxClearScope
    get() = when {
        showCleared -> InboxClearScope.Cleared
        priorityFilter != null -> InboxClearScope.Priority(priorityFilter)
        else -> InboxClearScope.All
    }

// CLAIM(inbox-clear-event): the label and click share the selected scope, and the click captures the page cutoff [NotificationFilterClearEventTest].
internal fun NotificationFilterState.dispatchClear(onEvent: (NotificationFilterEvent) -> Unit) {
    onEvent(NotificationFilterEvent.OnClearNotifications(clearScope, standingCutoff, inboxSearch))
}

// ↳ CLAIM(inbox-clear-event)
internal val NotificationFilterState.clearLabel: Int
    get() = when (val scope = clearScope) {
        InboxClearScope.All -> ly.com.tahaben.core.R.string.clear_all
        InboxClearScope.Cleared -> R.string.clear_inbox_history
        is InboxClearScope.Priority -> when (scope.priority) {
            NotificationPriority.CRITICAL -> R.string.clear_inbox_critical
            NotificationPriority.HIGH -> R.string.clear_inbox_high
            NotificationPriority.MEDIUM -> R.string.clear_inbox_medium
            NotificationPriority.LOW -> R.string.clear_inbox_low
        }
    }
