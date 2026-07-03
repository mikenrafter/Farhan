package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

val priorityColor = mapOf(
    NotificationPriority.CRITICAL to Color(0xFFF44336),
    NotificationPriority.HIGH to Color(0xFFFF9800),
    NotificationPriority.MEDIUM to Color(0xFF2196F3),
    NotificationPriority.LOW to Color(0xFF9E9E9E),
)

@Composable
fun PriorityDistributionWidget(
    breakdown: Map<NotificationPriority, Int>,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val total = breakdown.values.sum().coerceAtLeast(1)

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
                text = "Priority Breakdown",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                NotificationPriority.entries.forEach { priority ->
                    val count = breakdown[priority] ?: 0
                    val weight = count.toFloat() / total
                    if (weight > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .height(12.dp)
                                .background(priorityColor[priority] ?: Color.Gray)
                        )
                    }
                }
            }
            NotificationPriority.entries.forEach { priority ->
                val count = breakdown[priority] ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(priorityColor[priority] ?: Color.Gray)
                    )
                    Text(
                        text = priority.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(spacing.spaceSmall))
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
