package ly.com.tahaben.privileged_access_selfadb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Finds this phone's own wireless-debugging ports through service discovery, so the user never types a port.
 * Wireless debugging announces `_adb-tls-pairing._tcp` while the pairing dialog is open and `_adb-tls-connect._tcp`
 * while it is on. Discovery also sees other phones on the Wi-Fi, so a candidate counts only when its port is already
 * taken on this phone's loopback (the daemon is listening there); the same check the Shizuku client makes.
 */
internal class AdbDiscovery(
    private val context: Context,
    private val portIsOurs: (Int) -> Boolean = ::portInUseHere,
) {
    suspend fun findPairingPort(timeoutMillis: Long = 10_000): Int? = find(PAIRING_TYPE, timeoutMillis)

    suspend fun findConnectPort(timeoutMillis: Long = 6_000): Int? = find(CONNECT_TYPE, timeoutMillis)

    @Suppress("DEPRECATION")
    private suspend fun find(type: String, timeoutMillis: Long): Int? = withTimeoutOrNull(timeoutMillis) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        suspendCancellableCoroutine { continuation ->
            val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
            val resolving = AtomicBoolean(false)
            val done = AtomicBoolean(false)
            lateinit var discovery: NsdManager.DiscoveryListener

            fun finish(port: Int?) {
                if (!done.compareAndSet(false, true)) return
                try { manager.stopServiceDiscovery(discovery) } catch (ignored: RuntimeException) { }
                if (continuation.isActive) continuation.resume(port)
            }

            fun resolveNext() {
                if (done.get() || !resolving.compareAndSet(false, true)) return
                val info = pending.poll() ?: run { resolving.set(false); return }
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false); resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving.set(false)
                        if (portIsOurs(serviceInfo.port)) finish(serviceInfo.port) else resolveNext()
                    }
                })
            }

            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    pending.add(serviceInfo); resolveNext()
                }
            }
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    try { manager.stopServiceDiscovery(discovery) } catch (ignored: RuntimeException) { }
                }
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
            } catch (failure: RuntimeException) {
                finish(null)
            }
        }
    }

    companion object {
        const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
        const val CONNECT_TYPE = "_adb-tls-connect._tcp"

        /** True when something on this phone already listens on [port]: binding it fails. */
        fun portInUseHere(port: Int): Boolean = try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close(); false
        } catch (failure: IOException) {
            true
        }
    }
}
