package ly.com.tahaben.notification_filter_presentation.apppriority

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ly.com.tahaben.notification_filter_presentation.R

internal val appRailWidth = 32.dp

@Composable
internal fun AppAlphabetRail(
    letters: List<String>,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val jump by rememberUpdatedState(onJump)
    var heldIndex by remember(letters) { mutableStateOf<Int?>(null) }
    var railHeight by remember { mutableIntStateOf(0) }
    val bubbleSize = with(LocalDensity.current) { minOf(56.dp, railHeight.toDp()) }

    Box(modifier = modifier.fillMaxHeight().width(appRailWidth).onSizeChanged { railHeight = it.height }) {
        Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth().pointerInput(letters, haptics) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun choose(y: Float) {
                        if (size.height <= 0) return
                        val index = (y / size.height * letters.size).toInt().coerceIn(letters.indices)
                        if (index != heldIndex) {
                            heldIndex = index
                            jump(letters[index])
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    try {
                        choose(down.position.y)
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) break
                            choose(change.position.y)
                            change.consume()
                        } while (true)
                    } finally {
                        heldIndex = null
                    }
                }
            },
        ) {
            letters.forEachIndexed { index, letter ->
                val action = stringResource(R.string.app_list_jump_to, letter)
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().clearAndSetSemantics {
                        contentDescription = action
                        onClick {
                            jump(letter)
                            true
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = if (index == heldIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == heldIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        heldIndex?.let { index ->
            Surface(
                modifier = Modifier.align(Alignment.TopStart).offset {
                    IntOffset(
                        x = (-62).dp.roundToPx(),
                        y = railBubbleTop(index, letters.size, railHeight, bubbleSize.roundToPx()),
                    )
                }.requiredSize(bubbleSize).clearAndSetSemantics {},
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(letters[index], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
