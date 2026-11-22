package ly.com.tahaben.notification_filter_presentation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_presentation.settings.showCodeAccessNote
import org.junit.Test

class CodeAccessNoteTest {
    @Test
    fun `the note shows when codes are raised, one was hidden and access is not allowed`() {
        assertThat(showCodeAccessNote(otpCodes = true, redactionSeen = true, accessNeeded = true)).isTrue()
    }

    @Test
    fun `the note hides as soon as access is allowed, even if the hidden flag is still set`() {
        assertThat(showCodeAccessNote(otpCodes = true, redactionSeen = true, accessNeeded = false)).isFalse()
    }

    @Test
    fun `the note stays hidden when codes are off or none was hidden`() {
        assertThat(showCodeAccessNote(otpCodes = false, redactionSeen = true, accessNeeded = true)).isFalse()
        assertThat(showCodeAccessNote(otpCodes = true, redactionSeen = false, accessNeeded = true)).isFalse()
    }
}
