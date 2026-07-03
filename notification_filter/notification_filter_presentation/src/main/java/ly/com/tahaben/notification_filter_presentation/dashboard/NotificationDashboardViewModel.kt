package ly.com.tahaben.notification_filter_presentation.dashboard

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
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import javax.inject.Inject

@HiltViewModel
class NotificationDashboardViewModel @Inject constructor(
    private val useCases: NotificationFilterUseCases,
    private val serviceUtil: ServiceUtil
) : ViewModel() {

    var state by mutableStateOf(NotificationDashboardState())
        private set

    private var summaryJob: Job? = null
    private var batchesJob: Job? = null

    init {
        loadSettings()
        observeSummary()
        observeBatches()
    }

    private fun loadSettings() {
        state = state.copy(
            batchWindowMinutes = useCases.getDashboardSettings.getBatchWindowMinutes(),
            standingThresholdHours = useCases.getDashboardSettings.getStandingThresholdHours(),
            floodThreshold = useCases.getDashboardSettings.getFloodThreshold(),
            schedule = useCases.getNotificationSchedule(),
        )
    }

    private fun observeSummary() {
        summaryJob?.cancel()
        summaryJob = useCases.getNotificationDashboardSummary()
            .onEach { summary ->
                state = state.copy(summary = summary, isLoading = false)
            }
            .launchIn(viewModelScope)
    }

    private fun observeBatches() {
        batchesJob?.cancel()
        batchesJob = useCases.getNotificationBatches()
            .onEach { batches ->
                state = state.copy(batches = batches)
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: NotificationDashboardEvent) {
        when (event) {
            is NotificationDashboardEvent.OnToggleBatch -> {
                val expanded = state.expandedBatchKeys.toMutableSet()
                if (expanded.contains(event.batchKey)) expanded.remove(event.batchKey)
                else expanded.add(event.batchKey)
                state = state.copy(expandedBatchKeys = expanded)
            }

            is NotificationDashboardEvent.OnOpenNotification -> {
                useCases.openNotification(
                    event.notificationItem.id,
                    event.notificationItem.packageName
                )
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases.markNotificationRead(event.notificationItem.id)
                        useCases.deleteNotificationFromDB(event.notificationItem)
                        useCases.deleteNotificationIntentFromHashmap(event.notificationItem.id)
                    }
                }
            }

            is NotificationDashboardEvent.OnDismissNotification -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases.deleteNotificationFromDB(event.notificationItem)
                        useCases.deleteNotificationIntentFromHashmap(event.notificationItem.id)
                    }
                }
            }

            is NotificationDashboardEvent.OnSetLowPriority -> {
                useCases.addPackageToLowPriority(event.packageName)
            }

            is NotificationDashboardEvent.OnMarkAppRead -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases.markAppNotificationsRead(event.packageName)
                    }
                }
            }

            is NotificationDashboardEvent.OnSetAppPriority -> {
                useCases.setAppPriorityOverride(event.packageName, event.priority)
            }

            is NotificationDashboardEvent.OnSetAppPriorityLocked -> {
                useCases.setAppPriorityLocked(event.packageName, event.locked)
            }

            is NotificationDashboardEvent.OnSetCategoryPriority -> {
                useCases.setCategoryPriorityOverride(event.category, event.priority)
            }

            is NotificationDashboardEvent.OnSetSchedulePeriod -> {
                val updatedSchedule = state.schedule.toMutableList()
                if (event.index in updatedSchedule.indices) {
                    updatedSchedule[event.index] = event.period
                    useCases.setNotificationSchedule(updatedSchedule)
                    state = state.copy(schedule = updatedSchedule)
                }
            }

            is NotificationDashboardEvent.OnSetBatchWindow -> {
                useCases.setDashboardSettings.setBatchWindowMinutes(event.minutes)
                state = state.copy(batchWindowMinutes = event.minutes)
                observeBatches()
            }

            is NotificationDashboardEvent.OnSetStandingThreshold -> {
                useCases.setDashboardSettings.setStandingThresholdHours(event.hours)
                state = state.copy(standingThresholdHours = event.hours)
            }

            is NotificationDashboardEvent.OnSetFloodThreshold -> {
                useCases.setDashboardSettings.setFloodThreshold(event.count)
                state = state.copy(floodThreshold = event.count)
            }

            NotificationDashboardEvent.OnClearAllStanding -> {
                val standing = state.batches
                    .flatMap { it.items }
                    .filter { !it.isRead }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        standing.forEach { item ->
                            useCases.deleteNotificationFromDB(item)
                            useCases.deleteNotificationIntentFromHashmap(item.id)
                        }
                    }
                }
            }

            NotificationDashboardEvent.OnDeleteAll -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases.deleteAllNotifications()
                    }
                }
            }
        }
    }
}
