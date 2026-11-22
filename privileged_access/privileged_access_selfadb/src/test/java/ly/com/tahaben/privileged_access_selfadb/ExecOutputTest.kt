package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecOutputTest {
    @Test
    fun `the destination appends the sentinel to the command line`() {
        assertThat(ExecOutput.destination("pm grant a.b android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo("exec:pm grant a.b android.permission.WRITE_SECURE_SETTINGS && echo farhan-privileged-ok")
    }

    // why: `exec:` has no exit status; the sentinel is the only proof the command exited 0.
    // catches: treating an empty output as success (a failed command can print nothing).
    @Test
    fun `the sentinel on the last line means success and the rest is the output`() {
        assertThat(ExecOutput.parse("farhan-privileged-ok\n")).isEqualTo(ExecOutput.Parsed(0, ""))
        assertThat(ExecOutput.parse("Operation done\nfarhan-privileged-ok\r\n")).isEqualTo(ExecOutput.Parsed(0, "Operation done"))
    }

    @Test
    fun `no sentinel means failure and the text is kept`() {
        assertThat(ExecOutput.parse("")).isEqualTo(ExecOutput.Parsed(1, ""))
        assertThat(ExecOutput.parse("Exception occurred while executing 'grant':\nSecurityException: nope\n"))
            .isEqualTo(ExecOutput.Parsed(1, "Exception occurred while executing 'grant':\nSecurityException: nope"))
    }

    @Test
    fun `a sentinel that is not on the last line does not count`() {
        assertThat(ExecOutput.parse("farhan-privileged-ok\nmore\n").exitCode).isEqualTo(1)
        assertThat(ExecOutput.parse("xfarhan-privileged-ok\n").exitCode).isEqualTo(1)
    }
}
