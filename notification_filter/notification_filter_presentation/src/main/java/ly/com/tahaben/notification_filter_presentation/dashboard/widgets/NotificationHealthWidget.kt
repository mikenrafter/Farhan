package ly.com.tahaben.notification_filter_presentation.dashboard.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ly.com.tahaben.core_ui.LocalSpacing

@Composable
fun NotificationHealthWidget(
    score: Int,
    description: String,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val ringColor = when {
        score >= 75 -> Color(0xFF4CAF50)
        score >= 40 -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.spaceMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
        ) {
            Text(
                text = "Notification Health",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Start)
            )
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(120.dp)) {
                    val strokeWidth = 14.dp.toPx()
                    val sweep = score / 100f * 270f
                    drawArc(
                        color = trackColor,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = 135f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$score",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = ringColor
                    )
                    Text(
                        text = "/ 100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
