package ly.com.tahaben.notification_filter_presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.delivery.Experiments
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.InboxQuery
import ly.com.tahaben.notification_filter_domain.inbox.InboxSelection
import ly.com.tahaben.notification_filter_domain.inbox.NotificationInboxFilter
import ly.com.tahaben.notification_filter_domain.inbox.OtpCodeExtractor
import ly.com.tahaben.notification_filter_domain.priority.OtpRuleConfig
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import ly.com.tahaben.notification_filter_presentation.components.NotificationDateLabeler
import ly.com.tahaben.notification_filter_presentation.inbox.InboxEntryBuilder
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSection
import timber.log.Timber
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class NotificationFilterViewModel internal constructor(
    private val useCases: () -> NotificationFilterUseCases,
    private val sharedPref: Preferences,
    private val repository: NotificationRepository,
    private val serviceUtil: ServiceUtil,
    private val clearInbox: suspend (InboxClearScope, Long, String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        notificationFilterUseCases: NotificationFilterUseCases,
        sharedPref: Preferences,
        repository: NotificationRepository,
        serviceUtil: ServiceUtil,
    ) : this(
        { notificationFilterUseCases }, sharedPref, repository, serviceUtil,
        { scope, cutoff, search -> notificationFilterUseCases.clearInbox(scope, cutoff, search) },
        Dispatchers.IO,
    )

    var state by mutableStateOf(NotificationFilterState())
        private set

    private var getNotificationsJob: Job? = null

    init {
        checkServiceStats()
        isFirstTimeOpened()
    }

    fun checkServiceStats() {
        viewModelScope.launch {
            state = state.copy(
                isServiceEnabled = serviceUtil.isNotificationServiceEnabled()
            )
        }
        checkIfNotificationAccessGranted()
    }

    private fun checkIfNotificationAccessGranted() {
        state = state.copy(
            isPermissionGranted = serviceUtil.isNotificationAccessPermissionGranted()
        )
    }

    fun startNotificationService() {
        useCases().startNotificationService()
        setServiceEnabled()
    }

    fun startObserving() { getNotifications() }
    fun stopObserving() { getNotificationsJob?.cancel() }
    fun nextPage() { state = state.copy(page = state.page + 1); getNotifications() }
    fun previousPage() { state = state.copy(page = (state.page - 1).coerceAtLeast(0)); getNotifications() }

    private fun getNotifications() {
        getNotificationsJob?.cancel()
        val key = InboxPageKey(state.showCleared, state.priorityFilter, state.page)
        // CLAIM(inbox-resume): the same displayed page stays visible until its refresh emits [NotificationFilterViewModelTest].
        if (key != state.shownKey) {
            state = state.copy(
                isLoading = true, shownKey = null, hasNextPage = false, selected = emptySet(),
                filteredNotifications = emptyList(), clearedNotifications = emptyList(), sections = emptyList(),
            )
        }
        val selection = InboxSelection(
            cleared = key.cleared, priority = key.priority,
            standingCutoff = System.currentTimeMillis() - sharedPref.getStandingThresholdHours() * 3_600_000L,
        )
        val query = InboxQuery(selection, offset = key.page * 100, limit = 101)
        getNotificationsJob = repository.observeInbox(query)
            // Display priorities, action lookups, grouping and date labels are per-row work: keep them off Main.
            .map { rows -> prepare(rows, selection) }
            .flowOn(ioDispatcher)
            .onEach { prepared ->
                val observed = state.copy(
                    isLoading = false,
                    shownKey = key,
                    hasNextPage = prepared.hasNextPage,
                    inboxSearch = selection.search,
                    standingCutoff = selection.standingCutoff,
                    displayPriorities = prepared.displayPriorities,
                    sections = prepared.sections,
                    // Ticks follow rows that are still on the page, so "Clear N" never counts a row that is gone.
                    selected = state.selected.intersect(prepared.page.mapTo(HashSet()) { it.id }),
                )
                state = if (selection.cleared) {
                    observed.copy(clearedNotifications = prepared.page, notificationActions = emptyMap())
                } else {
                    observed.copy(
                        isServiceEnabled = true,
                        allNotifications = prepared.page,
                        filteredNotifications = prepared.page,
                        notificationActions = prepared.actions,
                    )
                }
            }.launchIn(viewModelScope)
    }

    private class PreparedPage(
        val page: List<NotificationItem>,
        val hasNextPage: Boolean,
        val displayPriorities: Map<String, NotificationPriority>,
        val actions: Map<String, List<String>>,
        val sections: List<InboxSection>,
    )

    /** The lookup that raises fresh verification codes, or one that finds none when the feature is off. */
    private fun codeLookup(cleared: Boolean): (NotificationItem) -> String? {
        if (cleared || !sharedPref.isExperimentEnabled(Experiments.OTP_CODES)) return { null }
        // The same scope as the rule, and only fresh codes: an old code does not stay pinned at the top.
        val config = OtpRuleConfig.from(sharedPref)
        val now = System.currentTimeMillis()
        return { item ->
            if (config.covers(item.packageName) && now - item.timestamp <= OtpRuleConfig.FRESH_MILLIS) {
                OtpCodeExtractor.extract(item.title, item.text)
            } else null
        }
    }

    private fun prepare(rows: List<NotificationItem>, selection: InboxSelection): PreparedPage {
        val page = rows.take(100)
        val displayPriorities = page.associate { item ->
            item.id to NotificationInboxFilter.effectivePriorityAtCutoff(item, selection.standingCutoff)
        }
        val actions = if (selection.cleared) emptyMap() else page.associate { item ->
            item.id to serviceUtil.getNotificationActionTitles(item.id)
        }.filterValues { it.isNotEmpty() }
        val sections = InboxEntryBuilder.build(
            notifications = page,
            displayPriority = { item -> displayPriorities[item.id] ?: item.priority },
            actionTitles = { item -> actions[item.id].orEmpty() },
            labeler = NotificationDateLabeler(),
            otpCode = codeLookup(selection.cleared),
        )
        return PreparedPage(page, rows.size > 100, displayPriorities, actions, sections)
    }

    fun onEvent(event: NotificationFilterEvent) {
        when (event) {
            is NotificationFilterEvent.OnOpenNotification -> {
                Timber.d("notification click viewmodel")
                useCases().openNotification(
                    event.notificationItem.id,
                    event.notificationItem.packageName
                )
                // Opening only marks the row opened. Clearing from the shade or in the app is what removes it.
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases().markNotificationRead(event.notificationItem.id)
                    }
                }
            }
            is NotificationFilterEvent.OnDismissNotification -> {
                deleteNotificationData(event.notificationItem)
            }
            is NotificationFilterEvent.OnToggleSelected -> {
                val current = state.selected
                state = state.copy(
                    selected = if (event.ids.all { it in current }) current - event.ids.toSet()
                    else current + event.ids,
                )
            }
            NotificationFilterEvent.OnClearSelected -> {
                val ids = state.selected
                state.filteredNotifications.filter { it.id in ids }.forEach(::deleteNotificationData)
                state = state.copy(selected = emptySet())
            }
            is NotificationFilterEvent.OnToggleThread -> {
                val expanded = state.expandedThreads
                state = state.copy(
                    expandedThreads = if (event.threadKey in expanded) expanded - event.threadKey
                    else expanded + event.threadKey
                )
            }
            is NotificationFilterEvent.OnClearNotifications -> {
                viewModelScope.launch {
                    withContext(ioDispatcher) {
                        clearInbox(event.scope, event.standingCutoff, event.search)
                    }
                    if (state.clearScope == event.scope && state.page != 0) {
                        state = state.copy(page = 0)
                        if (getNotificationsJob?.isActive == true) getNotifications()
                    }
                }
            }

            is NotificationFilterEvent.OnExcludeAppClick -> {
                useCases().setAppPriorityOverride(
                    event.appPackageName,
                    NotificationPriority.CRITICAL,
                )
            }

            is NotificationFilterEvent.OnLaunchAppInfoClick -> {
                serviceUtil.launchAppInfo(event.appPackageName)
            }

            is NotificationFilterEvent.OnSetPriorityFilter -> {
                state = state.copy(
                    showCleared = false, priorityFilter = event.priority, page = 0,
                )
                getNotifications()
            }

            NotificationFilterEvent.OnToggleClearedView -> {
                state = state.copy(
                    showCleared = !state.showCleared, priorityFilter = null, page = 0,
                )
                getNotifications()
            }

            is NotificationFilterEvent.OnFireNotificationAction -> {
                serviceUtil.fireNotificationAction(event.notificationId, event.actionIndex)
            }
        }
    }

    private fun deleteNotificationData(notificationItem: NotificationItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                useCases().deleteNotificationFromDB(
                    notificationItem
                )
                useCases().deleteNotificationIntentFromHashmap(
                    notificationItem.id
                )
            }
        }
    }

    private fun setServiceEnabled() {
        useCases().setServiceState(true)
        state = state.copy(
            isServiceEnabled = true
        )
    }

    fun isFirstTimeOpened() {
        state = state.copy(
            isFirstTimeOpened = sharedPref.loadShouldShowcase()
        )
        Timber.d("sholdShowcase ${state.isFirstTimeOpened}")
    }

    fun setShowcased() {
        sharedPref.saveShouldShowcase(false)
        state = state.copy(
            isFirstTimeOpened = false
        )
    }

    fun openAppSettings() {
        useCases().openAppSettings()
    }

    companion object {
        private const val NOTIFICATION_LIST_DEBOUNCE_MS = 150L
    }
}