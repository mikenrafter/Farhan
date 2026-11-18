package ly.com.tahaben.notification_filter_data.automation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomationAdmissionTest {
    // why: CLAIM(admission-bounded). A flood from an exported receiver must be refused at the door: only the
    //      permitted number is in flight, the rest fail immediately instead of queueing, and a finished request frees its slot.
    // catches: an unbounded queue (third request admitted); a leaked slot (fourth refused after a leave).
    @Test
    fun `only the permitted requests are admitted and a leave frees a slot`() {
        val admission = AutomationAdmission(permits = 2)

        assertThat(listOf(admission.tryEnter(), admission.tryEnter(), admission.tryEnter(), admission.tryEnter()))
            .containsExactly(true, true, false, false).inOrder()
        admission.leave()
        assertThat(admission.tryEnter()).isTrue()
        assertThat(admission.tryEnter()).isFalse()
    }
}
