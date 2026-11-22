package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

class ConnectionCheckTest {
    private class FakeProbes(
        val services: List<DiscoveredService> = emptyList(),
        val wifi: WifiState = WifiState("connected", "192.168.1.5"),
        val tcpFailures: Map<String, Throwable> = emptyMap(),
        val tlsResult: (Int, String, Int) -> TlsProbe = { _, _, _ -> TlsProbe(80, "protocol=TLSv1.3 cipher=X peer=CN=00 sig=SHA256withRSA", "ok", "ok (64 bytes)", null, null) },
    ) : CheckProbes {
        var network = NetworkProbe(ly.com.tahaben.privileged_access_domain.NetworkAccess.Allowed, "Network permission: granted (checkSelfPermission(INTERNET)=GRANTED; loopback self-test ok)")
        override fun networkProbe() = network
        val tlsCalls = mutableListOf<String>()
        val bannerCalls = mutableListOf<String>()
        override fun phoneFacts() = listOf("Android 17 (SDK 37), Farhan targetSdk 35", "adb_enabled=1, adb_wifi_enabled=1")
        override fun wifi() = wifi
        override suspend fun discover(types: List<String>, millis: Long, report: (String) -> Unit): List<DiscoveredService> = services
        override fun listening(host: String, port: Int) = host == "127.0.0.1"
        override fun tcp(host: String, port: Int) = TcpProbe(3, tcpFailures["$host:$port"])
        override fun adbBanner(host: String, port: Int): String { bannerCalls += "$host:$port"; return "STLS (wireless-debugging connect port)" }
        override fun tlsVariants() = listOf("bundled", "platform")
        override fun tls(variantIndex: Int, host: String, port: Int): TlsProbe { tlsCalls += "$variantIndex $host:$port"; return tlsResult(variantIndex, host, port) }
    }

    private val log = ConnectionLog()
    private suspend fun run(probes: FakeProbes, typed: Int? = null) = ConnectionCheck(probes, log).run(typed)

    // why: the report is what the owner sends. catches: a missing section, or a report that does not say nothing was used.
    @Test fun `the report has the phone, Wi-Fi, mDNS, TCP and TLS lines and says no code was used`() = runTest {
        val probes = FakeProbes(listOf(DiscoveredService("_adb-tls-pairing._tcp", "adb-ABC", "192.168.1.5", 41234)))
        val lines = run(probes)
        assertThat(lines[1]).contains("no pairing code")
        assertThat(lines.any { it.startsWith("Android 17") }).isTrue()
        assertThat(lines.any { it.startsWith("Wi-Fi: connected") }).isTrue()
        assertThat(lines.any { it.contains("mDNS _adb-tls-pairing \"adb-ABC\" host=192.168.1.5 port=41234; 127.0.0.1 listening, 192.168.1.5 not listening") }).isTrue()
        assertThat(lines.any { it.startsWith("TCP 127.0.0.1:41234 (pairing service): connected in 3 ms") }).isTrue()
        assertThat(lines.any { it.startsWith("TCP 192.168.1.5:41234") }).isTrue()
        assertThat(lines.count { it.startsWith("TLS 127.0.0.1:41234 [") }).isEqualTo(2)
        assertThat(lines.any { it.contains("handshake and key export ok") && it.contains("exportKeyingMaterial: ok (64 bytes)") }).isTrue()
        assertThat(lines.last()).contains("No pairing code was used and nothing on the phone changed")
    }

    // why: the check must not send SPAKE2. catches: any probe beyond TLS; the fake has no pairing-message call at all.
    @Test fun `nothing is run beyond the TLS probe for each reachable host`() = runTest {
        val probes = FakeProbes(listOf(DiscoveredService("_adb-tls-pairing._tcp", "p", "192.168.1.5", 41234)))
        run(probes)
        assertThat(probes.tlsCalls).containsExactly("0 127.0.0.1:41234", "1 127.0.0.1:41234", "0 192.168.1.5:41234", "1 192.168.1.5:41234").inOrder()
    }

    @Test fun `a refused host is reported with its exception and is not probed for TLS`() = runTest {
        val probes = FakeProbes(
            listOf(DiscoveredService("_adb-tls-pairing._tcp", "p", "192.168.1.5", 41234)),
            tcpFailures = mapOf("192.168.1.5:41234" to ConnectException("Connection refused")),
        )
        val lines = run(probes)
        assertThat(lines.any { it.startsWith("TCP 192.168.1.5:41234") && it.contains("failed after 3 ms: java.net.ConnectException: Connection refused") }).isTrue()
        assertThat(probes.tlsCalls.none { it.contains("192.168.1.5") }).isTrue()
    }

    // why: the comparison across ways of connecting. catches: a handshake failure reported without class, message and step.
    @Test fun `a failed handshake names the step, the class and the message`() = runTest {
        val probes = FakeProbes(
            listOf(DiscoveredService("_adb-tls-pairing._tcp", "p", null, 41234)),
            tlsResult = { variant, _, _ ->
                if (variant == 0) TlsProbe(12, "", "", "", "tls-handshake", SSLHandshakeException("alert certificate_unknown"))
                else TlsProbe(30, "protocol=TLSv1.3 cipher=X", "ok", "ok (64 bytes)", null, null)
            },
        )
        val lines = run(probes)
        assertThat(lines.any { it.contains("[bundled]: FAILED at tls-handshake after 12 ms: javax.net.ssl.SSLHandshakeException: alert certificate_unknown") }).isTrue()
        assertThat(lines.any { it.contains("[platform]: handshake and key export ok") }).isTrue()
    }

    // why: the owner may type the connect port. catches: a TLS probe of a connect port, which proves nothing.
    @Test fun `the connect port gets a banner and no TLS probe, and a typed connect port is called out`() = runTest {
        val probes = FakeProbes(listOf(DiscoveredService("_adb-tls-connect._tcp", "c", "192.168.1.5", 37000)))
        val lines = run(probes, typed = 37000)
        assertThat(lines.any { it.contains("typed port 37000 is the connect port") }).isTrue()
        assertThat(lines.any { it.startsWith("adb banner 127.0.0.1:37000: STLS") }).isTrue()
        assertThat(probes.tlsCalls).isEmpty()
    }

    @Test fun `a typed port with no mDNS service is still probed, and no port at all says so`() = runTest {
        val typed = FakeProbes()
        assertThat(run(typed, typed = 40000).any { it.startsWith("TCP 127.0.0.1:40000 (typed)") }).isTrue()
        assertThat(run(FakeProbes()).any { it.contains("No port to try") }).isTrue()
        assertThat(run(FakeProbes()).any { it.contains("no _adb-tls-pairing._tcp or _adb-tls-connect._tcp service found") }).isTrue()
    }

    @Test fun `every report line is also in the connection log`() = runTest {
        val lines = run(FakeProbes(), typed = 40000)
        assertThat(log.lines()).hasSize(lines.size)
        assertThat(log.text()).contains("| check | tcp | info | TCP 127.0.0.1:40000")
    }

    // why: the owner's Pixel refused every socket with EPERM. catches: a report that starts with phone facts, or one that
    // goes on to probe ports that cannot work.
    @Test fun `a denied network is the first line, with the hint, and the probes are skipped`() = runTest {
        val denied = ly.com.tahaben.privileged_access_domain.NetworkAccess.Denied(permissionDenied = false, at = null)
        val probes = FakeProbes(
            listOf(DiscoveredService("_adb-tls-pairing._tcp", "p", "192.168.1.5", 41234)),
            wifi = WifiState("none", null),
        ).also { it.network = NetworkProbe(denied, "Network permission: denied (checkSelfPermission(INTERNET)=GRANTED; loopback self-test failed: java.net.SocketException: socket failed: EPERM (Operation not permitted))") }
        val lines = run(probes, typed = 4000)
        assertThat(lines.first()).startsWith("Network permission: denied")
        assertThat(lines.first()).contains("EPERM")
        assertThat(lines.first()).contains("Farhan's network access is off")
        assertThat(lines.last()).contains("Network probes skipped")
        assertThat(lines.none { it.startsWith("TCP ") || it.startsWith("TLS ") || it.startsWith("mDNS") }).isTrue()
        assertThat(probes.tlsCalls).isEmpty()
    }

    @Test fun `a granted network is still the first line`() = runTest {
        assertThat(run(FakeProbes()).first()).startsWith("Network permission: granted")
    }

    // why: with network access off the interface list can be empty. catches: reading "none" as a fact.
    @Test fun `a missing Wi-Fi address says it can be wrong`() = runTest {
        val lines = run(FakeProbes(wifi = WifiState("phone Wi-Fi IPv4 none", null)))
        assertThat(lines.any { it.startsWith("Wi-Fi:") && it.contains("can be wrong when Farhan's network access is denied") }).isTrue()
    }
}
