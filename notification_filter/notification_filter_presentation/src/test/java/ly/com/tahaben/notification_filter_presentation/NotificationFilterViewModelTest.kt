package ly.com.tahaben.notification_filter_presentation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Priority(LOW), shown.selection.standingCutoff, search = ""),
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
            NotificationFilterEvent.OnClearNotifications(InboxClearScope.Cleared, shown.standingCutoff, search = ""),
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

    // why: CLAIM(inbox-resume) keeps the presented page while the same page refreshes with a new cutoff.
    // catches: blanking on every subscription or comparing the standing cutoff as part of the page key.
    @Test fun `resuming the same page keeps its rows until the refresh emits`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        inbox.emit(listOf(item("before-resume", LOW)))
        val displayed = vm.state
        val previousQuery = inbox.queries.last()

        vm.stopObserving()
        inbox.standingThresholdHours = 4
        vm.startObserving()
        runCurrent()

        val refreshedQuery = inbox.queries.last()
        assertThat(inbox.queries).hasSize(2)
        assertThat(refreshedQuery.selection.priority).isEqualTo(LOW)
        assertThat(refreshedQuery.selection.standingCutoff).isLessThan(previousQuery.selection.standingCutoff)
        assertThat(vm.state.isLoading).isFalse()
        assertThat(vm.state).isEqualTo(displayed)

        inbox.emit(listOf(item("after-resume", LOW)))
        assertThat(vm.state.filteredNotifications.map { it.id }).containsExactly("after-resume")
        assertThat(vm.state.standingCutoff).isEqualTo(refreshedQuery.selection.standingCutoff)
    }

    // why: changes to any page key field hide the preceding rows while the new query is pending.
    // catches: omitting priority, page, or the Cleared flag from the comparison.
    @Test fun `changing chip page or Cleared tab blanks the prior rows`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        inbox.emit(listOf(item("low", LOW)))

        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(null))
        assertLoadingWithoutRows(vm.state)
        inbox.emit(List(101) { item("all-$it", HIGH) })
        assertThat(vm.state.hasNextPage).isTrue()

        vm.nextPage()
        assertLoadingWithoutRows(vm.state)
        assertThat(inbox.queries.last().offset).isEqualTo(100)
        inbox.emit(listOf(item("last-all", HIGH)))
        vm.previousPage()
        assertLoadingWithoutRows(vm.state)
        inbox.emit(listOf(item("first-all", HIGH)))

        vm.onEvent(NotificationFilterEvent.OnToggleClearedView)
        assertLoadingWithoutRows(vm.state)
        assertThat(inbox.queries.last().selection.cleared).isTrue()
        inbox.emit(listOf(item("cleared", HIGH).copy(isCleared = true)))
        assertThat(vm.state.isLoading).isFalse()
        assertThat(vm.state.clearedNotifications.map { it.id }).containsExactly("cleared")
    }

    // why: a clear can reset the requested page while observation is paused and the later page is still published.
    // catches: comparing the new request with the last requested state instead of the last shown page.
    @Test fun `resuming after a paused clear resets the shown later page`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        inbox.emit(List(101) { item("low-$it", LOW) })
        vm.nextPage()
        inbox.emit(listOf(item("later-low", LOW)))

        val finishClear = CompletableDeferred<Unit>()
        inbox.clearCompletion = finishClear
        vm.state.dispatchClear(vm::onEvent)
        runCurrent()
        assertThat(inbox.clears).hasSize(1)
        assertThat(vm.state.page).isEqualTo(1)
        vm.stopObserving()
        finishClear.complete(Unit)
        runCurrent()
        assertThat(vm.state.page).isEqualTo(0)
        assertThat(vm.state.filteredNotifications.map { it.id }).containsExactly("later-low")
        assertThat(inbox.queries).hasSize(2)

        vm.startObserving()
        assertLoadingWithoutRows(vm.state)
        assertThat(inbox.queries.last().offset).isEqualTo(0)
        inbox.emit(emptyList())
        assertThat(vm.state.isLoading).isFalse()
    }

    // why: a superseded query cannot replace loading or data for the most recently tapped chip.
    // catches: deleting the previous query cancellation in getNotifications.
    @Test fun `late emissions from the previous chip leave the current state intact`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(LOW))
        runCurrent()
        val previous = inbox.queries.lastIndex

        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(HIGH))
        runCurrent()
        assertLoadingWithoutRows(vm.state)
        val loading = vm.state
        inbox.emit(listOf(item("late-low", LOW)), request = previous)
        assertThat(vm.state).isEqualTo(loading)

        inbox.emit(listOf(item("shown-high", HIGH)))
        assertThat(vm.state.filteredNotifications.map { it.id }).containsExactly("shown-high")
        val shown = vm.state
        inbox.emit(listOf(item("another-late-low", LOW)), request = previous)
        assertThat(vm.state).isEqualTo(shown)
    }

    // why: the right-edge column ticks a row, and a conversation ticks all of its messages together.
    // catches: a thread toggle that adds only some ids, or a second tap that does not untick.
    @Test fun `selection toggles single rows and whole conversations`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.startObserving()
        runCurrent()
        inbox.emit(listOf(item("a", LOW), item("b", LOW), item("c", LOW)))

        vm.onEvent(NotificationFilterEvent.OnToggleSelected(listOf("a")))
        assertThat(vm.state.selected).containsExactly("a")
        vm.onEvent(NotificationFilterEvent.OnToggleSelected(listOf("b", "c")))
        assertThat(vm.state.selected).containsExactly("a", "b", "c")
        vm.onEvent(NotificationFilterEvent.OnToggleSelected(listOf("b", "c")))
        assertThat(vm.state.selected).containsExactly("a")
    }

    // why: ticks belong to the page that showed them; Clear N must never act on rows that left the screen.
    // catches: carrying a selection across a chip or tab change.
    @Test fun `changing the page key drops the selection`() = withInbox { inbox ->
        val vm = inbox.viewModel
        vm.startObserving()
        runCurrent()
        inbox.emit(listOf(item("a", LOW)))
        vm.onEvent(NotificationFilterEvent.OnToggleSelected(listOf("a")))
        assertThat(vm.state.selected).containsExactly("a")

        vm.onEvent(NotificationFilterEvent.OnSetPriorityFilter(HIGH))
        runCurrent()
        assertThat(vm.state.selected).isEmpty()
    }

    private fun assertLoadingWithoutRows(state: NotificationFilterState) {
        assertThat(state.isLoading).isTrue()
        assertThat(state.filteredNotifications).isEmpty()
        assertThat(state.clearedNotifications).isEmpty()
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
        var standingThresholdHours = 3
        var clearCompletion: CompletableDeferred<Unit>? = null
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
                    "getStandingThresholdHours" -> standingThresholdHours
                    "isExperimentEnabled" -> false
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
                clearCompletion?.await()
            },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        fun emit(rows: List<NotificationItem>, request: Int = pages.lastIndex) {
            check(pages[request].tryEmit(rows))
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
