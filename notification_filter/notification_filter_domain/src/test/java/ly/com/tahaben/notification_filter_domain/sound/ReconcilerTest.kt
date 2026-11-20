package ly.com.tahaben.notification_filter_domain.sound

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReconcilerTest {
    // why: v2.3 picks the stored ID when valid and only acts on the fixed condition under Farhan's ownership.
    // catches: treating a condition match as ownership or letting enumeration order override a valid journal ID.
    @Test
    fun `valid stored rule wins and Off releases only rules with both identity fields`() {
        val owner = "ly.com.tahaben.farhan_alpha"
        val condition = "condition://ly.com.tahaben.farhan_alpha/sound-control"
        val rules = listOf(
            ZenRule("first", owner, condition, true),
            ZenRule("saved", owner, condition, true),
            ZenRule("foreign", "other.app", condition, true),
            ZenRule("unrelated", owner, "condition://other-mode", true),
        )
        val ready = SoundPrerequisites(true, true, true, SoundGrants(true, true, true), BreakthroughChannel(true, 4))
        val reconciler = Reconciler(owner, condition)

        assertThat(reconciler.plan(SoundIntent(false, 8), ready, rules))
            .isEqualTo(Reconciliation(SoundControlState.Off, listOf(ReconcileAction.Release(listOf("first", "saved")))))
        assertThat(reconciler.plan(SoundIntent(true, 7, "saved"), ready, rules).state)
            .isEqualTo(SoundControlState.Held("saved"))
    }
}
