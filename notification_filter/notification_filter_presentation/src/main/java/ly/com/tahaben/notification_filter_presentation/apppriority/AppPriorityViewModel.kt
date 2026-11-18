package ly.com.tahaben.notification_filter_presentation.apppriority

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import javax.inject.Inject

@HiltViewModel
class AppPriorityViewModel @Inject constructor(
    private val useCases: NotificationFilterUseCases,
) : ViewModel() {

    private val rows = androidx.compose.runtime.mutableStateListOf<ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo>()
    private val rowIndices = mutableMapOf<String, Int>()
    var state by mutableStateOf(AppPriorityState(apps = rows))
        private set

    private var refreshJob: kotlinx.coroutines.Job? = null
    private var revision = 0L

    init { refresh() }

    private fun cachedApps(): List<ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo> {
        android.os.Trace.beginSection("farhan.catalog.assemble")
        return try { useCases.getAppPriorityInfoList.cached() } finally { android.os.Trace.endSection() }
    }
    private fun publish(apps: List<ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo>) {
        rows.clear(); rows.addAll(apps)
        rowIndices.clear()
        apps.forEachIndexed { index, app -> rowIndices[app.packageName] = index }
        state = state.copy(apps = rows, isLoading = false)
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val capturedRevision = revision
            val cached = withContext(Dispatchers.Default) { cachedApps() }
            if (revision == capturedRevision) publish(cached)
            withContext(Dispatchers.IO) { useCases.getAppPriorityInfoList(forceRescan = false) }
            // Rebuild from the latest preferences after discovery; edits during discovery cannot be overwritten.
            publish(cachedApps())
        }
    }

    fun onEvent(event: AppPriorityEvent) {
        when (event) {
            is AppPriorityEvent.OnToggleExpanded -> {
                val current = state.expandedPackages
                state = if (event.packageName in current) {
                    state.copy(expandedPackages = current - event.packageName)
                } else {
                    state.copy(expandedPackages = current + event.packageName)
                }
            }
            is AppPriorityEvent.OnSetAppPriority -> {
                useCases.setAppPriorityOverride(event.packageName, event.priority)
                patchApp(event.packageName)
            }
            is AppPriorityEvent.OnSetAppLocked -> {
                useCases.setAppPriorityLocked(event.packageName, event.locked)
                patchApp(event.packageName)
            }
            is AppPriorityEvent.OnSetChannelPriority -> {
                val appPriority = state.apps.find { it.packageName == event.packageName }?.currentPriority
                    ?: NotificationPriority.MEDIUM
                val offset = if (event.priority == null) 0 else appPriority.level - event.priority.level
                useCases.setChannelPriorityOverride(event.packageName, event.channelId, offset)
                patchApp(event.packageName)
            }
        }
    }

    private fun patchApp(packageName: String) {
        revision++
        val updated = useCases.getAppPriorityInfoList.forPackage(packageName) ?: return
        val index = rowIndices[packageName] ?: return
        rows[index] = updated
    }
}
