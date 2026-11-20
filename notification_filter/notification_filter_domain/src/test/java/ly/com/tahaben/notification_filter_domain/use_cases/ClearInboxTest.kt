package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryHarness
import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.InboxSelection
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.schedule.FakeScheduleBookStore
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleBook
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot
import ly.com.tahaben.notification_filter_domain.schedule.TEN_AM_DENVER
import ly.com.tahaben.notification_filter_domain.schedule.threeWindows
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import org.junit.Test
import java.lang.reflect.Proxy

class ClearInboxTest {
    // why: CLAIM(inbox-clear-routing) keeps distinct storage and action operations for All, a priority, and Cleared.
    // catches: global delete for a scoped request, dropping the cutoff or cleared flag, and clearing unrelated actions.
    @Test fun `clear forwards the scope and drops only actions for deleted ids`() = runTest {
        val calls = mutableListOf<List<Any?>>()
        val repository = recordingPort<NotificationRepository> { name, args ->
            calls.add(listOf(name) + args)
            when (name) {
                "deleteInboxNotifications" -> if ((args.single() as InboxSelection).cleared) listOf("history") else listOf("low-1", "low-2")
                "deleteAllNotificationsFromDB" -> Unit
                else -> error("Unexpected repository call $name")
            }
        }
        val service = recordingPort<ServiceUtil> { name, args ->
            check(name == "clearNotificationActions")
            calls.add(listOf(name) + args)
            Unit
        }
        val windows = threeWindows(8)
        val h = DeliveryHarness(windows)
        val book = FakeScheduleBookStore(ScheduleSnapshot(ScheduleBook(windows), DeliveryBudgetState()))
        val core = NotificationCore(book, h.rows, h.budget, h.poster, h.environment, TEN_AM_DENVER) {}
        val clear = ClearInbox(repository, core, service)

        clear(InboxClearScope.Priority(LOW), standingCutoff = 500, search = "needle")
        clear(InboxClearScope.Cleared, standingCutoff = 700, search = "")
        clear(InboxClearScope.All, standingCutoff = 900, search = "")

        assertThat(calls).containsExactly(
            listOf("deleteInboxNotifications", InboxSelection(priority = LOW, search = "needle", standingCutoff = 500)),
            listOf("clearNotificationActions", listOf("low-1", "low-2")),
            listOf("deleteInboxNotifications", InboxSelection(cleared = true, standingCutoff = 700)),
            listOf("clearNotificationActions", listOf("history")),
            listOf("deleteAllNotificationsFromDB"),
            listOf("clearNotificationActions"),
        ).inOrder()
    }

    private inline fun <reified T> recordingPort(crossinline record: (String, List<Any?>) -> Any?): T =
        T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val parameters = args.orEmpty().toList()
            // Kotlin passes a continuation to suspend interface methods.
            record(method.name, parameters.takeWhile { it !is kotlin.coroutines.Continuation<*> })
        })!!
}
