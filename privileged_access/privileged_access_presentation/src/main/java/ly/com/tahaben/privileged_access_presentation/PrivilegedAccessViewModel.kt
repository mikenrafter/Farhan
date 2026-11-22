package ly.com.tahaben.privileged_access_presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.NetworkAccess
import ly.com.tahaben.privileged_access_domain.PairingCode
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.PrivilegedAccess
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RoutePreference
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult
import javax.inject.Inject

/** What the connector's screens show. [status] carries the link and each access; the rest is the last thing the user did. */
data class PrivilegedAccessState(
    val status: PrivilegedStatus = PrivilegedStatus.UNCHECKED,
    val routePreference: RoutePreference = RoutePreference.DEFAULT,
    /** True while a command, a pairing or a permission prompt is in flight. */
    val working: Boolean = false,
    /** The last grant or revoke, until [PrivilegedAccessViewModel.dismissResult]. */
    val lastResult: AccessResult? = null,
    val lastPairing: PairingResult? = null,
    val lastShizukuPermission: ShizukuPermissionResult? = null,
    /** The self-ADB connection check: running now, and the lines of the last run. */
    val connectionCheck: ConnectionCheckState = ConnectionCheckState(),
    /** Whether the operating system lets Farhan open sockets, as last asked. */
    val networkAccess: NetworkAccess = NetworkAccess.Allowed,
)

/**
 * The calls the UI makes. Each one is something the user started: the connector runs no command on its own.
 * Re-check on return to the foreground with [onForeground]; the app also does it for the whole process.
 */
@HiltViewModel
class PrivilegedAccessViewModel @Inject constructor(
    private val access: PrivilegedAccess,
) : ViewModel() {
    private val activity = MutableStateFlow(Activity())

    val state: StateFlow<PrivilegedAccessState> = combine(access.status, access.routePreference, activity) { status, preference, activity ->
        PrivilegedAccessState(status, preference, activity.working, activity.result, activity.pairing, activity.shizuku, activity.check, activity.network)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PrivilegedAccessState())

    init {
        status()
    }

    /** Re-reads the link and each access. Runs nothing on the phone. */
    fun status() {
        viewModelScope.launch { access.refresh() }
    }

    fun onForeground() = status()

    /** [route] null: the stored preference, then the retained link; with neither, the result is `ChooseRoute`. */
    fun grantSensitiveNotifications(route: Route? = null) = command { access.grant(Access.SENSITIVE_NOTIFICATIONS, route) }

    fun grantSecureSettings(route: Route? = null) = command { access.grant(Access.SECURE_SETTINGS, route) }

    fun revokeSensitiveNotifications(route: Route? = null) = command { access.revoke(Access.SENSITIVE_NOTIFICATIONS, route) }

    fun revokeSecureSettings(route: Route? = null) = command { access.revoke(Access.SECURE_SETTINGS, route) }

    /** [code] and [pairingPort] are the raw text fields. A blank port means "find it"; the code is never kept. */
    fun pairSelfAdb(code: String, pairingPort: String = "") {
        val parsedCode = PairingCode.parse(code)
        val portText = pairingPort.trim()
        val port = portText.toIntOrNull()
        val portInvalid = portText.isNotEmpty() && (port == null || port !in 1..65535)
        if (parsedCode == null || portInvalid) return reject()
        working {
            activity.update { it.copy(pairing = null) }
            val result = access.pairSelfAdb(parsedCode, port)
            activity.update { it.copy(pairing = result) }
        }
    }

    /**
     * Runs the connection check: opens connections, closes them, uses no pairing code and changes nothing. [portText] is
     * the raw port field; blank or invalid means no typed port.
     */
    fun checkConnection(portText: String = "") {
        if (activity.value.check.running) return
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        viewModelScope.launch {
            activity.update { it.copy(check = ConnectionCheckState(running = true, lines = emptyList())) }
            val lines = try {
                access.checkSelfAdbConnection(port)
            } catch (failure: Exception) {
                listOf("The check could not run: ${failure.javaClass.simpleName}")
            }
            activity.update { it.copy(check = ConnectionCheckState(running = false, lines = lines)) }
        }
    }

    /** Asks whether Farhan may open sockets. The pairing page does this when it opens, before it asks for a code. */
    fun checkNetworkAccess() {
        viewModelScope.launch {
            val result = try { access.checkNetworkAccess() } catch (failure: Exception) { NetworkAccess.Allowed }
            activity.update { it.copy(network = result) }
        }
    }

    /** The whole connection log, for Copy and Share. */
    fun connectionLog(): String = access.selfAdbLog()

    fun requestShizukuPermission() = working {
        val result = access.requestShizukuPermission()
        activity.update { it.copy(shizuku = result) }
    }

    fun setRoutePreference(preference: RoutePreference) {
        viewModelScope.launch { access.setRoutePreference(preference) }
    }

    fun forgetSelfAdbKey() {
        viewModelScope.launch { access.forgetSelfAdbKey() }
    }

    fun dismissResult() = activity.update { it.copy(result = null, pairing = null, shizuku = null) }

    private fun reject() = activity.update { it.copy(pairing = PairingResult.Rejected) }

    private fun command(block: suspend () -> AccessResult) = working {
        activity.update { it.copy(result = null) }
        val result = block()
        activity.update { it.copy(result = result) }
    }

    private fun working(block: suspend () -> Unit) {
        viewModelScope.launch {
            activity.update { it.copy(working = true) }
            try { block() } finally { activity.update { it.copy(working = false) } }
        }
    }

    private data class Activity(
        val working: Boolean = false,
        val result: AccessResult? = null,
        val pairing: PairingResult? = null,
        val shizuku: ShizukuPermissionResult? = null,
        val check: ConnectionCheckState = ConnectionCheckState(),
        val network: NetworkAccess = NetworkAccess.Allowed,
    )
}
