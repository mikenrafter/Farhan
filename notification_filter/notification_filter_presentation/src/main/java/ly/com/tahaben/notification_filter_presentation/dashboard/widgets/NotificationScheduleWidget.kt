package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScheduleWidget(
    schedule: List<NotificationSchedulePeriod>,
    onPeriodChanged: (index: Int, period: NotificationSchedulePeriod) -> Unit,
    onBoundaryAdjusted: (boundaryIndex: Int, hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    if (schedule.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var editingBoundaryIndex by remember { mutableIntStateOf(-1) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notification Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!expanded) {
                        val firstStart = schedule.first().let { "%02d:%02d".format(it.startHour, it.startMinute) }
                        val lastEnd = schedule.last().let { "%02d:%02d".format(it.endHour, it.endMinute) }
                        Text(
                            text = "${schedule.size} period${if (schedule.size != 1) "s" else ""} · $firstStart–$lastEnd",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Text(
                    text = "Tap a boundary time to adjust it. Day always runs 00:00–23:59 with no gaps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                schedule.forEachIndexed { index, period ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    SchedulePeriodRow(
                        period = period,
                        periodIndex = index,
                        isLastPeriod = index == schedule.lastIndex,
                        onStartBoundaryClick = if (index > 0) {
                            { editingBoundaryIndex = index - 1 }
                        } else {
                            null
                        },
                        onEndBoundaryClick = if (index < schedule.lastIndex) {
                            { editingBoundaryIndex = index }
                        } else {
                            null
                        },
                        onBudgetSettled = { newBudget ->
                            onPeriodChanged(index, period.copy(budgetPoints = newBudget))
                        }
                    )
                }
            }
        }
    }

    if (editingBoundaryIndex >= 0 && editingBoundaryIndex < schedule.lastIndex) {
        val boundaryPeriod = schedule[editingBoundaryIndex]
        val timePickerState = rememberTimePickerState(
            initialHour = boundaryPeriod.endHour,
            initialMinute = boundaryPeriod.endMinute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { editingBoundaryIndex = -1 }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.padding(spacing.spaceLarge)) {
                    Text(
                        text = "Adjust boundary",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = spacing.spaceSmall),
                    )
                    Text(
                        text = "Between ${formatTimeRange(schedule[editingBoundaryIndex])} and ${formatTimeRange(schedule[editingBoundaryIndex + 1])}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = spacing.spaceMedium),
                    )
                    TimePicker(state = timePickerState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { editingBoundaryIndex = -1 }) {
                            Text(text = "Cancel")
                        }
                        Spacer(modifier = Modifier.width(spacing.spaceSmall))
                        TextButton(onClick = {
                            onBoundaryAdjusted(
                                editingBoundaryIndex,
                                timePickerState.hour,
                                timePickerState.minute,
                            )
                            editingBoundaryIndex = -1
                        }) {
                            Text(text = "OK")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulePeriodRow(
    period: NotificationSchedulePeriod,
    periodIndex: Int,
    isLastPeriod: Boolean,
    onStartBoundaryClick: (() -> Unit)?,
    onEndBoundaryClick: (() -> Unit)?,
    onBudgetSettled: (Int) -> Unit,
) {
    var sliderValue by remember(period.budgetPoints) { mutableFloatStateOf(period.budgetPoints.toFloat()) }
    val currentBudget = sliderValue.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScheduleTimeRangeText(
                period = period,
                isFirstPeriod = periodIndex == 0,
                isLastPeriod = isLastPeriod,
                onStartBoundaryClick = onStartBoundaryClick,
                onEndBoundaryClick = onEndBoundaryClick,
            )
            Text(
                text = if (currentBudget == 0) "Silent" else "$currentBudget pts",
                style = MaterialTheme.typography.bodyMedium,
                color = if (currentBudget == 0)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onBudgetSettled(sliderValue.roundToInt()) },
            valueRange = 0f..30f,
            steps = 29,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = budgetDescription(currentBudget),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScheduleTimeRangeText(
    period: NotificationSchedulePeriod,
    isFirstPeriod: Boolean,
    isLastPeriod: Boolean,
    onStartBoundaryClick: (() -> Unit)?,
    onEndBoundaryClick: (() -> Unit)?,
) {
    val start = "%02d:%02d".format(period.startHour, period.startMinute)
    val end = "%02d:%02d".format(period.endHour, period.endMinute)
    val startIsFixed = isFirstPeriod
    val endIsFixed = isLastPeriod &&
        period.endHour == NotificationSchedulePeriod.DAY_END_HOUR &&
        period.endMinute == NotificationSchedulePeriod.DAY_END_MINUTE

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (startIsFixed || onStartBoundaryClick == null) {
            Text(
                text = start,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = start,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onStartBoundaryClick),
            )
        }
        Text(
            text = " – ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (endIsFixed || onEndBoundaryClick == null) {
            Text(
                text = end,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = end,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onEndBoundaryClick),
            )
        }
    }
}

private fun formatTimeRange(period: NotificationSchedulePeriod): String {
    val start = "%02d:%02d".format(period.startHour, period.startMinute)
    val end = "%02d:%02d".format(period.endHour, period.endMinute)
    return "$start – $end"
}

private fun budgetDescription(points: Int): String {
    if (points == 0) return "No notifications delivered this window"
    val parts = mutableListOf<String>()
    val maxHigh = (points / 2).coerceAtMost(10)
    val maxMedium = (points / 4).coerceAtMost(10)
    val maxLow = (points / 8).coerceAtMost(10)
    if (maxHigh > 0) parts.add("$maxHigh high")
    if (maxMedium > 0) parts.add("$maxMedium medium")
    if (maxLow > 0) parts.add("$maxLow low")
    return "Up to " + parts.joinToString(", ")
}
