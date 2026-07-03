package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory

private val categoryColor = mapOf(
    NotificationCategory.COMMUNICATION to Color(0xFF4CAF50),
    NotificationCategory.SOCIAL to Color(0xFF9C27B0),
    NotificationCategory.PRODUCTIVITY to Color(0xFF2196F3),
    NotificationCategory.FINANCE to Color(0xFF4CAF50),
    NotificationCategory.HEALTH to Color(0xFFE91E63),
    NotificationCategory.ENTERTAINMENT to Color(0xFFFF5722),
    NotificationCategory.SYSTEM to Color(0xFF607D8B),
    NotificationCategory.OTHER to Color(0xFF9E9E9E),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBreakdownWidget(
    breakdown: Map<NotificationCategory, Int>,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val activeCategories = breakdown.filter { it.value > 0 }

    if (activeCategories.isEmpty()) return

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
            Text(
                text = "By Category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
                verticalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall)
            ) {
                activeCategories
                    .entries
                    .sortedByDescending { it.value }
                    .forEach { (category, count) ->
                        val color = categoryColor[category] ?: Color.Gray
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = {
                                Text(
                                    text = "${category.name.lowercase().replaceFirstChar { it.uppercase() }}: $count",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = color.copy(alpha = 0.12f),
                                labelColor = color
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = color.copy(alpha = 0.4f),
                                borderWidth = 1.dp
                            )
                        )
                    }
            }
        }
    }
}
