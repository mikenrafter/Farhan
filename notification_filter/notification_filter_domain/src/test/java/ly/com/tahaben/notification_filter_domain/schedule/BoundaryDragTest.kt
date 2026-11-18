package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * One boundary drag. Behavior follows Edge `pilot/sleep-editor-fixes` e0759a67f (timeline_model.dart
 * MagnifierDrag), in minutes: coarse = nearest 5 minutes; after hold = 1-minute steps at 0.25 of finger speed.
 * Fine steps use 1.0 minutes per pixel so expected values are exact: minutes moved = round(pixels x 0.25).
 * Deltas avoid x.5 so rounding mode is not pinned. proves: CLAIM(fine-drag-anchor)
 */
class BoundaryDragTest {

    // why: a fast drag lands on 5-minute marks.
    // catches: no rounding; rounding down only. 602.4/5=120.48->120 (600); 603/5=120.6->121 (605);
    //      607.4/5=121.48->121 (605); 607.6/5=121.52->122 (610).
    @Test
    fun `a coarse drag rounds to the nearest five minutes`() {
        val drag = BoundaryDrag(600)

        assertThat(drag.update(0f, 602.4f, 1f)).isEqualTo(600)
        assertThat(drag.update(0f, 603.0f, 1f)).isEqualTo(605)
        assertThat(drag.update(0f, 607.4f, 1f)).isEqualTo(605)
        assertThat(drag.update(0f, 607.6f, 1f)).isEqualTo(610)
    }

    // why: after holding still, the boundary must stay where it is (no jump to the finger's time), then move
    //      one minute per four pixels.
    // catches: fine mode that re-reads the finger time (would jump to 640); full-speed fine steps.
    // Hand math from 605: +5px -> 1.25 -> 1 (606); +7px -> 1.75 -> 2 (607); +12px -> 3.0 (608); back to 0px -> 605.
    @Test
    fun `holding anchors at the current value and moves at quarter speed`() {
        val drag = BoundaryDrag(600)
        drag.update(100f, 605f, 1f)

        assertThat(drag.hold()).isTrue()
        assertThat(drag.hold()).isFalse()
        assertThat(drag.isFine).isTrue()
        assertThat(drag.update(100f, 640f, 1f)).isEqualTo(605)
        assertThat(drag.update(105f, 640f, 1f)).isEqualTo(606)
        assertThat(drag.update(107f, 640f, 1f)).isEqualTo(607)
        assertThat(drag.update(112f, 640f, 1f)).isEqualTo(608)
        assertThat(drag.update(100f, 640f, 1f)).isEqualTo(605)
    }

    // why: when the drag is pinned at a clamp, the value the user sees is the clamped one; fine mode must anchor
    //      there, not at the finger's unclamped time.
    // catches: anchoring at the raw finger value. Range 600..840; finger at 560 shows 600. After hold, +16px
    //      is 4 minutes: 604. An unclamped anchor would give 560+4=564, clamped back to 600.
    @Test
    fun `holding while pinned at a clamp anchors at the visible value`() {
        val drag = BoundaryDrag(700) { it.coerceIn(600, 840) }

        assertThat(drag.update(0f, 560f, 1f)).isEqualTo(600)
        assertThat(drag.hold()).isTrue()
        assertThat(drag.update(50f, 560f, 1f)).isEqualTo(600)
        assertThat(drag.update(66f, 560f, 1f)).isEqualTo(604)
    }
}
