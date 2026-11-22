package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.RouteBlocker.*
import org.junit.Test

class AccessMapperTest {
    // why: the UI branches on these types, so each blocker must land on the right one.
    // catches: a rejected key shown as a generic blocked route (the user would not be sent to pairing).
    @Test
    fun `blockers become typed failures`() {
        assertThat(AccessMapper.failureOf(Route.SELF_ADB, NOT_PAIRED)).isEqualTo(AccessFailure.LinkLost(LinkLostReason.NOT_PAIRED))
        assertThat(AccessMapper.failureOf(Route.SELF_ADB, KEY_REJECTED)).isEqualTo(AccessFailure.LinkLost(LinkLostReason.KEY_REJECTED))
        assertThat(AccessMapper.failureOf(Route.SELF_ADB, WIRELESS_DEBUGGING_OFF)).isEqualTo(AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF))
        assertThat(AccessMapper.failureOf(Route.SHIZUKU, SHIZUKU_NOT_RUNNING)).isEqualTo(AccessFailure.ShizukuNotRunning)
        listOf(ANDROID_TOO_OLD, CONNECTION_FAILED, SHIZUKU_NOT_INSTALLED, SHIZUKU_TOO_OLD, SHIZUKU_PERMISSION_NEEDED, SHIZUKU_PERMISSION_DENIED, SHIZUKU_SERVICE_FAILED)
            .forEach { assertThat(AccessMapper.failureOf(Route.SHIZUKU, it)).isEqualTo(AccessFailure.RouteBlocked(Route.SHIZUKU, it)) }
    }

    @Test
    fun `a failed command carries its exit code and cleaned output`() {
        val failure = AccessMapper.commandFailure(Route.SELF_ADB, ShellResult.Ran(255, "  Exception occurred while executing 'grant':\u0007\nSecurityException  \n"))
        assertThat(failure).isEqualTo(AccessFailure.CommandFailed(Route.SELF_ADB, 255, "Exception occurred while executing 'grant':\nSecurityException"))
    }

    @Test
    fun `output is capped so a runaway shell cannot fill the screen`() {
        val cleaned = OutputText.clean("x".repeat(5000))
        assertThat(cleaned.length).isAtMost(1001)
        assertThat(cleaned).endsWith("…")
        assertThat(OutputText.clean("")).isEmpty()
    }

    @Test
    fun `only the Shizuku in-app prompt counts as fixable without leaving Farhan`() {
        assertThat(RouteBlocker.entries.filter { it.askInApp }).containsExactly(SHIZUKU_PERMISSION_NEEDED)
    }
}
