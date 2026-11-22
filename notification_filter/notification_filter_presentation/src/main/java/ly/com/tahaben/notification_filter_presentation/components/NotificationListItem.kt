package ly.com.tahaben.notification_filter_presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.apppriority.AppIconLoader
import ly.com.tahaben.notification_filter_presentation.apppriority.PackageAppIcon
import ly.com.tahaben.notification_filter_presentation.inbox.DeliveryState
import ly.com.tahaben.notification_filter_presentation.inbox.InboxMessage
import ly.com.tahaben.notification_filter_presentation.inbox.InboxThread
import kotlin.math.roundToInt

/**
 * Everything the Inbox rows call back into, created once per screen. Rows receive this one stable object
 * instead of a fresh set of lambdas per row, so an unchanged row is skipped when the list updates.
 */
@Immutable
internal class InboxActions(
    val iconLoader: AppIconLoader<ImageBitmap>,
    val onOpen: (NotificationItem) -> Unit,
    val onAction: (notificationId: String, actionIndex: Int) -> Unit,
    val onAlwaysAllow: (NotificationItem) -> Unit,
    val onAppInfo: (packageName: String) -> Unit,
    /** Expands or collapses a row or thread; the key is the row id or the thread key. */
    val onToggleExpand: (key: String) -> Unit,
    val onToggleSelect: (ids: List<String>) -> Unit,
    val onCopyCode: (code: String) -> Unit,
)

private val CardShape = RoundedCornerShape(8.dp)
private val PillShape = RoundedCornerShape(10.dp)
private const val MAX_EXPANDED_MESSAGES = 30
private val RAIL_WIDTH = 3.dp
private val SELECT_COLUMN_WIDTH = 52.dp

/** Text longer than this is shown cut off, so the row can be opened up with its first tap. */
private const val LONG_TEXT_CHARS = 110

/** Only urgent rows get a rail, and a quiet one; everything else stays flat. */
@Composable
private fun railColor(priority: NotificationPriority): Color? = when (priority) {
    NotificationPriority.CRITICAL -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    NotificationPriority.HIGH -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    else -> null
}

@Composable
private fun deliveryDot(state: DeliveryState): Color = when (state) {
    DeliveryState.SENT -> MaterialTheme.colorScheme.primary
    DeliveryState.WAITING -> MaterialTheme.colorScheme.tertiary
    DeliveryState.NOT_SENT -> MaterialTheme.colorScheme.error
}

/** One notification: app icon, text, delivery state and labels. The source app's buttons appear once it is opened up. */
@Composable
internal fun NotificationRowCard(
    message: InboxMessage,
    expanded: Boolean,
    selected: Boolean,
    selectable: Boolean,
    actions: InboxActions,
    modifier: Modifier = Modifier,
) {
    val item = message.item
    val expandable = message.otp == null &&
        (message.actions.isNotEmpty() || (item.text?.length ?: 0) > LONG_TEXT_CHARS)
    CardFrame(
        delivery = message.delivery,
        isRead = item.isRead,
        priority = message.displayPriority,
        item = item,
        actions = actions,
        selectable = selectable,
        selected = selected,
        selectIds = listOf(item.id),
        onTap = {
            if (expandable && !expanded) actions.onToggleExpand(item.id) else actions.onOpen(item)
        },
        modifier = modifier,
        animateSize = expandable,
    ) {
        HeaderLine(item.appName, message.dateTimeLabel)
        Text(
            text = item.title.orEmpty(),
            color = textColor(item.isRead),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            overflow = TextOverflow.Ellipsis,
            maxLines = if (expanded) 4 else 2,
        )
        if (message.otp != null) {
            OtpLine(message.otp, actions)
        } else if (!item.text.isNullOrEmpty()) {
            Text(
                text = item.text.orEmpty(),
                color = textColor(item.isRead),
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = if (expanded) 16 else 2,
            )
        }
        if (item.deliveryInvalidated) Text(
            text = "Not proxied after intake overflow. Open the source app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Labels(message, extra = null, detailed = expanded)
        if (expanded) ActionButtons(item.id, message.actions, actions)
        if (expandable) ExpandAffordance(expanded) { actions.onToggleExpand(item.id) }
    }
}

/** A conversation: the latest message and a count. The first tap opens it up; its messages and buttons open the source app. */
@Composable
internal fun NotificationThreadCard(
    thread: InboxThread,
    expanded: Boolean,
    selected: Boolean,
    selectable: Boolean,
    actions: InboxActions,
    modifier: Modifier = Modifier,
) {
    val latest = thread.latest
    val item = latest.item
    CardFrame(
        delivery = thread.delivery,
        isRead = thread.unreadCount == 0,
        priority = latest.displayPriority,
        item = item,
        actions = actions,
        selectable = selectable,
        selected = selected,
        selectIds = thread.messages.map { it.item.id },
        onTap = { actions.onToggleExpand(thread.key) },
        modifier = modifier,
        animateSize = true,
    ) {
        HeaderLine(item.appName, latest.dateTimeLabel)
        Text(
            text = item.title.orEmpty(),
            color = textColor(thread.unreadCount == 0),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
        if (!expanded && !item.text.isNullOrEmpty()) {
            Text(
                text = item.text.orEmpty(),
                color = textColor(thread.unreadCount == 0),
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        }
        if (expanded) ThreadMessages(thread, actions)
        Labels(latest, extra = threadCountLabel(thread), detailed = expanded)
        if (expanded) ActionButtons(item.id, latest.actions, actions)
        ExpandAffordance(expanded, count = thread.messages.size) { actions.onToggleExpand(thread.key) }
    }
}

@Composable
private fun textColor(isRead: Boolean): Color =
    if (isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

private fun threadCountLabel(thread: InboxThread): String {
    val unread = thread.unreadCount
    return if (unread > 0) "${thread.messages.size} messages · $unread unread"
    else "${thread.messages.size} messages"
}

@Composable
private fun ThreadMessages(thread: InboxThread, actions: InboxActions) {
    val shown = thread.messages.take(MAX_EXPANDED_MESSAGES)
    Column(modifier = Modifier.fillMaxWidth()) {
        shown.forEachIndexed { index, message ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ThreadMessageLine(message, actions)
        }
        val hidden = thread.messages.size - shown.size
        if (hidden > 0) Text(
            text = "$hidden older messages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThreadMessageLine(message: InboxMessage, actions: InboxActions) {
    val item = message.item
    val onOpen by rememberUpdatedState { actions.onOpen(item) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message.compactLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = item.text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold,
            color = textColor(item.isRead),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A verification code in plain sight, with a Copy button. Nothing is copied until it is tapped. */
@Composable
private fun OtpLine(code: String, actions: InboxActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Verification code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Button(
            onClick = { actions.onCopyCode(code) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 40.dp),
        ) { Text("Copy") }
    }
}

@Composable
private fun ExpandAffordance(expanded: Boolean, count: Int? = null, onClick: () -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick)
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(PillShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                expanded -> "Show less"
                count != null -> "Show $count messages"
                else -> "Show more"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderLine(appName: String?, dateTimeLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = appName.orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            text = dateTimeLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Delivery and priority always; category, count and channel once the row is opened up. All neutral, with a small dot for delivery. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labels(message: InboxMessage, extra: String?, detailed: Boolean) {
    val item = message.item
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Pill(text = message.delivery.chipLabel, dot = deliveryDot(message.delivery))
        Pill(text = message.displayPriority.name.lowercase().replaceFirstChar { it.uppercase() })
        if (extra != null) Pill(text = extra)
        if (item.isRead) Pill(text = "Opened", icon = Icons.Filled.CheckCircle)
        if (detailed) {
            Pill(text = item.category.label())
            if (!item.channelId.isNullOrBlank()) Pill(text = item.channelId.orEmpty())
        }
    }
}

private fun NotificationCategory.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun Pill(text: String, dot: Color? = null, icon: ImageVector? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(modifier = Modifier.width(5.dp))
        }
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The source app's own buttons (Reply, Mark as read, ...), wrapped onto extra lines instead of scrolling sideways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(notificationId: String, titles: List<String>, actions: InboxActions) {
    if (titles.isEmpty()) return
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        titles.forEachIndexed { index, title ->
            OutlinedButton(
                onClick = { actions.onAction(notificationId, index) },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The shared card chrome: a flat surface with a thin outline (dashed when Farhan has not sent it),
 * a quiet rail for urgent rows, the app icon, a press ripple and a long-press menu. The whole right-hand
 * column is the selection target. The menu is only composed while open, and its anchor comes from the
 * press position rather than a per-row size measurement.
 */
@Composable
private fun CardFrame(
    delivery: DeliveryState,
    isRead: Boolean,
    priority: NotificationPriority,
    item: NotificationItem,
    actions: InboxActions,
    selectable: Boolean,
    selected: Boolean,
    selectIds: List<String>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    animateSize: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentActions by rememberUpdatedState(actions)
    val rail = railColor(priority)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val unsent = delivery != DeliveryState.SENT
    val container = if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.surface
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (animateSize) Modifier.animateContentSize() else Modifier)
            .clip(CardShape)
            .background(container)
            // Rail and outline are drawn, not laid out: a fillMaxHeight child inside a list row measures to zero height.
            .drawBehind {
                if (rail != null) drawRect(rail, size = Size(RAIL_WIDTH.toPx(), size.height))
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = if (unsent) dash else null),
                )
            }
            .indication(interactionSource, LocalIndication.current)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { menuAnchor = IntOffset(it.x.roundToInt(), it.y.roundToInt()) },
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onTap = { currentOnTap() },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = if (selectable) SELECT_COLUMN_WIDTH else 12.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PackageAppIcon(
                packageName = item.packageName,
                letter = (item.appName ?: item.packageName).firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                loader = actions.iconLoader,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { content() }
        }
        if (selectable) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(SELECT_COLUMN_WIDTH)
                        .clickable(interactionSource = null, indication = null) { currentActions.onToggleSelect(selectIds) },
                    contentAlignment = Alignment.Center,
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.onSurface,
                            checkmarkColor = MaterialTheme.colorScheme.surface,
                            uncheckedColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
        }
        menuAnchor?.let { anchor ->
            // A zero-size box at the press point: the menu opens where the finger was.
            Box(modifier = Modifier.absoluteOffset { anchor }) {
                DropdownMenu(expanded = true, onDismissRequest = { menuAnchor = null }) {
                    DropdownMenuItem(text = { Text(text = "Always allow (Critical)") }, onClick = {
                        menuAnchor = null
                        actions.onAlwaysAllow(item)
                    })
                    DropdownMenuItem(text = { Text(text = "App info") }, onClick = {
                        menuAnchor = null
                        actions.onAppInfo(item.packageName)
                    })
                }
            }
        }
    }
}

/** A quiet section label: no colour, no icon. [subtitle] says what the section means. */
@Composable
internal fun InboxSectionHeader(title: String, count: Int, subtitle: String, onPage: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$title · $count" + if (onPage) " on this page" else "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}
