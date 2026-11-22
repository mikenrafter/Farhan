package ly.com.tahaben.privileged_access_selfadb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * What the phone says about its network. The app strips ACCESS_NETWORK_STATE on purpose, so ConnectivityManager is
 * only asked when that permission is held; otherwise the interface list gives the Wi-Fi address (no permission needed).
 */
internal object NetworkFacts {
    fun wifi(context: Context): WifiState {
        val interfaces = interfaceAddresses()
        val wifiIp = interfaces.firstOrNull { it.first.startsWith("wlan") }?.second
        val listed = if (interfaces.isEmpty()) "no up interface with an IPv4 address" else interfaces.joinToString(", ") { "${it.first} ${it.second}" }
        val state = connectivity(context)
        return WifiState("phone Wi-Fi IPv4 ${wifiIp ?: "none"}; interfaces: $listed; $state", wifiIp)
    }

    /** Up, non-loopback interfaces and their first IPv4 address, in system order. */
    private fun interfaceAddresses(): List<Pair<String, String>> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.mapNotNull { nic ->
            nic.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress?.let { nic.name to it }
        }
    } catch (failure: Exception) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun connectivity(context: Context): String {
        if (context.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE") != PackageManager.PERMISSION_GRANTED) {
            return "connectivity state not readable (this app has no ACCESS_NETWORK_STATE)"
        }
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            val kind = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            "active network $kind, validated=${if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "yes" else "no"}"
        } catch (failure: Exception) {
            "connectivity state failed (${Trace.describe(failure)})"
        }
    }
}

/** The check's questions answered by this phone. Nothing here sends a pairing message or writes a setting. */
internal class AndroidCheckProbes(
    private val context: Context,
    private val identity: () -> AdbIdentity,
    private val log: ConnectionLog,
    private val preflight: NetworkPreflight,
) : CheckProbes {
    override fun networkProbe(): NetworkProbe = preflight.run(ConnectionCheck.OPERATION)

    private val wifiState: WifiState by lazy { NetworkFacts.wifi(context) }

    override fun phoneFacts(): List<String> {
        val resolver = context.contentResolver
        fun setting(name: String) = try { Settings.Global.getInt(resolver, name, -1).let { if (it < 0) "unknown" else it.toString() } } catch (failure: Exception) { failure.javaClass.simpleName }
        val manager = context.packageManager
        val permission = LOCAL_NETWORK_PERMISSION
        val granted = manager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
        val declared = try {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(permission) == true
        } catch (failure: Exception) { false }
        return listOf(
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), Farhan targetSdk ${context.applicationInfo.targetSdkVersion}, ${Build.MANUFACTURER} ${Build.MODEL}",
            "adb_enabled=${setting("adb_enabled")}, adb_wifi_enabled=${setting("adb_wifi_enabled")}, development_settings_enabled=${setting("development_settings_enabled")}",
            "ACCESS_LOCAL_NETWORK: declared=${if (declared) "yes" else "no"}, granted=${if (granted) "yes" else "no"}",
        )
    }

    override fun wifi(): WifiState = wifiState

    override suspend fun discover(types: List<String>, millis: Long, report: (String) -> Unit): List<DiscoveredService> {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val pending = ConcurrentLinkedQueue<Pair<String, NsdServiceInfo>>()
        val found = mutableListOf<DiscoveredService>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        for (type in types) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = report("mDNS discovery started for $serviceType")
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = report("mDNS discovery could not start for $serviceType (NSD error $errorCode)")
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = report("mDNS service lost: ${serviceInfo.serviceName}")
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    report("mDNS service seen: ${serviceInfo.serviceName} ${serviceInfo.serviceType}")
                    pending.add(type to serviceInfo)
                }
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners += listener
            } catch (failure: RuntimeException) {
                report("mDNS discovery for $type threw ${Trace.describe(failure)}")
            }
        }
        val deadline = System.currentTimeMillis() + millis
        try {
            while (System.currentTimeMillis() < deadline) {
                val next = pending.poll()
                if (next == null) { delay(100); continue }
                val remaining = deadline - System.currentTimeMillis()
                val resolved = withTimeoutOrNull(minOf(RESOLVE_MILLIS, maxOf(remaining, 500))) { resolve(manager, next.second, report) }
                if (resolved != null) found += DiscoveredService(next.first, next.second.serviceName, resolved.first, resolved.second)
                else report("mDNS could not resolve ${next.second.serviceName}")
            }
        } finally {
            listeners.forEach { try { manager.stopServiceDiscovery(it) } catch (ignored: RuntimeException) { } }
        }
        return found
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo, report: (String) -> Unit): Pair<String?, Int>? =
        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            try {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        report("mDNS resolve failed for ${serviceInfo.serviceName} (NSD error $errorCode)")
                        if (done.compareAndSet(false, true)) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (done.compareAndSet(false, true)) continuation.resume(serviceInfo.host?.hostAddress to serviceInfo.port)
                    }
                })
            } catch (failure: RuntimeException) {
                report("mDNS resolve threw ${Trace.describe(failure)}")
                if (done.compareAndSet(false, true)) continuation.resume(null)
            }
        }

    override fun listening(host: String, port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName(host)).close(); false
    } catch (failure: java.io.IOException) {
        true
    }

    override fun tcp(host: String, port: Int): TcpProbe {
        val started = System.nanoTime()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), TCP_MILLIS)
            TcpProbe((System.nanoTime() - started) / 1_000_000, null)
        } catch (failure: Throwable) {
            TcpProbe((System.nanoTime() - started) / 1_000_000, failure)
        } finally {
            try { socket.close() } catch (ignored: java.io.IOException) { }
        }
    }

    override fun adbBanner(host: String, port: Int): String = try {
        Socket().use { socket ->
            socket.soTimeout = BANNER_MILLIS
            socket.connect(InetSocketAddress(host, port), TCP_MILLIS)
            Frame.write(socket.getOutputStream(), Frame.CNXN, 0x01000001, 4096, "host::\u0000".toByteArray(Charsets.US_ASCII))
            val frame = Frame.read(socket.getInputStream(), 1 shl 20)
            if (frame.command == Frame.STLS) "STLS (wireless-debugging connect port)" else "command 0x${frame.command.toString(16)} (not a wireless-debugging connect port)"
        }
    } catch (failure: Throwable) {
        "no banner: ${Trace.describe(failure)}"
    }

    private val variants: List<PairingVariant> by lazy {
        // The loopback ones only: the check tries each host itself. The Wi-Fi variant is a host choice, not a TLS one.
        PairingVariants.of(identity(), wifiAddress = null)
    }

    override fun tlsVariants(): List<String> = variants.map { it.label }

    override fun tls(variantIndex: Int, host: String, port: Int): TlsProbe {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val notes = StringBuilder()
        val noting = object : StepLog {
            override fun reached(operation: String, step: SelfAdbStep) = Unit
            override fun failed(operation: String, failure: ly.com.tahaben.privileged_access_domain.StepFailure, cause: Throwable?) = Unit
            override fun note(operation: String, step: SelfAdbStep?, text: String) {
                if (step == SelfAdbStep.TLS_HANDSHAKE && text.startsWith("protocol=")) notes.append(text)
            }
        }
        // Tee: the steps go into the connection log, and the TLS details line is also kept for the report.
        val both = TeeLog(AndroidStepLog(log), noting)
        return try {
            PairingTlsOpener.open(Stepper(both, ConnectionCheck.OPERATION), variants[variantIndex].transport, host, port).use { opened ->
                TlsProbe(elapsed(), notes.toString(), "ok", "ok (${opened.keyingMaterial.size} bytes)", null, null)
            }
        } catch (stopped: StepException) {
            TlsProbe(elapsed(), notes.toString(), "", "", stopped.step.tag, stopped.cause ?: stopped)
        } catch (failure: Throwable) {
            TlsProbe(elapsed(), notes.toString(), "", "", "?", failure)
        }
    }

    private class TeeLog(private val a: StepLog, private val b: StepLog) : StepLog {
        override fun reached(operation: String, step: SelfAdbStep) { a.reached(operation, step); b.reached(operation, step) }
        override fun failed(operation: String, failure: ly.com.tahaben.privileged_access_domain.StepFailure, cause: Throwable?) { a.failed(operation, failure, cause); b.failed(operation, failure, cause) }
        override fun note(operation: String, step: SelfAdbStep?, text: String) { a.note(operation, step, text); b.note(operation, step, text) }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val RESOLVE_MILLIS = 3_000L
        const val TCP_MILLIS = 3_000
        const val BANNER_MILLIS = 3_000
    }
}
