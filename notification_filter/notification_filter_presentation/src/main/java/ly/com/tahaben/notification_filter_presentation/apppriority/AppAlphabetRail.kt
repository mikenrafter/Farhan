package ly.com.tahaben.notification_filter_presentation.apppriority

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
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
    var heldLetter by remember(letters) { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxHeight().width(appRailWidth)) {
        Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth().pointerInput(letters, haptics) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun choose(y: Float) {
                        if (size.height <= 0) return
                        val index = (y / size.height * letters.size).toInt().coerceIn(letters.indices)
                        val letter = letters[index]
                        if (letter != heldLetter) {
                            heldLetter = letter
                            jump(letter)
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
                        heldLetter = null
                    }
                }
            },
        ) {
            letters.forEach { letter ->
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
                        fontWeight = if (letter == heldLetter) FontWeight.Bold else FontWeight.Normal,
                        color = if (letter == heldLetter) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        heldLetter?.let { letter ->
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).offset(x = (-62).dp)
                    .requiredSize(56.dp).clearAndSetSemantics {},
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(letter, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
