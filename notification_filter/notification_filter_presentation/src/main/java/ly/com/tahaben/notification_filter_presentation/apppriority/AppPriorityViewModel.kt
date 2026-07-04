package ly.com.tahaben.notification_filter_presentation.apppriority

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import javax.inject.Inject

@HiltViewModel
class AppPriorityViewModel @Inject constructor(
    private val useCases: NotificationFilterUseCases,
) : ViewModel() {

    var state by mutableStateOf(AppPriorityState())
        private set

    init {
        loadData()
    }

    fun refresh() {
        loadData()
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
                loadData()
            }
            is AppPriorityEvent.OnSetAppLocked -> {
                useCases.setAppPriorityLocked(event.packageName, event.locked)
                loadData()
            }
            is AppPriorityEvent.OnSetChannelPriority -> {
                useCases.setChannelPriorityOverride(event.packageName, event.channelId, event.priority)
                loadData()
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            state = state.copy(isLoading = true)
            val apps = useCases.getAppPriorityInfoList()
            state = state.copy(apps = apps, isLoading = false)
        }
    }
}
