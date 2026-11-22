package ly.com.tahaben.farhan.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import ly.com.tahaben.screen_grayscale_domain.use_cases.GrayscaleUseCases
import javax.inject.Inject

/** The two accesses Android grants through its own screens. Both are read, never changed, from Settings. */
internal data class SystemAccessChecks(
    val notificationAccess: Boolean = false,
    val accessibility: Boolean = false,
)

@HiltViewModel
internal class SpecialAccessChecksViewModel @Inject constructor(
    private val serviceUtil: ServiceUtil,
    private val grayscale: GrayscaleUseCases,
) : ViewModel() {
    var checks by mutableStateOf(SystemAccessChecks())
        private set

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = SystemAccessChecks(
                notificationAccess = serviceUtil.isNotificationAccessPermissionGranted(),
                accessibility = grayscale.isAccessibilityPermissionGranted(),
            )
            if (next != checks) checks = next
        }
    }
}
