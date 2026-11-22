package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

/**
 * The schedule in force today, read only, with the way into the full-day editor. [isDatedToday] says today uses a
 * one-date schedule, so what is listed is not the every-day one.
 */
@Composable
fun NotificationScheduleWidget(
    schedule: List<NotificationSchedulePeriod>,
    isDatedToday: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    if (schedule.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxWidth().padding(spacing.spaceMedium), verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall)) {
            Text("Notification schedule", style = MaterialTheme.typography.titleMedium)
            Text(
                if (isDatedToday) "Today uses a one-date schedule." else "Today uses your every-day schedule.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            schedule.forEach { period ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d:%02d – %02d:%02d".format(period.startHour, period.startMinute, period.endHour, period.endMinute),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(if (period.budgetPoints == 0) "Silent" else "${period.budgetPoints} pts",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Button(onClick = onEdit) { Text("Edit schedule") }
        }
    }
}
