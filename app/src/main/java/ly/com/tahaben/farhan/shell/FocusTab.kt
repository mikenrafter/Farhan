package ly.com.tahaben.farhan.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ly.com.tahaben.core.R
import ly.com.tahaben.core.navigation.Routes
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.infinite_scroll_blocker_domain.use_cases.InfiniteScrollUseCases
import ly.com.tahaben.launcher_domain.preferences.Preference
import ly.com.tahaben.screen_grayscale_domain.use_cases.GrayscaleUseCases
import javax.inject.Inject

internal data class FocusStatuses(
    val scrollBlocker: Boolean = false,
    val grayscale: Boolean = false,
    val timeLimit: Boolean = false,
    val mindfulLaunch: Boolean = false,
)

/** Whether each focus tool is on right now: the service switch and its accessibility permission, as the old home card did. */
@HiltViewModel
internal class FocusStatusViewModel @Inject constructor(
    private val infiniteScroll: InfiniteScrollUseCases,
    private val grayscale: GrayscaleUseCases,
    private val launcher: Preference,
) : ViewModel() {
    var statuses by mutableStateOf(FocusStatuses())
        private set

    fun refresh() {
        viewModelScope.launch {
            statuses = FocusStatuses(
                scrollBlocker = infiniteScroll.isServiceEnabled() && infiniteScroll.isAccessibilityPermissionGranted(),
                grayscale = grayscale.isGrayscaleEnabled() && grayscale.isAccessibilityPermissionGranted(),
                timeLimit = launcher.isTimeLimiterEnabled(),
                mindfulLaunch = launcher.isDelayedLaunchEnabled().first(),
            )
        }
    }
}

/**
 * The focus tools' hub: each row opens the tool's existing screen, which stays as it is. The rows keep the old
 * home screen's rule: with the main switch off they explain instead of opening.
 */
@Composable
internal fun FocusTab(
    mainSwitchEnabled: Boolean,
    onOpen: (String) -> Unit,
    onMainSwitchOff: () -> Unit,
    viewModel: FocusStatusViewModel = hiltViewModel(),
) {
    OnLifecycleEvent { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
    val status = viewModel.statuses
    Column(Modifier.fillMaxSize()) {
        CommonAppBar(title = stringResource(R.string.shell_tab_focus), onNavigateUp = null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val open: (String) -> Unit = { route -> if (mainSwitchEnabled) onOpen(route) else onMainSwitchOff() }
            FocusRow(stringResource(R.string.infinite_scrolling), status.scrollBlocker) { open(Routes.INFINITE_SCROLLING) }
            FocusRow(stringResource(R.string.grayscale), status.grayscale) { open(Routes.SCREEN_GRAY_SCALE) }
            FocusRow(stringResource(R.string.app_time_limiter), status.timeLimit) { open(Routes.TimeLimiter_SETTINGS) }
            FocusRow(stringResource(R.string.delayed_launch), status.mindfulLaunch) { open(Routes.DELAYED_LAUNCH_SETTINGS) }
        }
    }
}

/** A neutral outlined row. The small dot is the status: filled when the tool is on. */
@Composable
private fun FocusRow(title: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(8.dp).background(
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                ),
            )
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (on) R.string.enabled else R.string.disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
