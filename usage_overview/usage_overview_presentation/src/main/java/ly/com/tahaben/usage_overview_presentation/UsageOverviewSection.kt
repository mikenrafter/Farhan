package ly.com.tahaben.usage_overview_presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.usage_overview_presentation.components.TrackedAppItem
import java.text.DecimalFormat

private const val MOST_USED_COUNT = 5

/**
 * Today's screen time as a block for a bigger screen to place in its own list (the Summary tab does). It runs the
 * same [UsageOverviewViewModel] and rows as [UsageOverviewScreen], which stays the place for other days, ranges
 * and the usage settings ([onOpenFull]).
 */
@Composable
fun UsageOverviewSection(
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UsageOverviewViewModel = hiltViewModel(),
) {
    val state = viewModel.state
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            viewModel.checkUsagePermissionState()
            viewModel.checkIfCachingEnabled()
            viewModel.iniFilters()
        }
    }
    val number = DecimalFormat.getInstance()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usage), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenFull) { Text(stringResource(R.string.usage_open_full)) }
            }
            when {
                !state.isUsagePermissionGranted -> {
                    Text(stringResource(R.string.usage_permission_sub_msg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::askForUsagePermission) { Text(stringResource(R.string.grant_access)) }
                }
                state.isLoading -> CircularProgressIndicator(Modifier.padding(8.dp))
                else -> {
                    Text(
                        text = buildString {
                            if (state.totalUsageDuration > 0) append(number.format(state.totalUsageDuration)).append("h ")
                            append(number.format(state.totalUsageMinutes)).append("m")
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(stringResource(R.string.total_usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    state.trackedApps.take(MOST_USED_COUNT).forEach { app ->
                        TrackedAppItem(app, Modifier.padding(vertical = 3.dp))
                    }
                    if (state.trackedApps.isEmpty()) {
                        Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
