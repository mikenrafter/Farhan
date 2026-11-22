package ly.com.tahaben.notification_filter_presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.summary.HourlyArrivals
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Today's notification arrivals per hour, read from the notification database (Inbox and Cleared rows still
 * stored). The usage module keeps no per-hour screen time, so this chart is notifications only.
 */
@HiltViewModel
class HourlyTimelineViewModel @Inject constructor(
    repository: NotificationRepository,
) : ViewModel() {
    /** Null until the first read. */
    val hours: StateFlow<List<Int>?> = combine(
        repository.getNotifications(),
        repository.getClearedNotifications(),
    ) { inbox, cleared ->
        val zone = ZoneId.systemDefault()
        HourlyArrivals.count((inbox.asSequence() + cleared.asSequence()).map { it.timestamp }.asIterable(), LocalDate.now(zone), zone).toList()
    }.map<List<Int>, List<Int>?> { it }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HourlyTimelineCard(
    modifier: Modifier = Modifier,
    viewModel: HourlyTimelineViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val hours by viewModel.hours.collectAsStateWithLifecycle()
    HourlyTimelineCard(hours ?: List(HourlyArrivals.HOURS) { 0 }, LocalTime.now().hour, modifier)
}

@Composable
internal fun HourlyTimelineCard(hours: List<Int>, currentHour: Int, modifier: Modifier = Modifier) {
    val heights = HourlyArrivals.scaled(hours.toIntArray())
    val total = hours.sum()
    val barColor = MaterialTheme.colorScheme.outline
    val nowColor = MaterialTheme.colorScheme.onSurface
    val summary = "Notifications by hour today, $total in all"
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Today by hour", style = MaterialTheme.typography.titleSmall)
                Text("$total notifications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(top = 8.dp).semantics { contentDescription = summary },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                heights.forEachIndexed { hour, fraction ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight(fraction.coerceAtLeast(0.04f))
                                .background(
                                    if (hour == currentHour) nowColor else barColor.copy(alpha = if (fraction == 0f) 0.25f else 0.7f),
                                    RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                                ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatHour(0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatHour(12), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatHour(23), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Screen time by hour is not recorded, so this shows notifications only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val hourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
private fun formatHour(hour: Int): String = LocalTime.of(hour, 0).format(hourFormat)
