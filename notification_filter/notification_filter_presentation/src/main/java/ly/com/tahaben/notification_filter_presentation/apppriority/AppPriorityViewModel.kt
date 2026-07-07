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
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import javax.inject.Inject

@HiltViewModel
class AppPriorityViewModel @Inject constructor(
    private val useCases: NotificationFilterUseCases,
) : ViewModel() {

    var state by mutableStateOf(AppPriorityState())
        private set

    init {
        loadData(showLoading = true)
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) {
            val apps = useCases.getAppPriorityInfoList(forceRescan = false)
            state = state.copy(apps = apps)
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
                useCases.setChannelPriorityOverride(event.packageName, event.channelId, event.priority)
                patchApp(event.packageName)
            }
        }
    }

    private fun loadData(showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) {
                state = state.copy(isLoading = true)
            }
            val apps = withContext(Dispatchers.Default) {
                useCases.getAppPriorityInfoList(forceRescan = false)
            }
            state = state.copy(apps = apps, isLoading = false)
        }
    }

    private fun patchApp(packageName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val updated = useCases.getAppPriorityInfoList.forPackage(packageName) ?: return@launch
            state = state.copy(
                apps = state.apps.map { app ->
                    if (app.packageName == packageName) updated else app
                }
            )
        }
    }
}
