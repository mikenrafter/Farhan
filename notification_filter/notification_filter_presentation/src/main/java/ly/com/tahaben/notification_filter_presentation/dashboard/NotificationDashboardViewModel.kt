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
    private val repository: ly.com.tahaben.notification_filter_domain.repository.NotificationRepository,
    private val preferences: ly.com.tahaben.notification_filter_domain.preferences.Preferences,
) : ViewModel() {

    var state by mutableStateOf(NotificationDashboardState())
        private set

    private var summaryJob: Job? = null
    private var batchesJob: Job? = null
    private var standingJob: Job? = null
    private val batchItemJobs = mutableMapOf<String, Job>()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        try {
            val book = preferences.getScheduleSnapshot().book
            val today = java.time.LocalDate.now(ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.systemClock)
            state = state.copy(
            batchWindowMinutes = useCases.getDashboardSettings.getBatchWindowMinutes(),
            standingThresholdHours = useCases.getDashboardSettings.getStandingThresholdHours(),
            floodThreshold = useCases.getDashboardSettings.getFloodThreshold(),
            schedule = book.scheduleFor(today),
            isScheduleDatedToday = today in book.dated,
            budgetStatus = useCases.getCurrentBudgetStatus(),
            priorityDeliverySettings = useCases.getPriorityDeliverySettings(),
            deliveryTestingMode = useCases.isDeliveryTestingMode(),
            scheduleError = null,
        ) } catch (_: ly.com.tahaben.notification_filter_domain.schedule.ScheduleReadException) {
            state = state.copy(scheduleError = "Saved schedule needs repair. Original data has been kept.")
        }
    }

    fun reloadSettings() { loadSettings() }
    fun startObserving() {
        loadSettings(); observeSummary(); observeBatches()
        standingJob?.cancel()
        standingJob = repository.observeStanding(System.currentTimeMillis() - state.standingThresholdHours * 3_600_000L, 3)
            .onEach { state = state.copy(standingItems = it) }.launchIn(viewModelScope)
    }
    fun stopObserving() {
        summaryJob?.cancel(); batchesJob?.cancel(); standingJob?.cancel()
        batchItemJobs.values.forEach { it.cancel() }; batchItemJobs.clear()
    }
    private fun observeBatchItems(key: String, offset: Int = 0) {
        val batch = state.batches.find { it.key == key } ?: return
        batchItemJobs.remove(key)?.cancel()
        batchItemJobs[key] = repository.observeBatchItems(batch, offset).onEach { items ->
            state = state.copy(batches = state.batches.map { if (it.key == key) it.copy(items = items, itemsOffset = offset) else it })
        }.launchIn(viewModelScope)
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
                val previous = state.batches.associateBy { it.key }
                val liveKeys = batches.take(5).map { it.key }.toSet()
                val expanded = state.expandedBatchKeys.intersect(liveKeys)
                state = state.copy(expandedBatchKeys = expanded, batches = batches.map {
                    it.copy(items = if (it.key in expanded) previous[it.key]?.items.orEmpty() else emptyList(), itemsOffset = previous[it.key]?.itemsOffset ?: 0)
                })
                batchItemJobs.keys.toList().filter { it !in liveKeys }.forEach { batchItemJobs.remove(it)?.cancel() }
                state.expandedBatchKeys.intersect(liveKeys).forEach { if (batchItemJobs[it]?.isActive != true) observeBatchItems(it) }
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: NotificationDashboardEvent) {
        when (event) {
            is NotificationDashboardEvent.OnBatchPage -> observeBatchItems(event.batchKey, event.offset.coerceAtLeast(0))
            is NotificationDashboardEvent.OnToggleBatch -> {
                val expanded = state.expandedBatchKeys.toMutableSet()
                if (expanded.contains(event.batchKey)) expanded.remove(event.batchKey)
                else expanded.add(event.batchKey)
                state = state.copy(expandedBatchKeys = expanded)
                if (event.batchKey in expanded) observeBatchItems(event.batchKey)
                else {
                    batchItemJobs.remove(event.batchKey)?.cancel()
                    state = state.copy(batches = state.batches.map { if (it.key == event.batchKey) it.copy(items = emptyList()) else it })
                }
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

            is NotificationDashboardEvent.OnSetPriorityDeliverySetting -> {
                useCases.setPriorityDeliverySetting(event.priority, event.setting)
                state = state.copy(
                    priorityDeliverySettings = state.priorityDeliverySettings.toMutableMap().apply {
                        put(event.priority, event.setting)
                    },
                )
            }

            is NotificationDashboardEvent.OnSetBatchWindow -> {
                useCases.setDashboardSettings.setBatchWindowMinutes(event.minutes)
                state = state.copy(batchWindowMinutes = event.minutes)
                observeBatches()
            }

            is NotificationDashboardEvent.OnSetStandingThreshold -> {
                useCases.setDashboardSettings.setStandingThresholdHours(event.hours)
                state = state.copy(standingThresholdHours = event.hours)
                startObserving()
            }

            is NotificationDashboardEvent.OnSetFloodThreshold -> {
                useCases.setDashboardSettings.setFloodThreshold(event.count)
                state = state.copy(floodThreshold = event.count)
                observeSummary()
            }

            NotificationDashboardEvent.OnClearAllStanding -> {
                val cutoff = System.currentTimeMillis() - state.standingThresholdHours * 3_600_000L
                viewModelScope.launch(Dispatchers.IO) {
                    while (true) {
                        val page = repository.getStandingPage(cutoff)
                        if (page.isEmpty()) break
                        page.forEach { useCases.deleteNotificationFromDB(it) }
                    }
                }
            }

            NotificationDashboardEvent.OnDeleteAll -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        useCases.clearInbox()
                    }
                }
            }
        }
    }
}
