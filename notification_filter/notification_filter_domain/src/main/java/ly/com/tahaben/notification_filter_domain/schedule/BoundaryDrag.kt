package ly.com.tahaben.notification_filter_domain.schedule

/**
 * One boundary drag, after Edge's MagnifierDrag
 * (edge `pilot/sleep-editor-fixes` e0759a67f, timeline_model.dart). Coarse: follows the
 * finger, rounded to 5 minutes. After [hold]: 1-minute steps at a quarter of the finger's
 * speed, anchored at the value the user currently sees ([clamp] applied).
 */
class BoundaryDrag(startMinute: Int, private val clamp: (Int) -> Int = { it }) {
    var minute: Int = startMinute
        private set

    var isFine: Boolean = false
        private set

    private var anchorPx: Float? = null
    private var anchorMinute = startMinute

    /** The finger is at [px]; [minuteUnderFinger] is the view's time there. Returns the new [minute]. */
    fun update(px: Float, minuteUnderFinger: Float, minutesPerPx: Float): Int {
        if (isFine && anchorPx == null) anchorPx = px
        val requested = if (isFine) anchorMinute + kotlin.math.round((px - anchorPx!!) * minutesPerPx * 0.25f).toInt()
            else kotlin.math.round(minuteUnderFinger / 5f).toInt() * 5
        minute = clamp(requested)
        return minute
    }

    /** The finger has been still for 600 ms. True the first time, false when already fine. */
    fun hold(): Boolean {
        if (isFine) return false
        isFine = true
        anchorMinute = minute
        anchorPx = null
        return true
    }
}
