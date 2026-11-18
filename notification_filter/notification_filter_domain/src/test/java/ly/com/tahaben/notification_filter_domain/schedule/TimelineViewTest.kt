package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** View arithmetic after Edge's TimelineView.clampTo/zoomAt (timeline_model.dart). All values are exact in Float. */
class TimelineViewTest {
    // why: zoom halves the span about the centre but shifts, never squeezes, at midnight.
    // catches: a view that goes past 1440 or shrinks near the edge. 1430 centre, span 720: start 1070 -> 720.
    @Test
    fun `zooming near midnight shifts the view inside the day`() {
        assertThat(TimelineView.FullDay.zoomedIn(720f)).isEqualTo(TimelineView(360f, 1080f))
        assertThat(TimelineView.FullDay.zoomedIn(1430f)).isEqualTo(TimelineView(720f, 1440f))
    }

    // why: one-minute precision needs a floor; the floor is 30 minutes (Edge).
    // catches: a span that reaches 0 or goes below 30. 1440 halves to 45, then 22.5 -> 30.
    @Test
    fun `zoom stops at thirty minutes and zoom out returns to the full day`() {
        var view = TimelineView.FullDay
        repeat(8) { view = view.zoomedIn(720f) }
        assertThat(view.span).isEqualTo(30f)
        repeat(8) { view = view.zoomedOut() }
        assertThat(view).isEqualTo(TimelineView.FullDay)
    }

    // why: panning stops at midnight on both sides, and a full-day view has nothing to pan.
    // catches: pan past the day; pan that changes the span.
    @Test
    fun `pan is clamped to the day`() {
        val view = TimelineView(540f, 900f)
        assertThat(view.panned(10_000f)).isEqualTo(TimelineView(1080f, 1440f))
        assertThat(view.panned(-10_000f)).isEqualTo(TimelineView(0f, 360f))
        assertThat(TimelineView.FullDay.panned(50f)).isEqualTo(TimelineView.FullDay)
    }
}
