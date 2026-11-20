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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.InboxQuery
import ly.com.tahaben.notification_filter_domain.inbox.InboxSelection
import ly.com.tahaben.notification_filter_domain.inbox.NotificationInboxFilter
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
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
        state = state.copy(
            isLoading = true, hasNextPage = false,
            filteredNotifications = emptyList(), clearedNotifications = emptyList(),
        )
        val selection = InboxSelection(
            cleared = state.showCleared, priority = state.priorityFilter,
            standingCutoff = System.currentTimeMillis() - sharedPref.getStandingThresholdHours() * 3_600_000L,
        )
        val query = InboxQuery(selection, offset = state.page * 100, limit = 101)
        getNotificationsJob = repository.observeInbox(query).onEach { rows ->
            val page = rows.take(100)
            val displayPriorities = page.associate { item ->
                item.id to NotificationInboxFilter.effectivePriorityAtCutoff(item, selection.standingCutoff)
            }
            val observed = state.copy(
                isLoading = false,
                hasNextPage = rows.size > 100,
                inboxSearch = selection.search,
                standingCutoff = selection.standingCutoff,
                displayPriorities = displayPriorities,
            )
            state = if (selection.cleared) {
                observed.copy(clearedNotifications = page, notificationActions = emptyMap())
            } else {
                val actions = page.associate { item ->
                    item.id to serviceUtil.getNotificationActionTitles(item.id)
                }.filterValues { it.isNotEmpty() }
                observed.copy(
                    isServiceEnabled = true,
                    allNotifications = page,
                    filteredNotifications = page,
                    notificationActions = actions,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onEvent(event: NotificationFilterEvent) {
        when (event) {
            is NotificationFilterEvent.OnOpenNotification -> {
                Timber.d("notification click viewmodel")
                useCases().openNotification(
                    event.notificationItem.id,
                    event.notificationItem.packageName
                )
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases().markNotificationRead(event.notificationItem.id)
                    }
                }
                deleteNotificationData(event.notificationItem)
            }
            is NotificationFilterEvent.OnDismissNotification -> {
                deleteNotificationData(event.notificationItem)
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