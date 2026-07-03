package ly.com.tahaben.notification_filter_presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import timber.log.Timber
import javax.inject.Inject

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
                .onEach { notifications ->
                    val effectiveFilter = state.priorityFilter
                    val nowMillis = System.currentTimeMillis()
                    val standingCutoff = 3 * 3_600_000L

                    val filtered = if (effectiveFilter == null) notifications
                    else notifications.filter { item ->
                        val isStanding = !item.isRead && (nowMillis - item.timestamp) > standingCutoff
                        val effectivePriority = if (isStanding) item.priority.degraded(1) else item.priority
                        effectivePriority == effectiveFilter
                    }
                    state = state.copy(
                        isServiceEnabled = true,
                        allNotifications = notifications,
                        filteredNotifications = filtered
                    )
                }
                .launchIn(viewModelScope)
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
                notificationFilterUseCases.addPackageToNotificationException(event.appPackageName)
            }

            is NotificationFilterEvent.OnLaunchAppInfoClick -> {
                serviceUtil.launchAppInfo(event.appPackageName)
            }

            is NotificationFilterEvent.OnSetPriorityFilter -> {
                state = state.copy(priorityFilter = event.priority)
                getNotifications()
            }

            NotificationFilterEvent.OnToggleClearedView -> {
                state = state.copy(showCleared = !state.showCleared)
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
}