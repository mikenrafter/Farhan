package ly.com.tahaben.notification_filter_domain.schedule

/** One pointer position during a boundary drag. [timeMillis] is the platform's event time. */
data class DragSample(
    val timeMillis: Long,
    val px: Float,
    val minuteUnderFinger: Float,
    val minutesPerPx: Float,
)

/**
 * A [BoundaryDrag] plus the stillness timer that switches it to one-minute steps.
 *
 * CLAIM(hold-timer): the timer starts at the last movement, not at touch-down; any movement beyond
 * [stillnessTolerancePx] restarts it; after [HOLD_MILLIS] without movement the drag turns fine
 * [BoundaryGestureTest, ScheduleEditorTest]. Time is always passed in (event time), never read from a clock, so
 * tests need no sleeping. [ScheduleEditor.runDrag] is the loop that feeds it from the Compose layer; this class holds the rules.
 */
class BoundaryGesture(
    startMinute: Int,
    startMillis: Long,
    clamp: (Int) -> Int,
    private val stillnessTolerancePx: Float = 0f,
) {
    private val drag = BoundaryDrag(startMinute, clamp)
    private var lastMotionMillis = startMillis
    private var lastMotionPx: Float? = null

    val minute: Int get() = drag.minute
    val isFine: Boolean get() = drag.isFine

    /** Applies one finger position. Movement past the tolerance restarts the stillness timer. Returns the new minute. */
    fun move(sample: DragSample): Int {
        val reference = lastMotionPx
        if (reference == null || kotlin.math.abs(sample.px - reference) > stillnessTolerancePx) {
            lastMotionPx = sample.px
            lastMotionMillis = sample.timeMillis
        }
        return drag.update(sample.px, sample.minuteUnderFinger, sample.minutesPerPx)
    }

    /** Milliseconds until the hold activates if the finger stays still; null once fine. Zero when overdue. */
    fun millisUntilHold(nowMillis: Long): Long? =
        if (drag.isFine) null else (lastMotionMillis + HOLD_MILLIS - nowMillis).coerceAtLeast(0)

    /** Call when time passes without movement. True when this call turned the drag fine. */
    fun tick(nowMillis: Long): Boolean =
        millisUntilHold(nowMillis) == 0L && drag.hold()

    companion object {
        const val HOLD_MILLIS = 600L
    }
}
