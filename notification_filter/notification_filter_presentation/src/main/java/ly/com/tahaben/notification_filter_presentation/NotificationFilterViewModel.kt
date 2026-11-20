package ly.com.tahaben.notification_filter_presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.inbox.InboxQuery
import ly.com.tahaben.notification_filter_domain.inbox.NotificationInboxFilter
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import timber.log.Timber
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class NotificationFilterViewModel @Inject constructor(
    private val notificationFilterUseCases: NotificationFilterUseCases,
    private val sharedPref: Preferences,
    private val repository: ly.com.tahaben.notification_filter_domain.repository.NotificationRepository,
    private val serviceUtil: ServiceUtil
) : ViewModel() {


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
                isServiceEnabled = notificationFilterUseCases
                    .checkIfNotificationServiceIsEnabled()
            )
        }
        checkIfNotificationAccessGranted()
    }

    private fun checkIfNotificationAccessGranted() {
        state = state.copy(
            isPermissionGranted = notificationFilterUseCases.checkIfNotificationAccessIsGranted()
        )
    }

    fun startNotificationService() {
        notificationFilterUseCases.startNotificationService()
        setServiceEnabled()
    }

    fun startObserving() { getNotifications() }
    fun stopObserving() { getNotificationsJob?.cancel() }
    fun nextPage() { state = state.copy(page = state.page + 1); getNotifications() }
    fun previousPage() { state = state.copy(page = (state.page - 1).coerceAtLeast(0)); getNotifications() }

    private fun getNotifications() {
        getNotificationsJob?.cancel()
        val query = InboxQuery(
            cleared = state.showCleared, priority = state.priorityFilter,
            standingCutoff = System.currentTimeMillis() - sharedPref.getStandingThresholdHours() * 3_600_000L,
            offset = state.page * 100, limit = 101)
        getNotificationsJob = repository.observeInbox(query).onEach { rows ->
            val page = rows.take(100)
            val displayPriorities = page.associate { item ->
                item.id to NotificationInboxFilter.effectivePriorityAtCutoff(item, query.standingCutoff)
            }
            val observed = state.copy(
                hasNextPage = rows.size > 100,
                standingCutoff = query.standingCutoff,
                displayPriorities = displayPriorities,
            )
            state = if (query.cleared) {
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
                notificationFilterUseCases.openNotification(
                    event.notificationItem.id,
                    event.notificationItem.packageName
                )
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        notificationFilterUseCases.markNotificationRead(event.notificationItem.id)
                    }
                }
                deleteNotificationData(event.notificationItem)
            }
            is NotificationFilterEvent.OnDismissNotification -> {
                deleteNotificationData(event.notificationItem)
            }
            is NotificationFilterEvent.OnClearNotifications -> {
                viewModelScope.launch(Dispatchers.IO) {
                    notificationFilterUseCases.deleteAllNotifications(event.scope, event.standingCutoff)
                }
            }

            is NotificationFilterEvent.OnExcludeAppClick -> {
                notificationFilterUseCases.setAppPriorityOverride(
                    event.appPackageName,
                    NotificationPriority.CRITICAL,
                )
            }

            is NotificationFilterEvent.OnLaunchAppInfoClick -> {
                serviceUtil.launchAppInfo(event.appPackageName)
            }

            is NotificationFilterEvent.OnSetPriorityFilter -> {
                state = state.copy(
                    priorityFilter = event.priority, page = 0,
                    filteredNotifications = emptyList(), clearedNotifications = emptyList(),
                )
                getNotifications()
            }

            NotificationFilterEvent.OnToggleClearedView -> {
                state = state.copy(
                    showCleared = !state.showCleared, page = 0,
                    filteredNotifications = emptyList(), clearedNotifications = emptyList(),
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
                notificationFilterUseCases.deleteNotificationFromDB(
                    notificationItem
                )
                notificationFilterUseCases.deleteNotificationIntentFromHashmap(
                    notificationItem.id
                )
            }
        }
    }

    private fun setServiceEnabled() {
        notificationFilterUseCases.setServiceState(true)
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
        notificationFilterUseCases.openAppSettings()
    }

    companion object {
        private const val NOTIFICATION_LIST_DEBOUNCE_MS = 150L
    }
}