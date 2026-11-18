package ly.com.tahaben.notification_filter_presentation.schedule

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ly.com.tahaben.notification_filter_domain.schedule.DragSample
import ly.com.tahaben.notification_filter_domain.schedule.EditorSelection
import ly.com.tahaben.notification_filter_domain.schedule.EditorState
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleEditor
import ly.com.tahaben.notification_filter_domain.schedule.TimelineView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The day, drawn and touched. Rules live in [ScheduleEditor]; this forwards input.
 *
 * Touch map: tap picks the nearest boundary (within 24 dp) or else the window under the finger. Only the selected
 * boundary can be dragged. A horizontal drag anywhere else pans when zoomed. After a drag starts, one
 * [ScheduleEditor.runDrag] loop reads a bounded (conflated) channel on the uptime clock the pointer events use.
 */
@Composable
fun ScheduleTimeline(state: EditorState, editor: ScheduleEditor, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val currentState by rememberUpdatedState(state)
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    LaunchedEffect(density) { editor.stillnessTolerancePx = with(density) { 3.dp.toPx() } }

    val textMeasurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary = MaterialTheme.colorScheme.tertiary
    val labelStyle = TextStyle(fontSize = 11.sp, color = muted)
    val zoneStyle = TextStyle(fontSize = 12.sp, color = onSurface)
    val description = "Day timeline from midnight to midnight with ${state.schedule.size} windows"

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .semantics { contentDescription = description }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(editor) {
                detectTapGestures { offset ->
                    val s = currentState
                    if (s.isDragging) return@detectTapGestures
                    val nearest = (0 until s.schedule.lastIndex)
                        .minByOrNull { abs(xOf(s.schedule[it].endMinuteOfDay, s.view, widthPx) - offset.x) }
                    if (nearest != null && abs(xOf(s.schedule[nearest].endMinuteOfDay, s.view, widthPx) - offset.x) <= hitRadiusPx) {
                        editor.selectBoundary(nearest)
                    } else {
                        val minute = s.view.minuteAt(offset.x / widthPx)
                        editor.selectZone(s.schedule.indexOfFirst { minute < it.endMinuteOfDay }.takeIf { it >= 0 } ?: s.schedule.lastIndex)
                    }
                }
            }
            .pointerInput(editor) {
                detectHorizontalDragGestures { change, dragAmount ->
                    val s = currentState
                    if (s.view.isFullDay || s.isDragging) return@detectHorizontalDragGestures
                    change.consume()
                    editor.pan(-dragAmount * s.view.span / widthPx)
                }
            }
            // Last in the chain so it sees events first and can claim a drag on the selected boundary.
            .pointerInput(editor) {
                coroutineScope {
                    while (true) {
                        val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                        val s = currentState
                        val selected = (s.selection as? EditorSelection.Boundary)?.index ?: continue
                        if (s.isDragging) continue
                        val boundaryMinute = s.schedule[selected].endMinuteOfDay
                        if (abs(xOf(boundaryMinute, s.view, widthPx) - down.position.x) > hitRadiusPx) continue
                        if (!editor.beginDrag(selected, down.uptimeMillis)) continue
                        // The boundary keeps its offset from the finger instead of jumping under it.
                        val grabOffset = s.view.minuteAt(down.position.x / widthPx) - boundaryMinute
                        val samples = Channel<DragSample>(Channel.CONFLATED)
                        val loop = launch { editor.runDrag(samples, SystemClock::uptimeMillis) }
                        var finished = false
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    val view = currentState.view
                                    samples.trySend(DragSample(change.uptimeMillis, change.position.x,
                                        view.minuteAt(change.position.x / widthPx) - grabOffset, view.span / widthPx))
                                    change.consume()
                                }
                            }
                            // Finger up: close, let the loop drain, then end. No queued frame can land after endDrag.
                            samples.close(); loop.join(); editor.endDrag(); finished = true
                        } finally {
                            // Disposal or cancellation: stop the loop first, then abort, so Cancel is not undone by a late frame.
                            if (!finished) { loop.cancel(); samples.close(); editor.abortDrag() }
                        }
                    }
                }
            }
    ) {
        val width = size.width
        val trackTop = 22.dp.toPx()
        val trackHeight = size.height - trackTop - 6.dp.toPx()
        val view = state.view
        val maxBudget = max(1, state.schedule.maxOf { it.budgetPoints })

        val tickStep = when { view.span >= 720 -> 180; view.span >= 360 -> 60; view.span >= 120 -> 30; else -> 10 }
        var tick = (kotlin.math.ceil(view.startMinute / tickStep) * tickStep).toInt()
        while (tick <= view.endMinute) {
            val x = xOf(tick, view, width)
            drawLine(muted.copy(alpha = 0.35f), Offset(x, trackTop - 4.dp.toPx()), Offset(x, trackTop), 1.dp.toPx())
            val label = textMeasurer.measure(clockLabel(tick), labelStyle)
            drawText(label, topLeft = Offset((x - label.size.width / 2f).coerceIn(0f, width - label.size.width), 0f))
            tick += tickStep
        }

        state.schedule.forEachIndexed { index, period ->
            val left = max(0f, xOf(period.startMinuteOfDay, view, width))
            val right = min(width, xOf(period.endMinuteOfDay, view, width))
            if (right <= left) return@forEachIndexed
            val alpha = if (period.budgetPoints == 0) 0.08f else 0.2f + 0.6f * period.budgetPoints / maxBudget
            drawRect(primary.copy(alpha = alpha), Offset(left + 1f, trackTop), Size(right - left - 2f, trackHeight))
            if (state.selection == EditorSelection.Zone(index)) {
                drawRect(primary, Offset(left + 1f, trackTop), Size(right - left - 2f, trackHeight), style = Stroke(2.dp.toPx()))
            }
            if (right - left > 36.dp.toPx()) {
                val text = textMeasurer.measure(period.budgetPoints.toString(), zoneStyle)
                drawText(text, topLeft = Offset((left + right - text.size.width) / 2f, trackTop + trackHeight / 2f - text.size.height / 2f))
            }
        }

        for (index in 0 until state.schedule.lastIndex) {
            val x = xOf(state.schedule[index].endMinuteOfDay, view, width)
            if (x < 0f || x > width) continue
            val selected = state.selection == EditorSelection.Boundary(index)
            val color = if (selected) tertiary else onSurface.copy(alpha = 0.6f)
            drawLine(color, Offset(x, trackTop), Offset(x, trackTop + trackHeight), if (selected) 4.dp.toPx() else 2.dp.toPx())
            if (selected) {
                drawCircle(color, 10.dp.toPx(), Offset(x, trackTop + trackHeight / 2f))
                if (state.isFineDrag) drawCircle(color, 16.dp.toPx(), Offset(x, trackTop + trackHeight / 2f), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun xOf(minute: Int, view: TimelineView, widthPx: Float): Float = view.fraction(minute.toFloat()) * widthPx

/** 00:00 to 24:00. The day's last boundary reads 24:00, never 00:00. */
internal fun clockLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
