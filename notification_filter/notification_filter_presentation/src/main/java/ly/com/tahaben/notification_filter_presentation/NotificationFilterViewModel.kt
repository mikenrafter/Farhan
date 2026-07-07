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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val serviceUtil: ServiceUtil
) : ViewModel() {


    var state by mutableStateOf(NotificationFilterState())
        private set

    private var getNotificationsJob: Job? = null

    init {
        checkServiceStats()
        getNotifications()
        getClearedNotifications()
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

    private fun getNotifications() {
        getNotificationsJob?.cancel()
        getNotificationsJob =
            notificationFilterUseCases.getNotificationsFromDB()
                .debounce(NOTIFICATION_LIST_DEBOUNCE_MS)
                .flowOn(Dispatchers.Default)
                .onEach { notifications ->
                    applyNotificationList(notifications)
                }
                .launchIn(viewModelScope)
    }

    private fun applyNotificationList(notifications: List<NotificationItem>) {
        val nowMillis = System.currentTimeMillis()
        val standingThresholdHours = sharedPref.getStandingThresholdHours()
        val filtered = NotificationInboxFilter.filter(
            notifications = notifications,
            priorityFilter = state.priorityFilter,
            standingThresholdHours = standingThresholdHours,
            nowMillis = nowMillis,
        )
        val displayPriorities = filtered.associate { item ->
            item.id to NotificationInboxFilter.effectivePriority(
                item = item,
                standingThresholdHours = standingThresholdHours,
                nowMillis = nowMillis,
            )
        }
        val actions = filtered.associate { item ->
            item.id to serviceUtil.getNotificationActionTitles(item.id)
        }.filterValues { it.isNotEmpty() }
        state = state.copy(
            isServiceEnabled = true,
            allNotifications = notifications,
            filteredNotifications = filtered,
            notificationActions = actions,
            displayPriorities = displayPriorities,
        )
    }

    private fun getClearedNotifications() {
        notificationFilterUseCases.getClearedNotifications()
            .onEach { cleared ->
                state = state.copy(clearedNotifications = cleared)
            }
            .launchIn(viewModelScope)
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
                Timber.d("dismissed: ${event.notificationItem}")
                deleteNotificationData(event.notificationItem)
            }
            is NotificationFilterEvent.OnDeleteAllNotifications -> {
                Timber.d("delete all notifications")
                deleteAllNotifications()
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
                state = state.copy(priorityFilter = event.priority)
                applyNotificationList(state.allNotifications)
            }

            NotificationFilterEvent.OnToggleClearedView -> {
                state = state.copy(showCleared = !state.showCleared)
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

    private fun deleteAllNotifications() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                notificationFilterUseCases.deleteAllNotifications()
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