package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriorityDeliveryWidget(
    settings: Map<NotificationPriority, PriorityDeliverySetting>,
    testingMode: Boolean,
    onSettingChanged: (NotificationPriority, PriorityDeliverySetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Priority Delivery",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!expanded) {
                        Text(
                            text = if (testingMode) {
                                "Testing mode: 3-minute boundaries"
                            } else {
                                "Per-priority delivery cadence and budget"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Text(
                    text = "Configure when each priority is delivered to the status bar. " +
                        if (testingMode) {
                            "Testing mode is on — hour boundaries use 3-minute slots."
                        } else {
                            "Batch cadences align to hourly boundaries."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                NotificationPriority.entries.forEachIndexed { index, priority ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val setting = settings[priority] ?: PriorityDeliverySetting.defaultFor(priority)
                    PriorityDeliveryRow(
                        priority = priority,
                        setting = setting,
                        onSettingChanged = onSettingChanged,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityDeliveryRow(
    priority: NotificationPriority,
    setting: PriorityDeliverySetting,
    onSettingChanged: (NotificationPriority, PriorityDeliverySetting) -> Unit,
) {
    val spacing = LocalSpacing.current
    val priorityLabel = priority.name.lowercase().replaceFirstChar { it.uppercase() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall),
    ) {
        Text(
            text = "Deliver notifications with $priorityLabel priority",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall),
        ) {
            DeliveryCadence.entries.forEach { cadence ->
                FilterChip(
                    selected = setting.cadence == cadence,
                    onClick = {
                        onSettingChanged(priority, setting.copy(cadence = cadence))
                    },
                    label = { Text(cadence.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall),
        ) {
            BudgetBehavior.entries.forEach { behavior ->
                FilterChip(
                    selected = setting.budgetBehavior == behavior,
                    onClick = {
                        onSettingChanged(priority, setting.copy(budgetBehavior = behavior))
                    },
                    label = { Text(behavior.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
