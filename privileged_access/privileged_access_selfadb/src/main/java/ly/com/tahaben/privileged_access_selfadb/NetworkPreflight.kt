package ly.com.tahaben.privileged_access_selfadb

import ly.com.tahaben.privileged_access_domain.NetworkAccess
import ly.com.tahaben.privileged_access_domain.NetworkDenial
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The preflight's answer, and the one line the connection log and the check show for it. */
internal data class NetworkProbe(val access: NetworkAccess, val summary: String) {
    val denied: Boolean get() = access is NetworkAccess.Denied
}

/**
 * Asks, before anything else, whether the operating system lets Farhan open a socket. Some phones have a per-app
 * network switch (GrapheneOS calls it Network, under App info, Permissions) that makes every socket call fail with
 * EPERM while the manifest still asks for INTERNET. Nothing about pairing can work then, and the user should hear
 * that before typing a code.
 *
 * Two signals: `checkSelfPermission(INTERNET)`, and a self-test that opens a loopback server socket of its own and
 * connects to it, which touches no other service. Only the permission being refused, or the self-test failing with
 * EPERM, counts as denied; any other self-test failure is logged and left alone.
 */
internal class NetworkPreflight(
    private val permission: () -> Int,
    private val selfTest: () -> Throwable? = ::loopbackSelfTest,
    private val log: ConnectionLog,
) {
    fun run(operation: String = OPERATION): NetworkProbe {
        val granted = permission() == PERMISSION_GRANTED
        val failure = selfTest()
        val refusal = failure != null && NetworkDenial.isRefusal(failure)
        val permissionText = "checkSelfPermission(INTERNET)=${if (granted) "GRANTED" else "DENIED"}"
        val testText = when {
            failure == null -> "loopback self-test ok"
            else -> "loopback self-test failed: ${Trace.describe(failure)}"
        }
        val denied = !granted || refusal
        val summary = "Network permission: ${if (denied) "denied" else "granted"} ($permissionText; $testText)"
        log.add(operation, "network", if (denied) "DENIED" else "ok", "$permissionText; $testText")
        val access = if (denied) {
            NetworkAccess.Denied(
                permissionDenied = !granted,
                at = failure?.takeIf { refusal }?.let { StepFailure.of(SelfAdbStep.CONNECT_SOCKET, it) },
            )
        } else NetworkAccess.Allowed
        return NetworkProbe(access, summary)
    }

    companion object {
        const val OPERATION = "preflight"
        const val PERMISSION_GRANTED = 0 // PackageManager.PERMISSION_GRANTED, kept out so this runs in a JVM test

        /** Opens a loopback server socket and connects to it. Null when both work, otherwise what failed. */
        fun loopbackSelfTest(): Throwable? = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000) }
            }
            null
        } catch (failure: Throwable) {
            failure
        }
    }
}

/** The hint shown with a denial, in the check report and the log. The page's own wording is in the string resources. */
internal object NetworkHint {
    const val TEXT = "Farhan's network access is off. Farhan uses it only to talk to this phone. " +
        "Turn it on in App info, Permissions, Network (GrapheneOS), or in your phone's app network settings, then try again."
}
