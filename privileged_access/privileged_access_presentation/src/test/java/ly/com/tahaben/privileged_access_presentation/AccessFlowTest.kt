package ly.com.tahaben.privileged_access_presentation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessFailure
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.LinkLostReason
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import ly.com.tahaben.privileged_access_domain.PrivilegedCommand
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult
import org.junit.Test

class AccessFlowTest {
    private val sensitive = AccessFlow(Access.SENSITIVE_NOTIFICATIONS, Route.SELF_ADB)

    @Test
    fun `wireless debugging pairs, then grants, then reports Allowed`() {
        val pairing = sensitive.start()
        assertThat(pairing.phase).isEqualTo(FlowPhase.PAIRING)
        assertThat(pairing.busy).isTrue()
        val granting = pairing.pairingDone(PairingResult.Paired)
        assertThat(granting.phase).isEqualTo(FlowPhase.GRANTING)
        val done = granting.accessDone(AccessResult.Done(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB, verified = true))
        assertThat(done.phase).isEqualTo(FlowPhase.IDLE)
        assertThat(done.outcome).isEqualTo(FlowOutcome.Allowed(Route.SELF_ADB, verified = true))
    }

    @Test
    fun `Shizuku asks for its permission first, then grants`() {
        val flow = sensitive.withRoute(Route.SHIZUKU).start()
        assertThat(flow.phase).isEqualTo(FlowPhase.SHIZUKU_PERMISSION)
        assertThat(flow.shizukuPermissionDone(ShizukuPermissionResult.Granted).phase).isEqualTo(FlowPhase.GRANTING)
    }

    @Test
    fun `a refused pairing stops with the failure and does not grant`() {
        val flow = sensitive.start().pairingDone(PairingResult.Rejected)
        assertThat(flow.phase).isEqualTo(FlowPhase.IDLE)
        assertThat(flow.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.PAIRING_REJECTED)))
    }

    @Test
    fun `a pairing that broke at a step stops with that step on the page`() {
        val at = StepFailure(SelfAdbStep.TLS_HANDSHAKE, "SSLHandshakeException")
        val flow = sensitive.start().pairingDone(PairingResult.StepFailed(at))
        assertThat(flow.phase).isEqualTo(FlowPhase.IDLE)
        assertThat(flow.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.PAIRING_FAILED, step = at)))
    }

    @Test
    fun `Shizuku not running stops with its message`() {
        val flow = sensitive.withRoute(Route.SHIZUKU).start()
            .shizukuPermissionDone(ShizukuPermissionResult.Blocked(RouteBlocker.SHIZUKU_NOT_RUNNING))
        assertThat(flow.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.SHIZUKU_NOT_RUNNING)))
    }

    @Test
    fun `a failed grant shows the failure and Try again returns to the form on the same route`() {
        val failed = sensitive.start().pairingDone(PairingResult.Paired)
            .accessDone(AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF)))
        assertThat(failed.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.WIRELESS_DEBUGGING_OFF)))
        val again = failed.retry()
        assertThat(again.outcome).isNull()
        assertThat(again.route).isEqualTo(Route.SELF_ADB)
        assertThat(again.busy).isFalse()
    }

    @Test
    fun `a second tap while running changes nothing`() {
        val running = sensitive.start()
        assertThat(running.start()).isEqualTo(running)
    }

    @Test
    fun `the route cannot change mid-run or after a result`() {
        assertThat(sensitive.start().withRoute(Route.SHIZUKU).route).isEqualTo(Route.SELF_ADB)
        val failed = sensitive.start().pairingDone(PairingResult.Failed)
        assertThat(failed.withRoute(Route.SHIZUKU).route).isEqualTo(Route.SELF_ADB)
    }

    @Test
    fun `a result that arrives out of phase is ignored`() {
        assertThat(sensitive.pairingDone(PairingResult.Paired)).isEqualTo(sensitive)
        assertThat(sensitive.accessDone(AccessResult.Failed(AccessFailure.Busy))).isEqualTo(sensitive)
        assertThat(sensitive.start().shizukuPermissionDone(ShizukuPermissionResult.Granted).phase).isEqualTo(FlowPhase.PAIRING)
    }

    @Test
    fun `a flow opened with a failure shows it before the form`() {
        val flow = AccessFlow.openedWithFailure(Access.SECURE_SETTINGS, Route.SELF_ADB, FailureView(FailureKind.KEY_REJECTED))
        assertThat(flow.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.KEY_REJECTED)))
        assertThat(flow.start()).isEqualTo(flow)
        assertThat(flow.retry().outcome).isNull()
    }

    @Test
    fun `choose-route never ends in success`() {
        val flow = sensitive.start().pairingDone(PairingResult.Paired).accessDone(AccessResult.ChooseRoute(Route.entries))
        assertThat(flow.outcome).isEqualTo(FlowOutcome.Failed(FailureView(FailureKind.NOT_CONNECTED)))
    }

    @Test
    fun `code and port input keeps digits only and caps the length`() {
        assertThat(codeInput("12 34-56789")).isEqualTo("123456")
        assertThat(portInput("4a4b4c4d4e4f")).isEqualTo("44444")
    }

    @Test
    fun `pairing needs six digits and a blank or valid port`() {
        assertThat(canPair("123456", "")).isTrue()
        assertThat(canPair("123456", "37215")).isTrue()
        assertThat(canPair("12345", "")).isFalse()
        assertThat(canPair("123456", "0")).isFalse()
        assertThat(canPair("123456", "70000")).isFalse()
    }
}
