package ly.com.tahaben.notification_filter_presentation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.CRITICAL
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM
import org.junit.Test

class NotificationFilterClearEventTest {
    // why: CLAIM(inbox-clear-event) carries the visible filter and does not limit the operation to its page.
    // catches: emitting All unconditionally, losing the priority on the Cleared tab, or reading the page as the scope.
    @Test fun `clear click dispatches the selected tab scope`() {
        val events = mutableListOf<NotificationFilterEvent>()
        NotificationFilterState().dispatchClear { events += it }
        NotificationFilterState(priorityFilter = LOW, standingCutoff = 500, page = 2).dispatchClear { events += it }
        NotificationFilterState(showCleared = true, priorityFilter = HIGH, standingCutoff = 700).dispatchClear { events += it }
        NotificationFilterState(showCleared = true).dispatchClear { events += it }

        assertThat(events).containsExactly(
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.All, 0),
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Priority(LOW), 500),
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Cleared(HIGH), 700),
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Cleared(null), 0),
        ).inOrder()
    }

    // why: CLAIM(inbox-clear-event) names the scope before the user presses the button.
    // catches: a generic Clear all label on any scoped view, or focusing on the prior priority in Cleared.
    @Test fun `clear button label names the selected tab`() {
        assertThat(NotificationFilterState().clearLabel).isEqualTo(R.string.clear_inbox_all)
        assertThat(NotificationFilterState(priorityFilter = CRITICAL).clearLabel).isEqualTo(R.string.clear_inbox_critical)
        assertThat(NotificationFilterState(priorityFilter = HIGH).clearLabel).isEqualTo(R.string.clear_inbox_high)
        assertThat(NotificationFilterState(priorityFilter = MEDIUM).clearLabel).isEqualTo(R.string.clear_inbox_medium)
        assertThat(NotificationFilterState(priorityFilter = LOW).clearLabel).isEqualTo(R.string.clear_inbox_low)
        assertThat(NotificationFilterState(showCleared = true, priorityFilter = HIGH).clearLabel)
            .isEqualTo(R.string.clear_inbox_history)
    }
}
