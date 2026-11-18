package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The stillness timer rules with injected event time. proves: CLAIM(hold-timer) */
class BoundaryGestureTest {
    private fun sample(timeMillis: Long, px: Float, minute: Float = 600f) = DragSample(timeMillis, px, minute, 1f)

    // why: real fingers jitter. Jitter inside the tolerance must not keep the hold from firing; drift that adds up
    //      past the tolerance (measured from the last timer reset, not from the previous event) must restart it.
    // catches: tolerance 0 (hold never fires on glass); a tolerance measured event-to-event (slow drift never resets).
    // Hand math: reference 100, tolerance 4. At t=400 the finger is at 98 (|2|): timer still from t=0, 200 ms left.
    //      Drifting 1 px per 100 ms from 100: 105 at t=500 is the first |5| > 4, restarting at 500 (hold due at 1100).
    @Test
    fun `jitter inside the tolerance keeps the timer and cumulative drift restarts it`() {
        val jittery = BoundaryGesture(600, 0, { it }, stillnessTolerancePx = 4f)
        jittery.move(sample(0, 100f)); jittery.move(sample(200, 102f)); jittery.move(sample(400, 98f))
        assertThat(jittery.millisUntilHold(400)).isEqualTo(200)
        assertThat(jittery.tick(600)).isTrue()

        val drifting = BoundaryGesture(600, 0, { it }, stillnessTolerancePx = 4f)
        for (step in 0..5) drifting.move(sample(step * 100L, 100f + step))
        assertThat(drifting.millisUntilHold(500)).isEqualTo(600)
        assertThat(drifting.tick(1099)).isFalse()
        assertThat(drifting.tick(1100)).isTrue()
    }

    // why: within the tolerance the coarse position still follows the finger; only the timer ignores jitter.
    // catches: dropping in-tolerance samples entirely. Finger time 612 -> 610.
    @Test
    fun `a sample inside the tolerance still moves a coarse drag`() {
        val gesture = BoundaryGesture(600, 0, { it }, stillnessTolerancePx = 4f)
        gesture.move(sample(0, 100f, 600f))

        assertThat(gesture.move(sample(10, 101f, 612f))).isEqualTo(610)
    }

    // why: once fine, the timer is over: nothing left to wait for and a second tick does not re-anchor.
    // catches: re-activating (re-anchoring) on every tick.
    @Test
    fun `fine mode is entered once`() {
        val gesture = BoundaryGesture(600, 0, { it })
        assertThat(gesture.tick(599)).isFalse()
        assertThat(gesture.tick(600)).isTrue()
        assertThat(gesture.tick(700)).isFalse()
        assertThat(gesture.millisUntilHold(700)).isNull()
    }
}
