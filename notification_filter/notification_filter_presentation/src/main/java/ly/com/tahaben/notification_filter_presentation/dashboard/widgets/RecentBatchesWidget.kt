package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.model.NotificationBatch
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecentBatchesWidget(
    batches: List<NotificationBatch>,
    expandedKeys: Set<String>,
    onToggleBatch: (String) -> Unit,
    onOpenNotification: (NotificationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val displayBatches = batches.take(5)
    if (displayBatches.isEmpty()) return

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
                text = "Recent Notifications",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            displayBatches.forEachIndexed { index, batch ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BatchRow(
                    batch = batch,
                    isExpanded = expandedKeys.contains(batch.key),
                    onToggle = { onToggleBatch(batch.key) },
                    onOpenNotification = onOpenNotification
                )
            }
        }
    }
}

@Composable
private fun BatchRow(
    batch: NotificationBatch,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpenNotification: (NotificationItem) -> Unit
) {
    val spacing = LocalSpacing.current
    val priorityColor = priorityColor[batch.priority]
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = spacing.spaceExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(priorityColor ?: MaterialTheme.colorScheme.primary)
                    .padding(vertical = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = batch.appName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (batch.count > 1) {
                        Text(
                            text = "${batch.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                val previewText = batch.preview
                if (previewText != null) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = timeFormat.format(Date(batch.latestTimestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (batch.count > 1) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = spacing.spaceLarge),
                verticalArrangement = Arrangement.spacedBy(spacing.spaceExtraSmall)
            ) {
                batch.items.forEach { item ->
                    Text(
                        text = buildString {
                            if (!item.title.isNullOrBlank()) append(item.title)
                            if (!item.text.isNullOrBlank()) {
                                if (isNotEmpty()) append(" — ")
                                append(item.text)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNotification(item) }
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
