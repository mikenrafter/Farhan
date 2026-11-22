package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry
import ly.com.tahaben.notification_filter_domain.model.CurrentBudgetStatus
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod

@Composable
fun BudgetSpendWidget(
    budgetStatus: CurrentBudgetStatus,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val period = budgetStatus.activePeriod

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
        ) {
            Text(
                text = "Budget This Period",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (period == null) {
                Text(
                    text = "No active schedule window right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = formatTimeRange(period),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!budgetStatus.hasBudget) {
                Text(
                    text = "This window is silent — no budget available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${budgetStatus.spentPoints} / ${budgetStatus.totalBudget} pts spent",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${budgetStatus.remainingPoints} remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LinearProgressIndicator(
                progress = {
                    if (budgetStatus.totalBudget == 0) {
                        0f
                    } else {
                        budgetStatus.spentPoints.toFloat() / budgetStatus.totalBudget.toFloat()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (budgetStatus.spendEntries.isEmpty()) {
                Text(
                    text = "Nothing has spent budget yet this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Spent by",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                budgetStatus.spendEntries
                    .sortedByDescending { it.timestamp }
                    .forEach { entry ->
                        BudgetSpendEntryRow(entry = entry)
                    }
            }
        }
    }
}

@Composable
private fun BudgetSpendEntryRow(entry: BudgetSpendEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.appName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.title.isNotBlank()) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${entry.pointCost} pts · ${entry.priority.name.lowercase().replaceFirstChar { it.titlecase() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTimeRange(period: NotificationSchedulePeriod): String {
    val start = "%02d:%02d".format(period.startHour, period.startMinute)
    val end = "%02d:%02d".format(period.endHour, period.endMinute)
    return "$start – $end"
}
