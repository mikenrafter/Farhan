package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.NetworkAccess
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException

class NetworkPreflightTest {
    private val log = ConnectionLog()
    private fun preflight(permission: Int = 0, selfTest: Throwable? = null) = NetworkPreflight({ permission }, { selfTest }, log)
    private val eperm = SocketException("socket failed: EPERM (Operation not permitted)")

    @Test fun `a granted permission and a working loopback is allowed`() {
        val probe = preflight().run()
        assertThat(probe.access).isEqualTo(NetworkAccess.Allowed)
        assertThat(probe.summary).contains("granted")
        assertThat(probe.summary).contains("checkSelfPermission(INTERNET)=GRANTED")
    }

    // why: the owner's Pixel. catches: treating a refused socket as a protocol problem and asking for a code.
    @Test fun `a loopback socket refused with EPERM is denied, with the refusal as evidence`() {
        val probe = preflight(selfTest = eperm).run()
        val denied = probe.access as NetworkAccess.Denied
        assertThat(denied.permissionDenied).isFalse()
        assertThat(denied.at).isEqualTo(StepFailure(SelfAdbStep.CONNECT_SOCKET, "SocketException", "socket failed: EPERM (Operation not permitted)", networkDenied = true))
        assertThat(probe.summary).contains("EPERM")
    }

    // why: on GrapheneOS the permission check is the cheap tell. catches: ignoring it when the self-test happens to pass.
    @Test fun `a denied INTERNET permission is denied even if the loopback works`() {
        val probe = preflight(permission = -1).run()
        val denied = probe.access as NetworkAccess.Denied
        assertThat(denied.permissionDenied).isTrue()
        assertThat(denied.at).isNull()
        assertThat(probe.summary).contains("checkSelfPermission(INTERNET)=DENIED")
    }

    // why: a loopback hiccup is not a policy decision. catches: blaming the user's settings for an unrelated failure.
    @Test fun `a loopback failure that is not EPERM is not a denial`() {
        val probe = preflight(selfTest = ConnectException("Connection refused")).run()
        assertThat(probe.access).isEqualTo(NetworkAccess.Allowed)
        assertThat(probe.summary).contains("loopback self-test failed")
    }

    @Test fun `the answer is written to the connection log`() {
        preflight(selfTest = eperm).run()
        assertThat(log.text()).contains("| preflight | network | DENIED |")
    }

    @Test fun `the real loopback self-test passes on this machine`() {
        assertThat(NetworkPreflight.loopbackSelfTest()).isNull()
    }
}
