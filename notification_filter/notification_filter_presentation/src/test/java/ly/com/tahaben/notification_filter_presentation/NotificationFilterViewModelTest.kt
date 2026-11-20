package ly.com.tahaben.notification_filter_presentation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.InboxQuery
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationFilterViewModelTest {
    // why: the ViewModel clears using the displayed Low query and returns to the first page.
    // catches: calling the old global clear, forwarding cutoff 0, or leaving the user on an empty later page.
    @Test fun `clear Low forwards the displayed cutoff and returns to page one`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        runCurrent()
        inbox.emit(List(101) { item("low-$it", LOW) })
        assertThat(vm.state.hasNextPage).isTrue()
        vm.nextPage()
        runCurrent()
        inbox.emit(listOf(item("last-low", LOW)))
        val shown = inbox.queries.last()
        assertThat(shown.offset).isEqualTo(100)
        assertThat(shown.selection.priority).isEqualTo(LOW)
        assertThat(shown.selection.standingCutoff).isGreaterThan(0L)

        vm.state.dispatchClear(vm::onEvent)
        runCurrent()

        assertThat(inbox.clears).containsExactly(
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Priority(LOW), shown.selection.standingCutoff),
        )
        assertThat(vm.state.page).isEqualTo(0)
        assertThat(inbox.queries.last().offset).isEqualTo(0)
        assertThat(inbox.queries.last().selection.priority).isEqualTo(LOW)
    }

    // why: the Cleared tab includes all cleared priorities after switching from Low.
    // catches: retaining Low in either the cleared list query or the clear event.
    @Test fun `opening Cleared from Low lists and clears all history priorities`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        runCurrent()
        inbox.emit(listOf(item("live-low", LOW)))

        vm.onEvent(NotificationFilterEvent.OnToggleClearedView)
        runCurrent()

        val shown = inbox.queries.last().selection
        assertThat(shown.cleared).isTrue()
        assertThat(shown.priority).isNull()
        assertThat(vm.state.priorityFilter).isNull()
        val history = listOf(item("old-low", LOW).copy(isCleared = true), item("old-high", HIGH).copy(isCleared = true))
        inbox.emit(history)
        assertThat(vm.state.clearedNotifications).containsExactlyElementsIn(history)
        vm.state.dispatchClear(vm::onEvent)
        runCurrent()
        assertThat(inbox.clears).containsExactly(
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Cleared, shown.standingCutoff),
        )
    }

    // why: a pending query shows neither the old category nor the empty result for the new one.
    // catches: leaving old rows visible or marking the new query loaded before its first emission.
    @Test fun `chip change stays loading until the new query emits even an empty page`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        runCurrent()
        inbox.emit(listOf(item("live-low", LOW)))
        assertThat(vm.state.isLoading).isFalse()
        assertThat(vm.state.filteredNotifications.map { it.id }).containsExactly("live-low")

        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(HIGH))
        runCurrent()
        assertThat(vm.state.isLoading).isTrue()
        assertThat(vm.state.filteredNotifications).isEmpty()

        inbox.emit(emptyList())
        assertThat(vm.state.isLoading).isFalse()
        assertThat(vm.state.filteredNotifications).isEmpty()
    }

    private fun withInbox(test: suspend TestScope.(InboxFixture) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val inbox = InboxFixture(this)
            try {
                test(inbox)
            } finally {
                inbox.viewModel.stopObserving()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class InboxFixture(private val scope: TestScope) {
        val queries = mutableListOf<InboxQuery>()
        val clears = mutableListOf<NotificationFilterEvent.OnClearNotifications>()
        private val pages = mutableListOf<MutableSharedFlow<List<NotificationItem>>>()
        private val repository = port<NotificationRepository> { call, args ->
            check(call == "observeInbox")
            queries += args.single() as InboxQuery
            MutableSharedFlow<List<NotificationItem>>(replay = 1).also { pages += it }
        }
        val viewModel = NotificationFilterViewModel(
            useCases = { error("Unexpected call to another use case") },
            sharedPref = port<Preferences> { call, _ ->
                when (call) {
                    "loadShouldShowcase" -> false
                    "getStandingThresholdHours" -> 3
                    else -> error("Unexpected preference call $call")
                }
            },
            repository = repository,
            serviceUtil = port<ServiceUtil> { call, _ ->
                when (call) {
                    "isNotificationServiceEnabled", "isNotificationAccessPermissionGranted" -> true
                    "getNotificationActionTitles" -> emptyList<String>()
                    else -> error("Unexpected service call $call")
                }
            },
            clearInbox = { scope, cutoff, search ->
                clears += NotificationFilterEvent.OnClearNotifications(scope, cutoff, search)
            },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        fun emit(rows: List<NotificationItem>) {
            check(pages.last().tryEmit(rows))
            scope.runCurrent()
        }
    }

    companion object {
        private fun item(id: String, priority: NotificationPriority) =
            NotificationItem(id, null, null, null, "", "test", priority = priority)

        private inline fun <reified T> port(crossinline call: (String, List<Any?>) -> Any?): T =
            T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args.orEmpty().toList())
            })!!
    }
}
