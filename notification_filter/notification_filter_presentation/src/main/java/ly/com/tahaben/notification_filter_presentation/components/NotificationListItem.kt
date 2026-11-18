package ly.com.tahaben.notification_filter_presentation.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.theme.FarhanTheme
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

@Composable
fun NotificationListItem(
    notification: NotificationItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuOptions: List<MenuOption>,
    displayPriority: NotificationPriority = notification.priority,
    actions: List<String> = emptyList(),
    onActionClick: (Int) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
    var isContextMenuExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    var pressOffsetX by rememberSaveable {
        mutableStateOf(0f)
    }
    var pressOffsetY by rememberSaveable {
        mutableStateOf(0f)
    }
    val density = LocalDensity.current
    val interactionSource = remember {
        MutableInteractionSource()
    }
    var itemHeight by remember {
        mutableStateOf(0.dp)
    }
    val priorityBarColor = when (displayPriority) {
        NotificationPriority.CRITICAL -> Color(0xFFF44336)
        NotificationPriority.HIGH -> Color(0xFFFF9800)
        NotificationPriority.MEDIUM -> Color(0xFF2196F3)
        NotificationPriority.LOW -> Color(0xFF9E9E9E)
    }
    val containerColor = if (notification.isRead)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(containerColor)
            .indication(interactionSource, LocalIndication.current)
            .onSizeChanged {
                itemHeight = with(density) { it.height.toDp() }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        pressOffsetX = if (isRtl) it.x.toDp().value * -1 else it.x.toDp().value
                        pressOffsetY = it.y.toDp().value
                        isContextMenuExpanded = true
                    },
                    onPress = {
                              val press = PressInteraction.Press(it)
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onTap = {
                        onClick()
                    }
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {

        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(priorityBarColor)
        )
        Spacer(modifier = Modifier.width(spacing.spaceSmall))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = spacing.spaceSmall),
            verticalArrangement = Arrangement.Top
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = notification.appName ?: "",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2
                )
                Text(
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.onSurface,
                    text = if (notification.timestamp > 0L)
                        notification.timestamp.toNotificationDateTimeLabel()
                    else
                        notification.time,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = notification.title ?: "",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(spacing.spaceSmall))
            Text(
                text = notification.text ?: "",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
            )
            if (notification.deliveryInvalidated) Text(
                text = "Not proxied after intake overflow. Open the source app.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.wrapContentWidth()
            ) {
                val priorityLabel = displayPriority.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = priorityBarColor.copy(alpha = 0.15f),
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = priorityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = priorityBarColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                val categoryLabel = notification.category.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = categoryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (!notification.channelId.isNullOrBlank()) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = notification.channelId.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    actions.forEachIndexed { index, title ->
                        OutlinedButton(
                            onClick = { onActionClick(index) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(text = title, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(spacing.spaceSmall))
    }
    DropdownMenu(
        expanded = isContextMenuExpanded,
        onDismissRequest = { isContextMenuExpanded = false },
        offset = DpOffset(y = (pressOffsetY - itemHeight.value).dp, x = pressOffsetX.dp)
        ) {
        menuOptions.forEach {
            DropdownMenuItem(text = { Text(text = it.text) }, onClick = {
                isContextMenuExpanded = false
                it.onClick()
            })
        }
    }
}

data class MenuOption(
    val text: String,
    val onClick: () -> Unit
)

@Preview
@Composable
fun NotificationListItemPreview() {

    FarhanTheme(false, ThemeColors.Classic) {
        val item = NotificationItem("sadfa", "Farhan", "Title", "text", "18:33", "ly.com")
        NotificationListItem(notification = item, onClick = { /*TODO*/ }, menuOptions = emptyList())
    }
}