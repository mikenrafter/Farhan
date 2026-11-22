package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException

class NetworkDenialTest {
    // why: the exact text the owner's phone gave, and the variants other Android versions use for the same errno.
    @Test fun `EPERM in any wording is a refusal`() {
        listOf(
            "socket failed: EPERM (Operation not permitted)",
            "failed to connect to /127.0.0.1 (port 4) from /:: (port 0) after 5000ms: isConnected failed: EPERM (Operation not permitted)",
            "Operation not permitted",
            "connect failed: eperm",
        ).forEach { assertThat(NetworkDenial.isRefusal(SocketException(it))).isTrue() }
    }

    // why: a refused port or a reset is a different problem. catches: blaming the network setting for them.
    @Test fun `other socket failures are not a refusal`() {
        listOf("Connection refused", "Connection reset", "Software caused connection abort", "Socket closed", "ENETUNREACH (Network is unreachable)")
            .forEach { assertThat(NetworkDenial.isRefusal(SocketException(it))).isFalse() }
        assertThat(NetworkDenial.isRefusal(ConnectException("failed to connect: ECONNREFUSED (Connection refused)"))).isFalse()
        assertThat(NetworkDenial.isRefusal(null)).isFalse()
        assertThat(NetworkDenial.isRefusal(SocketException())).isFalse()
    }

    @Test fun `a refusal inside a cause chain is found, and a cycle does not loop`() {
        val wrapped = IOException("outer", IllegalStateException("middle", SocketException("socket failed: EPERM (Operation not permitted)")))
        assertThat(NetworkDenial.isRefusal(wrapped)).isTrue()
        val a = RuntimeException("a"); val b = RuntimeException("b", a); a.initCause(b)
        assertThat(NetworkDenial.isRefusal(a)).isFalse()
    }

    // why: the message is safe to show only for a refusal. catches: a message of any other failure reaching the page.
    @Test fun `a step failure keeps the message only for a refusal`() {
        val refused = StepFailure.of(SelfAdbStep.CONNECT_SOCKET, SocketException("socket failed: EPERM (Operation not permitted)"))
        assertThat(refused).isEqualTo(StepFailure(SelfAdbStep.CONNECT_SOCKET, "SocketException", "socket failed: EPERM (Operation not permitted)", true))
        val other = StepFailure.of(SelfAdbStep.CONNECT_SOCKET, ConnectException("failed to connect to /127.0.0.1 (port 4)"))
        assertThat(other).isEqualTo(StepFailure(SelfAdbStep.CONNECT_SOCKET, "ConnectException"))
        assertThat(other.detail).isNull()
        assertThat(other.networkDenied).isFalse()
    }

    @Test fun `the refusal text is shortened`() {
        val text = NetworkDenial.refusalText(SocketException("EPERM " + "x".repeat(500)))!!
        assertThat(text.length).isAtMost(160)
    }
}
