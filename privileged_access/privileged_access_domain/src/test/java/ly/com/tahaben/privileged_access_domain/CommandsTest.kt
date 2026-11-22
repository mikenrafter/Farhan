package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandsTest {
    private val app = AppId.parse("ly.com.tahaben.farhan_alpha")!!

    // why: the argument lists are the whole contract with the shell; a changed word is a different command.
    // catches: a typo in an app-op name, `appops reset` (wipes every app-op) instead of `set ... default`.
    @Test
    fun `each command builds exactly these arguments`() {
        assertThat(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS.shellCommand(app).args)
            .containsExactly("appops", "set", "ly.com.tahaben.farhan_alpha", "RECEIVE_SENSITIVE_NOTIFICATIONS", "allow").inOrder()
        assertThat(PrivilegedCommand.REVOKE_SENSITIVE_NOTIFICATIONS.shellCommand(app).args)
            .containsExactly("appops", "set", "ly.com.tahaben.farhan_alpha", "RECEIVE_SENSITIVE_NOTIFICATIONS", "default").inOrder()
        assertThat(PrivilegedCommand.GRANT_SECURE_SETTINGS.shellCommand(app).args)
            .containsExactly("pm", "grant", "ly.com.tahaben.farhan_alpha", "android.permission.WRITE_SECURE_SETTINGS").inOrder()
        assertThat(PrivilegedCommand.REVOKE_SECURE_SETTINGS.shellCommand(app).args)
            .containsExactly("pm", "revoke", "ly.com.tahaben.farhan_alpha", "android.permission.WRITE_SECURE_SETTINGS").inOrder()
    }

    @Test
    fun `the command line is the arguments joined by single spaces`() {
        assertThat(PrivilegedCommand.GRANT_SECURE_SETTINGS.shellCommand(app).line)
            .isEqualTo("pm grant ly.com.tahaben.farhan_alpha android.permission.WRITE_SECURE_SETTINGS")
    }

    // why: the application id is the only variable part of any command line.
    // catches: a package name that smuggles a second command or a path.
    @Test
    fun `an application id with shell characters or no dot is not an AppId`() {
        listOf("", "farhan", "a.b c", "a.b;reboot", "a.b&&id", "a.b\$(id)", "a.b`id`", "a..b", ".a.b", "a.b.", "../x.y", "a.b\n", "1a.b", "a.b|c")
            .forEach { assertThat(AppId.parse(it)).isNull() }
        assertThat(AppId.parse("a".repeat(260) + ".bb")).isNull()
        assertThat(AppId.parse("ly.com.tahaben.farhan_alpha")).isNotNull()
        assertThat(AppId.parse("ly.com.tahaben.farhan")).isNotNull()
    }

    // why: a process that executes commands (Shizuku's user service) re-checks them, so a caller that is not Farhan's own code cannot widen what runs.
    @Test
    fun `only the two programs with safe words are allowed`() {
        val good = PrivilegedCommand.entries.map { it.shellCommand(app).args }
        good.forEach { assertThat(ShellCommand.isAllowed(it)).isTrue() }
        assertThat(ShellCommand.isAllowed(listOf("sh", "-c", "id"))).isFalse()
        assertThat(ShellCommand.isAllowed(listOf("pm", "grant", "a.b", "android.permission.X;id"))).isFalse()
        assertThat(ShellCommand.isAllowed(listOf("pm", "grant"))).isFalse()
        assertThat(ShellCommand.isAllowed(listOf("pm", "install", "a.b", "-r", "x", "y", "z", "w", "v"))).isFalse()
        assertThat(ShellCommand.isAllowed(listOf("pm", "grant", "a b", "android.permission.X"))).isFalse()
        assertThat(ShellCommand.isAllowed(emptyList())).isFalse()
    }

    @Test
    fun `a command the builder would not make cannot be created`() {
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.of(listOf("rm", "-rf", "x")) }
    }

    @Test
    fun `every access has one grant and one revoke`() {
        Access.entries.forEach { access ->
            assertThat(PrivilegedCommand.of(access, true).grants).isTrue()
            assertThat(PrivilegedCommand.of(access, false).grants).isFalse()
            assertThat(PrivilegedCommand.of(access, false).access).isEqualTo(access)
        }
        assertThat(PrivilegedCommand.entries).hasSize(4)
    }

    // why: the code is a secret the user typed once.
    // catches: logging the code through toString (string templates, Timber, exception messages).
    @Test
    fun `a pairing code is six digits and prints as stars`() {
        assertThat(PairingCode.parse(" 123456 ")?.reveal()).isEqualTo("123456")
        listOf("12345", "1234567", "12345a", "", "１２３４５６").forEach { assertThat(PairingCode.parse(it)).isNull() }
        assertThat(PairingCode.parse("123456").toString()).doesNotContain("123456")
        assertThat("${PairingCode.parse("654321")}").doesNotContain("654321")
    }
}
