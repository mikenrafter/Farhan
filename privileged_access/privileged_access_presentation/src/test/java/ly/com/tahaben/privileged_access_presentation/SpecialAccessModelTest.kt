package ly.com.tahaben.privileged_access_presentation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessFailure
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.AccessState
import ly.com.tahaben.privileged_access_domain.LinkLostReason
import ly.com.tahaben.privileged_access_domain.LinkState
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.PrivilegedCommand
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.RoutePreference
import ly.com.tahaben.privileged_access_domain.RouteReadiness
import org.junit.Test

class SpecialAccessModelTest {
    private fun status(
        sensitive: AccessState,
        secure: AccessState,
        link: LinkState = LinkState.None,
        routes: Map<Route, RouteReadiness> = emptyMap(),
    ) = PrivilegedStatus(
        checked = true,
        link = link,
        accesses = mapOf(Access.SENSITIVE_NOTIFICATIONS to sensitive, Access.SECURE_SETTINGS to secure),
        routes = routes,
    )

    private val adb = LinkState.Connected(Route.SELF_ADB)
    private val shizuku = LinkState.Connected(Route.SHIZUKU)

    @Test
    fun `no link and not allowed shows the Allow button`() {
        val s = status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED)
        assertThat(accessControl(s, Access.SENSITIVE_NOTIFICATIONS)).isEqualTo(AccessControl.ALLOW_BUTTON)
        assertThat(accessControl(s, Access.SECURE_SETTINGS)).isEqualTo(AccessControl.ALLOW_BUTTON)
    }

    @Test
    fun `a retained link turns the button into an off switch`() {
        val s = status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED, adb)
        assertThat(accessControl(s, Access.SECURE_SETTINGS)).isEqualTo(AccessControl.SWITCH_OFF)
    }

    @Test
    fun `an allowed access is a switch that is on, with or without a link`() {
        assertThat(accessControl(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED), Access.SENSITIVE_NOTIFICATIONS))
            .isEqualTo(AccessControl.SWITCH_ON)
        assertThat(accessControl(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, shizuku), Access.SENSITIVE_NOTIFICATIONS))
            .isEqualTo(AccessControl.SWITCH_ON)
    }

    @Test
    fun `sensitive notifications are hidden before Android 15`() {
        val s = status(AccessState.NOT_APPLICABLE, AccessState.NOT_ALLOWED)
        assertThat(accessControl(s, Access.SENSITIVE_NOTIFICATIONS)).isEqualTo(AccessControl.HIDDEN)
        assertThat(sensitiveNeedsAllowing(s)).isFalse()
    }

    @Test
    fun `before the first check a row has no control`() {
        assertThat(accessControl(PrivilegedStatus.UNCHECKED, Access.SECURE_SETTINGS)).isEqualTo(AccessControl.PENDING)
        assertThat(sensitiveNeedsAllowing(PrivilegedStatus.UNCHECKED)).isFalse()
    }

    @Test
    fun `the red note needs allowing only while sensitive notifications are not allowed`() {
        assertThat(sensitiveNeedsAllowing(status(AccessState.NOT_ALLOWED, AccessState.ALLOWED))).isTrue()
        assertThat(sensitiveNeedsAllowing(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED))).isFalse()
    }

    @Test
    fun `the note under the group follows the link`() {
        assertThat(groupNote(status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED))).isEqualTo(GroupNote.NO_LINK)
        assertThat(groupNote(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, adb))).isEqualTo(GroupNote.WIRELESS_DEBUGGING)
        assertThat(groupNote(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, shizuku))).isEqualTo(GroupNote.SHIZUKU)
        assertThat(groupNote(status(AccessState.NOT_APPLICABLE, AccessState.ALLOWED, adb))).isEqualTo(GroupNote.WIRELESS_DEBUGGING_ONE)
        assertThat(linkRoute(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, shizuku))).isEqualTo(Route.SHIZUKU)
    }

    @Test
    fun `the flow starts on the link's route, then a Shizuku preference, then wireless debugging`() {
        val none = status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED)
        assertThat(defaultFlowRoute(none, RoutePreference.ASK)).isEqualTo(Route.SELF_ADB)
        assertThat(defaultFlowRoute(none, RoutePreference.SHIZUKU)).isEqualTo(Route.SHIZUKU)
        assertThat(defaultFlowRoute(status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, shizuku), RoutePreference.ASK)).isEqualTo(Route.SHIZUKU)
    }

    @Test
    fun `Shizuku status reads the route's readiness`() {
        fun of(r: RouteReadiness?) = shizukuStatus(status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED, routes = listOfNotNull(r?.let { Route.SHIZUKU to it }).toMap()))
        assertThat(of(RouteReadiness.Ready)).isEqualTo(ShizukuStatus.RUNNING)
        assertThat(of(RouteReadiness.Blocked(RouteBlocker.SHIZUKU_PERMISSION_NEEDED))).isEqualTo(ShizukuStatus.RUNNING)
        assertThat(of(RouteReadiness.Blocked(RouteBlocker.SHIZUKU_NOT_RUNNING))).isEqualTo(ShizukuStatus.NOT_RUNNING)
        assertThat(of(RouteReadiness.Blocked(RouteBlocker.SHIZUKU_NOT_INSTALLED))).isEqualTo(ShizukuStatus.NOT_INSTALLED)
        assertThat(of(RouteReadiness.Blocked(RouteBlocker.SHIZUKU_TOO_OLD))).isEqualTo(ShizukuStatus.TOO_OLD)
    }

    @Test
    fun `every typed failure maps to a plain kind`() {
        assertThat(failureViewOf(AccessFailure.LinkLost(LinkLostReason.NOT_PAIRED)).kind).isEqualTo(FailureKind.NOT_PAIRED)
        assertThat(failureViewOf(AccessFailure.LinkLost(LinkLostReason.KEY_REJECTED)).kind).isEqualTo(FailureKind.KEY_REJECTED)
        assertThat(failureViewOf(AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF)).kind).isEqualTo(FailureKind.WIRELESS_DEBUGGING_OFF)
        assertThat(failureViewOf(AccessFailure.ShizukuNotRunning).kind).isEqualTo(FailureKind.SHIZUKU_NOT_RUNNING)
        assertThat(failureViewOf(AccessFailure.RouteBlocked(Route.SELF_ADB, RouteBlocker.CONNECTION_FAILED)).kind).isEqualTo(FailureKind.CONNECTION_FAILED)
        assertThat(failureViewOf(AccessFailure.NotEffective(Route.SELF_ADB)).kind).isEqualTo(FailureKind.NOT_EFFECTIVE)
        assertThat(failureViewOf(AccessFailure.Busy).kind).isEqualTo(FailureKind.BUSY)
        assertThat(failureViewOf(AccessFailure.InvalidAppId).kind).isEqualTo(FailureKind.INVALID_APP)
        RouteBlocker.entries.forEach { failureKindOf(it) }
    }

    @Test
    fun `a failed command keeps the shell's output, and blank output is dropped`() {
        val with = failureViewOf(AccessFailure.CommandFailed(Route.SELF_ADB, 1, "Security exception"))
        assertThat(with).isEqualTo(FailureView(FailureKind.COMMAND_FAILED, "Security exception"))
        assertThat(failureViewOf(AccessFailure.CommandFailed(Route.SELF_ADB, 1, "")).detail).isNull()
    }

    @Test
    fun `pairing results map to failures, and Paired is none`() {
        assertThat(failureViewOf(PairingResult.Paired)).isNull()
        assertThat(failureViewOf(PairingResult.Rejected)?.kind).isEqualTo(FailureKind.PAIRING_REJECTED)
        assertThat(failureViewOf(PairingResult.PairingServiceNotFound)?.kind).isEqualTo(FailureKind.PAIRING_SERVICE_NOT_FOUND)
        assertThat(failureViewOf(PairingResult.Failed)?.kind).isEqualTo(FailureKind.PAIRING_FAILED)
        assertThat(failureViewOf(PairingResult.AndroidTooOld)?.kind).isEqualTo(FailureKind.ANDROID_TOO_OLD)
    }

    private fun done(command: PrivilegedCommand, route: Route) = AccessResult.Done(command, route, verified = true)

    @Test
    fun `a grant says Allowed`() {
        val after = status(AccessState.ALLOWED, AccessState.NOT_ALLOWED, adb)
        assertThat(toggleOutcome(done(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.ALLOWED))
    }

    @Test
    fun `a revoke with another access still allowed says Revoked`() {
        val after = status(AccessState.NOT_ALLOWED, AccessState.ALLOWED, adb)
        assertThat(toggleOutcome(done(PrivilegedCommand.REVOKE_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.REVOKED))
    }

    @Test
    fun `revoking the last access over wireless debugging says the pairing is forgotten`() {
        val after = status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED)
        assertThat(toggleOutcome(done(PrivilegedCommand.REVOKE_SECURE_SETTINGS, Route.SELF_ADB), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.BOTH_OFF_FORGOTTEN))
    }

    @Test
    fun `revoking the last access over Shizuku says the Shizuku permission stays`() {
        val after = status(AccessState.NOT_ALLOWED, AccessState.NOT_ALLOWED, shizuku)
        assertThat(toggleOutcome(done(PrivilegedCommand.REVOKE_SECURE_SETTINGS, Route.SHIZUKU), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.BOTH_OFF_SHIZUKU_STAYS))
    }

    @Test
    fun `on a phone with one access the last revoke does not say both`() {
        val after = status(AccessState.NOT_APPLICABLE, AccessState.NOT_ALLOWED)
        assertThat(toggleOutcome(done(PrivilegedCommand.REVOKE_SECURE_SETTINGS, Route.SELF_ADB), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.ONLY_ACCESS_OFF_FORGOTTEN))
        assertThat(toggleOutcome(done(PrivilegedCommand.REVOKE_SECURE_SETTINGS, Route.SHIZUKU), after))
            .isEqualTo(ToggleOutcome.Say(ToggleMessage.ONLY_ACCESS_OFF_SHIZUKU_STAYS))
    }

    @Test
    fun `a lost link sends the switch to the flow on wireless debugging with the failure shown`() {
        val result = AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.KEY_REJECTED))
        assertThat(toggleOutcome(result, PrivilegedStatus.UNCHECKED))
            .isEqualTo(ToggleOutcome.OpenFlow(FailureView(FailureKind.KEY_REJECTED), Route.SELF_ADB))
    }

    @Test
    fun `no connection to run over sends the switch to the flow`() {
        assertThat(toggleOutcome(AccessResult.ChooseRoute(Route.entries), PrivilegedStatus.UNCHECKED))
            .isEqualTo(ToggleOutcome.OpenFlow(FailureView(FailureKind.NOT_CONNECTED), null))
    }

    @Test
    fun `other failures stay on the page as a message`() {
        listOf(AccessFailure.ShizukuNotRunning, AccessFailure.Busy, AccessFailure.NotEffective(Route.SELF_ADB)).forEach {
            assertThat(toggleOutcome(AccessResult.Failed(it), PrivilegedStatus.UNCHECKED)).isInstanceOf(ToggleOutcome.Warn::class.java)
        }
    }
}
