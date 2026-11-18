package ly.com.tahaben.notification_filter_domain.schedule

/**
 * The slice of the day the editor shows, in minutes. Zoom and pan change this view only; the schedule's full-day
 * coverage never depends on it. Shape after Edge `pilot/sleep-editor-fixes` e0759a67f `TimelineView`
 * (timeline_model.dart): a minimum span, widened about its centre, then shifted (not squeezed) back inside the day.
 */
data class TimelineView(val startMinute: Float, val endMinute: Float) {
    val span: Float get() = endMinute - startMinute
    val isFullDay: Boolean get() = span >= DaySchedule.DAY_MINUTES

    /** [minute]'s place across the view: 0 at the left edge, 1 at the right. */
    fun fraction(minute: Float): Float = (minute - startMinute) / span

    fun minuteAt(fraction: Float): Float = startMinute + span * fraction

    /** Shifts the view by [deltaMinutes], stopping at midnight on either side. A full-day view does not move. */
    fun panned(deltaMinutes: Float): TimelineView = shiftedInside(startMinute + deltaMinutes, span)

    /** Half the span (at least [MINIMUM_SPAN_MINUTES]) centred on [centerMinute]. */
    fun zoomedIn(centerMinute: Float): TimelineView = around(centerMinute, span / 2)

    /** Twice the span about the current centre; the full day is the limit. */
    fun zoomedOut(): TimelineView = around((startMinute + endMinute) / 2, span * 2)

    private fun around(center: Float, newSpan: Float): TimelineView {
        val width = newSpan.coerceIn(MINIMUM_SPAN_MINUTES, DaySchedule.DAY_MINUTES.toFloat())
        return shiftedInside(center - width / 2, width)
    }

    private fun shiftedInside(start: Float, width: Float): TimelineView {
        val day = DaySchedule.DAY_MINUTES.toFloat()
        val clampedStart = start.coerceIn(0f, day - width)
        return TimelineView(clampedStart, clampedStart + width)
    }

    companion object {
        /** Thirty minutes, as in Edge: close enough for one-minute steps, wide enough to see both neighbours. */
        const val MINIMUM_SPAN_MINUTES = 30f
        val FullDay = TimelineView(0f, DaySchedule.DAY_MINUTES.toFloat())
    }
}
