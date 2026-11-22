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
import ly.com.tahaben.notification_filter_domain.use_cases.AppPriorityCatalog
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.use_cases.RefreshAppPriorityCatalog
import javax.inject.Inject

@HiltViewModel
class AppPriorityViewModel @Inject constructor(
    private val useCases: NotificationFilterUseCases,
    private val preferences: ly.com.tahaben.notification_filter_domain.preferences.Preferences,
    private val soundControl: ly.com.tahaben.notification_filter_domain.sound.SoundControl,
) : ViewModel() {

    private val rows = androidx.compose.runtime.mutableStateListOf<ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo>()
    private var rowIndices: Map<String, Int> = emptyMap()
    var state by mutableStateOf(AppPriorityState(apps = rows))
        private set

    private var refreshJob: kotlinx.coroutines.Job? = null
    private val catalogRefresh = RefreshAppPriorityCatalog(
        loadCached = { cachedApps() },
        discover = { useCases.getAppPriorityInfoList.discover(forceRescan = false) },
        loadPackage = useCases.getAppPriorityInfoList::forPackage,
    )

    init { refresh() }

    private fun cachedApps(): List<ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo> {
        android.os.Trace.beginSection("farhan.catalog.assemble")
        return try { useCases.getAppPriorityInfoList.cached() } finally { android.os.Trace.endSection() }
    }
    private fun publish(catalog: AppPriorityCatalog) {
        rows.clear(); rows.addAll(catalog.apps)
        rowIndices = catalog.indices
        state = state.copy(apps = rows, isLoading = state.isLoading && catalog.apps.isEmpty())
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            catalogRefresh.refresh(::publish)
            state = state.copy(isLoading = false)
        }
    }

    fun onEvent(event: AppPriorityEvent) {
        when (event) {
            is AppPriorityEvent.OnSetAppPriority -> {
                useCases.setAppPriorityOverride(event.packageName, event.priority)
                patchApp(event.packageName)
            }
            is AppPriorityEvent.OnSetAppLocked -> {
                useCases.setAppPriorityLocked(event.packageName, event.locked)
                patchApp(event.packageName)
            }
            is AppPriorityEvent.OnSetQueuePreference -> viewModelScope.launch {
                // The write is off the main thread; the one row is patched afterwards. No discovery, no catalog copy.
                withContext(Dispatchers.IO) { preferences.setAppQueuePreference(event.packageName, event.preference) }
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

    /** Pass-through: the legacy exceptions list or a Critical override, the same test the delivery path uses. */
    fun isAlwaysAllowed(packageName: String): Boolean = useCases.isPackageInNotificationException(packageName)

    /**
     * Always allow, the way the Inbox action does it: a Critical override. Turning it off clears that override and
     * takes the app out of the legacy exceptions list, so the app follows its normal priority again.
     */
    fun setAlwaysAllowed(packageName: String, allowed: Boolean) {
        if (allowed) {
            useCases.setAppPriorityOverride(packageName, NotificationPriority.CRITICAL)
        } else {
            useCases.setAppPriorityOverride(packageName, null)
            useCases.removePackageFromNotificationException(packageName)
        }
        patchApp(packageName)
    }

    /** Whether sound control lets this app's notifications make sound. */
    fun otpAllApps(): Boolean = preferences.isOtpAllApps()
    fun isOtpCovered(packageName: String): Boolean = preferences.isOtpAllApps() || packageName in preferences.getOtpPackages()
    fun setOtpCovered(packageName: String, covered: Boolean) {
        val current = preferences.getOtpPackages()
        preferences.setOtpPackages(if (covered) current + packageName else current - packageName)
    }

    fun isSoundElected(packageName: String): Boolean = packageName in soundControl.choices().apps

    fun setSoundElected(packageName: String, elected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { soundControl.setAppElected(packageName, elected) }
    }

    fun appInfo(packageName: String) = rows.firstOrNull { it.packageName == packageName }

    private fun patchApp(packageName: String) {
        catalogRefresh.packageEdited(packageName)
        val updated = useCases.getAppPriorityInfoList.forPackage(packageName) ?: return
        val index = rowIndices[packageName] ?: return
        rows[index] = updated
    }
}
