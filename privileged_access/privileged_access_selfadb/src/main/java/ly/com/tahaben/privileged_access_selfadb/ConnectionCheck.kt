package ly.com.tahaben.privileged_access_selfadb

/** One service mDNS announced: where the daemon says it is. */
internal data class DiscoveredService(val type: String, val name: String, val host: String?, val port: Int)

internal data class WifiState(val summary: String, val ipv4: String?)

/** A TCP connect and how it went. */
internal data class TcpProbe(val millis: Long, val failure: Throwable?)

/** A TLS attempt up to the key export. [failedStep] names the step that broke it, when one did. */
internal data class TlsProbe(
    val millis: Long,
    val details: String,
    val firstRead: String,
    val exported: String,
    val failedStep: String?,
    val failure: Throwable?,
)

/** Everything the check asks of the phone, so the report can be assembled and tested without one. */
internal interface CheckProbes {
    /** Whether the operating system lets Farhan open sockets. Asked first. */
    fun networkProbe(): NetworkProbe

    fun phoneFacts(): List<String>
    fun wifi(): WifiState

    /** Every `_adb-tls-*` service found within [millis], for each of [types]. Progress notes go to [report]. */
    suspend fun discover(types: List<String>, millis: Long, report: (String) -> Unit): List<DiscoveredService>

    /** True when something already listens on [host]:[port] (a bind there fails). */
    fun listening(host: String, port: Int): Boolean
    fun tcp(host: String, port: Int): TcpProbe

    /** Sends a plain adb CONNECT to the port and says what came back: a wireless-debugging connect port answers STLS. */
    fun adbBanner(host: String, port: Int): String

    /** The ways of building the pairing TLS side the check compares, by label. Each stops before the code is used. */
    fun tlsVariants(): List<String>
    fun tls(variantIndex: Int, host: String, port: Int): TlsProbe
}

/**
 * The connection check: what this phone says about wireless debugging, who answers on which port, and how a TLS
 * handshake to the pairing port goes. It needs no pairing code and changes nothing: it opens connections and closes
 * them, and it stops before the first SPAKE2 message, so the code is never used up.
 */
internal class ConnectionCheck(
    private val probes: CheckProbes,
    private val log: ConnectionLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(typedPort: Int?): List<String> {
        val lines = mutableListOf<String>()
        fun say(section: String, text: String) {
            lines += text
            log.add(OPERATION, section, "info", text)
        }
        val started = clock()
        // First, ahead of everything: if the operating system refuses sockets, nothing below can work.
        val network = probes.networkProbe()
        say("network", network.summary + if (network.denied) ". ${NetworkHint.TEXT}" else "")
        say("start", "Connection check. It uses no pairing code and changes nothing on the phone.")
        probes.phoneFacts().forEach { say("phone", it) }
        if (network.denied) {
            say("end", "Network probes skipped: Farhan cannot open sockets. Turn its network access on, then run the check again.")
            return lines
        }
        val wifi = probes.wifi()
        say("wifi", "Wi-Fi: ${wifi.summary}" + if (wifi.ipv4 == null) " (\"none\" can be wrong when Farhan's network access is denied)" else "")

        val found = probes.discover(listOf(PAIRING_TYPE, CONNECT_TYPE), DISCOVERY_MILLIS) { say("mdns", it) }
        val hosts = listOfNotNull(LOOPBACK, wifi.ipv4)
        if (found.isEmpty()) say("mdns", "mDNS: no $PAIRING_TYPE or $CONNECT_TYPE service found in ${DISCOVERY_MILLIS / 1000} s.")
        for (service in found) {
            val where = hosts.joinToString(", ") { "$it ${if (probes.listening(it, service.port)) "listening" else "not listening"}" }
            say("mdns", "mDNS ${service.type.substringBefore('.')} \"${service.name}\" host=${service.host ?: "?"} port=${service.port}; $where")
        }

        val pairingPorts = found.filter { it.type.startsWith(PAIRING_TYPE.substringBefore('.')) }.map { it.port }
        val connectPorts = found.filter { it.type.startsWith(CONNECT_TYPE.substringBefore('.')) }.map { it.port }
        if (typedPort != null && typedPort in connectPorts) {
            say("port", "The typed port $typedPort is the connect port (_adb-tls-connect), not the pairing port.")
        }
        val candidates = linkedMapOf<Int, MutableList<String>>()
        typedPort?.let { candidates.getOrPut(it) { mutableListOf() } += "typed" }
        pairingPorts.forEach { candidates.getOrPut(it) { mutableListOf() } += "pairing service" }
        connectPorts.forEach { candidates.getOrPut(it) { mutableListOf() } += "connect service" }
        if (candidates.isEmpty()) say("port", "No port to try: none typed and none found by mDNS.")

        var handshakes = 0
        for ((port, roles) in candidates) {
            val reachable = mutableListOf<String>()
            for (host in hosts) {
                val result = probes.tcp(host, port)
                if (result.failure == null) reachable += host
                say("tcp", "TCP $host:$port (${roles.joinToString()}): " +
                    (result.failure?.let { "failed after ${result.millis} ms: ${Trace.describe(it)}" } ?: "connected in ${result.millis} ms"))
            }
            // A connect port speaks plain adb first, so a TLS handshake to it proves nothing; its banner says what it is.
            val connectOnly = port in connectPorts && port !in pairingPorts
            for (host in reachable) {
                if (port in connectPorts) say("banner", "adb banner $host:$port: ${probes.adbBanner(host, port)}")
                if (connectOnly) continue
                for ((index, label) in probes.tlsVariants().withIndex()) {
                    if (handshakes >= MAX_HANDSHAKES) { say("tls", "TLS: stopped after $MAX_HANDSHAKES handshakes."); break }
                    handshakes++
                    say("tls", "TLS $host:$port [$label]: " + describe(probes.tls(index, host, port)))
                }
            }
        }
        say("end", "Check finished in ${(clock() - started) / 1000.0} s. No pairing code was used and nothing on the phone changed.")
        return lines
    }

    private fun describe(probe: TlsProbe): String =
        if (probe.failure == null) {
            "handshake and key export ok in ${probe.millis} ms; ${probe.details}; first read: ${probe.firstRead}; exportKeyingMaterial: ${probe.exported}"
        } else {
            "FAILED at ${probe.failedStep ?: "?"} after ${probe.millis} ms: ${Trace.describe(probe.failure)}" +
                (if (probe.details.isNotBlank()) "; ${probe.details}" else "")
        }

    companion object {
        const val OPERATION = "check"
        const val LOOPBACK = "127.0.0.1"
        const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
        const val CONNECT_TYPE = "_adb-tls-connect._tcp"
        const val DISCOVERY_MILLIS = 8_000L
        const val MAX_HANDSHAKES = 16
    }
}
