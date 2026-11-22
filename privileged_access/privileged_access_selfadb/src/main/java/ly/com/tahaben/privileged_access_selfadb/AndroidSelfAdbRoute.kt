package ly.com.tahaben.privileged_access_selfadb

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ly.com.tahaben.privileged_access_domain.PairingCode
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.RouteReadiness
import ly.com.tahaben.privileged_access_domain.SelfAdbRoute
import ly.com.tahaben.privileged_access_domain.ShellCommand
import ly.com.tahaben.privileged_access_domain.ShellResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Self-ADB over the phone's own wireless-debugging daemon.
 *
 *  - Pairing runs once, with the code the user typed. The code goes to libadb and is dropped; only the key is kept
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
    private val discovery: AdbDiscovery = AdbDiscovery(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
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
        if (Build.VERSION.SDK_INT < MIN_SDK) return@withContext PairingResult.AndroidTooOld
        val port = pairingPort ?: discovery.findPairingPort() ?: return@withContext PairingResult.PairingServiceNotFound
        val retained = keys.load()
        val identity = retained ?: try {
            IdentityFactory.generate(clock()).also(keys::save)
        } catch (failure: Exception) {
            return@withContext PairingResult.Failed
        }
        when (pairWithLibadb(identity, port, code)) {
            true -> PairingResult.Paired
            false -> {
                // A key that never paired is useless, so it is not left behind. A key that paired earlier stays.
                if (retained == null) keys.delete()
                PairingResult.Rejected
            }
            null -> {
                if (retained == null) keys.delete()
                PairingResult.Failed
            }
        }
    }

    /**
     * libadb's pairing opens a socket with no read timeout, so it runs on its own thread and is given up on after
     * [PAIR_TIMEOUT_MILLIS]. A thread stuck on a daemon that never answers lingers until its socket fails; each
     * attempt uses a new manager, so a stuck one blocks nothing else. Null = timed out or crashed, false = refused.
     */
    private suspend fun pairWithLibadb(identity: AdbIdentity, port: Int, code: PairingCode): Boolean? {
        val reveal = code.reveal()
        return withTimeoutOrNull(PAIR_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                thread(name = "adb-pairing", isDaemon = true) {
                    val outcome = try {
                        LibadbPairing(identity.privateKey(), identity.certificate()).let { manager ->
                            try { manager.pair(LOOPBACK, port, reveal) } finally { try { manager.disconnect() } catch (ignored: Exception) { } }
                        }
                    } catch (failure: Exception) {
                        // libadb throws for a refused code too; only "no answer" would be null, so this is a refusal.
                        false
                    }
                    if (continuation.isActive) continuation.resume(outcome)
                }
            }
        }
    }

    override fun forgetPairing() = keys.delete()

    override suspend fun run(command: ShellCommand): ShellResult = withContext(io) {
        passiveBlocker()?.let { return@withContext ShellResult.Blocked(it) }
        val identity = keys.load() ?: return@withContext ShellResult.Blocked(RouteBlocker.NOT_PAIRED)
        val tls = try {
            ConscryptTls(identity.privateKey(), identity.certificate())
        } catch (failure: Exception) {
            return@withContext ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED)
        }
        // adbd answers an unknown key with a TLS failure, and so does a port that changed between discovery and
        // connect. Try once more with a fresh port before deciding the key is rejected.
        var session: AdbWireSession? = null
        var lastFailure: ShellResult.Blocked? = null
        repeat(2) {
            if (session != null) return@repeat
            val port = discovery.findConnectPort() ?: return@withContext ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
            try {
                session = AdbWireSession.connect(LOOPBACK, port, tls)
            } catch (failure: AdbWireException.NotWirelessDebugging) {
                return@withContext ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
            } catch (failure: AdbWireException.PairingRequired) {
                lastFailure = ShellResult.Blocked(RouteBlocker.KEY_REJECTED)
            } catch (failure: SocketTimeoutException) {
                return@withContext ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED)
            } catch (failure: IOException) {
                return@withContext ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED)
            }
        }
        val open = session ?: return@withContext (lastFailure ?: ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED))
        try {
            open.use { execute(it, command) }
        } catch (failure: AdbWireException.ServiceRejected) {
            ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED)
        } catch (failure: IOException) {
            ShellResult.Blocked(RouteBlocker.CONNECTION_FAILED)
        }
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
        const val PAIR_TIMEOUT_MILLIS = 25_000L
        const val MAX_OUTPUT_BYTES = 16 * 1024
    }
}
