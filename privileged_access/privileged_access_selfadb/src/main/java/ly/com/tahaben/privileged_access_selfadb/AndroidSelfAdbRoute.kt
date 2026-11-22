package ly.com.tahaben.privileged_access_selfadb

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ly.com.tahaben.privileged_access_domain.NetworkAccess
import ly.com.tahaben.privileged_access_domain.NetworkDenial
import ly.com.tahaben.privileged_access_domain.PairingCode
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.RouteReadiness
import ly.com.tahaben.privileged_access_domain.SelfAdbRoute
import ly.com.tahaben.privileged_access_domain.ShellCommand
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.ShellResult
import ly.com.tahaben.privileged_access_domain.StepFailure
import java.io.ByteArrayOutputStream

/**
 * Self-ADB over the phone's own wireless-debugging daemon.
 *
 *  - Pairing runs once, with the code the user typed. The code goes to the pairing client and is dropped; only the key is kept
 *    ([AdbKeyFile]). Nothing here logs a code, a key, a port or any command output.
 *  - A command runs over a fresh TLS connection to `127.0.0.1` with the retained key, so a second access needs no pairing.
 *  - [run] takes a [ShellCommand], which cannot hold free text.
 *
 * Not checked on a real phone: the emulator's adbd does not require authentication
 * (research/settings-rescue-device-report.md), so a secure adbd accepting this key is unverified.
 */
internal class AndroidSelfAdbRoute(
    private val context: Context,
    private val keys: AdbKeyFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trail: ConnectionLog = ConnectionLog(),
    private val log: StepLog = AndroidStepLog(trail),
    private val discovery: AdbDiscovery = AdbDiscovery(context, log = log),
    private val preflight: NetworkPreflight = NetworkPreflight(
        permission = { context.checkSelfPermission(android.Manifest.permission.INTERNET) },
        log = trail,
    ),
) : SelfAdbRoute {
    override val route = Route.SELF_ADB

    override suspend fun readiness(): RouteReadiness = passiveBlocker()?.let { RouteReadiness.Blocked(it) } ?: RouteReadiness.Ready

    /** Looks at the stored key and the wireless-debugging setting; opens nothing. */
    private fun passiveBlocker(): RouteBlocker? = when {
        Build.VERSION.SDK_INT < MIN_SDK -> RouteBlocker.ANDROID_TOO_OLD
        !keys.exists() -> RouteBlocker.NOT_PAIRED
        wirelessDebuggingEnabled() == false -> RouteBlocker.WIRELESS_DEBUGGING_OFF
        else -> null
    }

    /** `adb_wifi_enabled` is a readable global setting: 1 on, 0 off. Null when this phone does not say. */
    private fun wirelessDebuggingEnabled(): Boolean? = when (Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, -1)) {
        1 -> true
        0 -> false
        else -> null
    }

    override suspend fun pair(code: PairingCode, pairingPort: Int?): PairingResult = withContext(io) {
        // Nothing that mentions the code is kept in the connection log while it is in use.
        trail.guard(setOf(code.reveal()))
        try {
            pairGuarded(code, pairingPort)
        } finally {
            trail.guard(emptySet())
        }
    }

    override suspend fun networkAccess(): NetworkAccess = withContext(io) { preflight.run().access }

    /** The preflight, then false when it says the network is off. [denied] gets the answer to hand back. */
    private fun networkOff(): NetworkAccess.Denied? = preflight.run().access as? NetworkAccess.Denied

    private suspend fun pairGuarded(code: PairingCode, pairingPort: Int?): PairingResult {
        if (Build.VERSION.SDK_INT < MIN_SDK) return PairingResult.AndroidTooOld
        networkOff()?.let { return PairingResult.NetworkDenied(it.at) }
        val typed = pairingPort
        log.note(PAIR, null, "start: Android SDK ${Build.VERSION.SDK_INT}, ${if (typed != null) "typed port $typed" else "no typed port, using discovery"}")
        val port = typed ?: when (val found = discovery.findPairingPort()) {
            is AdbDiscovery.Found.Port -> found.port.also { log.note(PAIR, SelfAdbStep.DISCOVERY, "found pairing port $it") }
            AdbDiscovery.Found.Nothing -> { log.note(PAIR, SelfAdbStep.DISCOVERY, "no pairing service found"); return PairingResult.PairingServiceNotFound }
            is AdbDiscovery.Found.Broke -> return networkOffAfter(found.at)?.let { PairingResult.NetworkDenied(it) } ?: PairingResult.StepFailed(found.at)
        }
        val retained = keys.load()
        val identity = retained ?: try {
            log.reached(PAIR, SelfAdbStep.KEY_SETUP)
            IdentityFactory.generate(clock()).also(keys::save)
        } catch (failure: Throwable) {
            return PairingResult.StepFailed(StepFailure.of(SelfAdbStep.KEY_SETUP, failure).also { log.failed(PAIR, it, failure) })
        }
        val outcome = try {
            val publicKey = AndroidPubkey.encodeWithName(identity.publicKey(), DEVICE_NAME)
            val wifi = NetworkFacts.wifi(context)
            log.note(PAIR, null, "Wi-Fi: ${wifi.summary}")
            val client = AdbPairingClient(PairingVariants.of(identity, wifi.ipv4, clock()), log = log)
            val first = client.pair(LOOPBACK, port, code.reveal(), publicKey)
            // A typed port that fails before the code is used is often a stale one, or the connect port copied from the
            // wrong screen. The dialog's own port is the one discovery finds, so try that once.
            val other = if (typed != null && PairingRetry.worthRetrying(first)) {
                (discovery.findPairingPort(RETRY_DISCOVERY_MILLIS) as? AdbDiscovery.Found.Port)?.port?.takeIf { it != typed }
            } else null
            if (other == null) first else {
                log.note(PAIR, SelfAdbStep.DISCOVERY, "retrying with the discovered pairing port $other")
                PairingRetry.report(first, client.pair(LOOPBACK, other, code.reveal(), publicKey))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Building the transport or the key bytes failed before any socket was opened.
            PairingOutcome.Broke(StepFailure.of(SelfAdbStep.KEY_SETUP, failure).also { log.failed(PAIR, it, failure) })
        }
        if (outcome != PairingOutcome.Paired && retained == null) {
            // A key that never paired is useless, so it is not left behind. A key that paired earlier stays.
            keys.delete()
        }
        return when (outcome) {
            PairingOutcome.Paired -> PairingResult.Paired
            PairingOutcome.Rejected -> PairingResult.Rejected
            is PairingOutcome.Broke -> if (outcome.at.networkDenied) PairingResult.NetworkDenied(outcome.at) else PairingResult.StepFailed(outcome.at)
        }
    }

    /**
     * A discovery that could not start gives no reason of its own. Ask the preflight again: if the network is off that
     * is the reason, and the failure it found is returned.
     */
    private fun networkOffAfter(discoveryFailure: StepFailure): StepFailure? {
        if (discoveryFailure.exceptionClass != NetworkDenial.NSD_START_FAILED && !discoveryFailure.networkDenied) return null
        val denied = networkOff() ?: return if (discoveryFailure.networkDenied) discoveryFailure else null
        return denied.at ?: discoveryFailure
    }

    override suspend fun checkConnection(typedPort: Int?): List<String> = withContext(io) {
        val probes = AndroidCheckProbes(context, { keys.load() ?: IdentityFactory.generate(clock()) }, trail, preflight)
        ConnectionCheck(probes, trail).run(typedPort)
    }

    override fun connectionLogText(): String = trail.text()

    override fun forgetPairing() = keys.delete()

    override suspend fun run(command: ShellCommand): ShellResult = withContext(io) {
        passiveBlocker()?.let { return@withContext ShellResult.Blocked(it) }
        val identity = keys.load() ?: return@withContext ShellResult.Blocked(RouteBlocker.NOT_PAIRED)
        networkOff()?.let { return@withContext ShellResult.Blocked(RouteBlocker.NETWORK_DENIED, it.at) }
        log.reached(CONNECT, SelfAdbStep.CONNECT)
        val tls = try {
            ConscryptTls(identity.privateKey(), identity.certificate())
        } catch (failure: Throwable) {
            return@withContext blocked(RouteBlocker.CONNECTION_FAILED, failure)
        }
        // adbd answers an unknown key with a TLS failure, and so does a port that changed between discovery and
        // connect. Try once more with a fresh port before deciding the key is rejected.
        var session: AdbWireSession? = null
        var lastFailure: ShellResult.Blocked? = null
        repeat(2) {
            if (session != null) return@repeat
            val port = when (val found = discovery.findConnectPort()) {
                is AdbDiscovery.Found.Port -> found.port
                AdbDiscovery.Found.Nothing -> return@withContext ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
                is AdbDiscovery.Found.Broke -> return@withContext networkOffAfter(found.at)
                    ?.let { ShellResult.Blocked(RouteBlocker.NETWORK_DENIED, it) }
                    ?: ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED, found.at)
            }
            try {
                session = AdbWireSession.connect(LOOPBACK, port, tls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AdbWireException.NotWirelessDebugging) {
                log.failed(CONNECT, StepFailure.of(SelfAdbStep.CONNECT, failure), failure)
                return@withContext ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
            } catch (failure: AdbWireException.PairingRequired) {
                // The TLS failure is the cause; its class is what tells a rejected key from a broken handshake.
                lastFailure = blocked(RouteBlocker.KEY_REJECTED, failure.cause ?: failure)
            } catch (failure: Throwable) {
                // A timeout, a reset, a TLS error or anything the connect did not expect.
                return@withContext blocked(RouteBlocker.CONNECTION_FAILED, failure)
            }
        }
        val open = session ?: return@withContext (lastFailure ?: ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED))
        try {
            open.use { execute(it, command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            blocked(RouteBlocker.CONNECTION_FAILED, failure)
        }
    }

    /** A blocked result that names the exception class and logs it. */
    private fun blocked(blocker: RouteBlocker, failure: Throwable): ShellResult.Blocked {
        val at = StepFailure.of(SelfAdbStep.CONNECT, failure).also { log.failed(CONNECT, it, failure) }
        return ShellResult.Blocked(if (at.networkDenied) RouteBlocker.NETWORK_DENIED else blocker, at)
    }

    private fun execute(session: AdbWireSession, command: ShellCommand): ShellResult {
        val collected = ByteArrayOutputStream()
        val stream = session.open(ExecOutput.destination(command.line))
        try {
            val buffer = ByteArray(4096)
            while (collected.size() < MAX_OUTPUT_BYTES) {
                val count = stream.read(buffer, 0, buffer.size)
                if (count < 0) break
                collected.write(buffer, 0, count)
            }
        } finally {
            stream.close()
        }
        val parsed = ExecOutput.parse(String(collected.toByteArray(), Charsets.UTF_8))
        return ShellResult.Ran(parsed.exitCode, parsed.output)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MIN_SDK = Build.VERSION_CODES.R
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val RETRY_DISCOVERY_MILLIS = 3_000L
        const val PAIR = "pair"
        const val CONNECT = "connect"
        const val DEVICE_NAME = "Farhan"
        const val MAX_OUTPUT_BYTES = 16 * 1024
    }
}
