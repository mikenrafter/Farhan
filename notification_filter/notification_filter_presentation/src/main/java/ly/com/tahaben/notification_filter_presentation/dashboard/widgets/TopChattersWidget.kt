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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core_ui.LocalSpacing

@Composable
fun TopChattersWidget(
    chatters: List<Pair<String, Int>>,
    onExcludeApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    if (chatters.isEmpty()) return

    val maxCount = chatters.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

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
                text = "Top Chatters",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Apps sending the most notifications — candidates for the exceptions list",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            chatters.forEachIndexed { index, (appName, count) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}. $appName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { onExcludeApp(appName) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 8.dp
                                )
                            ) {
                                Text(
                                    text = "Exclude",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { count.toFloat() / maxCount },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}
