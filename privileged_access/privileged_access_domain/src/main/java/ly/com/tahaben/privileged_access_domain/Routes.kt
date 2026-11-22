package ly.com.tahaben.privileged_access_domain

/** The two ways to reach a shell that can run a [PrivilegedCommand]. */
enum class Route { SELF_ADB, SHIZUKU }

/** Why a route cannot run a command right now. Each one is something the user can read and act on. */
enum class RouteBlocker {
    /** Self-ADB needs wireless debugging, which starts at Android 11. */
    ANDROID_TOO_OLD,
    /** No retained adb key: Farhan has not been paired, or the pairing was forgotten. */
    NOT_PAIRED,
    /** The phone refused Farhan's key: the pairing was removed in Developer options. The retained key is dead. */
    KEY_REJECTED,
    /** Wireless debugging is off, or its daemon could not be found. The retained key may still be good. */
    WIRELESS_DEBUGGING_OFF,
    /** The daemon answered but the connection broke or timed out. Worth trying again. */
    CONNECTION_FAILED,
    /** The operating system refuses Farhan any socket: its network access is off, for example a per-app Network toggle. */
    NETWORK_DENIED,
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_TOO_OLD,
    /** Shizuku is running and Farhan has not asked yet. One in-app prompt fixes it. */
    SHIZUKU_PERMISSION_NEEDED,
    SHIZUKU_PERMISSION_DENIED,
    /** Shizuku granted the permission but its service did not start in time. */
    SHIZUKU_SERVICE_FAILED;

    /** True when the user can fix it without leaving Farhan's own screens. */
    val askInApp: Boolean get() = this == SHIZUKU_PERMISSION_NEEDED
}

sealed interface RouteReadiness {
    data object Ready : RouteReadiness
    data class Blocked(val blocker: RouteBlocker) : RouteReadiness
}

sealed interface ShellResult {
    /** The command ran. [output] is its output; it is shown to the user on failure, so it is cleaned by [OutputText]. */
    data class Ran(val exitCode: Int, val output: String) : ShellResult

    /** The command did not run. Nothing changed on the phone. [at] says where a self-ADB connection broke, when it did. */
    data class Blocked(val blocker: RouteBlocker, val at: StepFailure? = null) : ShellResult
}

/**
 * A back end that runs one [ShellCommand] with shell rights. It cannot run anything else: the type has no way to
 * carry free text.
 */
interface PrivilegedShell {
    val route: Route

    /** Looks at stored state and system settings only. It opens no connection and runs nothing. */
    suspend fun readiness(): RouteReadiness

    suspend fun run(command: ShellCommand): ShellResult
}

/** A one-time pairing code. [toString] hides it, and nothing in the connector stores it. */
@JvmInline
value class PairingCode private constructor(private val digits: String) {
    fun reveal(): String = digits
    override fun toString() = "PairingCode(******)"

    companion object {
        private val SHAPE = Regex("[0-9]{6}")
        fun parse(raw: String): PairingCode? = raw.trim().takeIf { SHAPE.matches(it) }?.let(::PairingCode)
    }
}

sealed interface PairingResult {
    data object Paired : PairingResult
    data object AndroidTooOld : PairingResult
    /** The pairing service was not found: the user has not opened "Pair device with pairing code". */
    data object PairingServiceNotFound : PairingResult
    /** Wrong or expired code, or the dialog was closed. */
    data object Rejected : PairingResult
    data object Failed : PairingResult

    /** A step of the pairing threw. [at] names the step and the exception class; see [StepFailure]. */
    data class StepFailed(val at: StepFailure) : PairingResult

    /** Farhan's network access is off, so no pairing was tried. [at] is the refusal, when a socket call gave one. */
    data class NetworkDenied(val at: StepFailure?) : PairingResult
}

sealed interface ShizukuPermissionResult {
    data object Granted : ShizukuPermissionResult
    data class Blocked(val blocker: RouteBlocker) : ShizukuPermissionResult
}

/** Whether Farhan may open sockets at all. Asked before any pairing screen time is spent. */
sealed interface NetworkAccess {
    data object Allowed : NetworkAccess

    /** [permissionDenied]: `checkSelfPermission(INTERNET)` said no. [at]: a socket call failed with EPERM. */
    data class Denied(val permissionDenied: Boolean, val at: StepFailure?) : NetworkAccess
}

/** Self-ADB: the phone's own wireless-debugging daemon, reached over loopback with a key Farhan keeps. */
interface SelfAdbRoute : PrivilegedShell {
    /**
     * Pairs once. [pairingPort] null means find it through service discovery. On success the key is retained and the
     * code is gone: it is never stored.
     */
    suspend fun pair(code: PairingCode, pairingPort: Int?): PairingResult

    /** Deletes the retained key. The phone's paired-devices entry stays until the user removes it. */
    fun forgetPairing()

    /**
     * The connection check: report lines about the phone's wireless debugging, who answers on which port, and how a TLS
     * handshake to the pairing port goes. It needs no code, changes nothing and stops before the code could be used.
     */
    suspend fun checkConnection(typedPort: Int?): List<String> = emptyList()

    /** Whether the operating system lets Farhan open sockets. Opens only a loopback socket pair of its own. */
    suspend fun networkAccess(): NetworkAccess = NetworkAccess.Allowed

    /** The in-memory connection log, oldest line first: steps, outcomes, exception text. Never a code or a key. */
    fun connectionLogText(): String = ""
}

/** Shizuku: its running service hands out a shell process after the user allows Farhan in Shizuku. */
interface ShizukuRoute : PrivilegedShell {
    suspend fun requestPermission(): ShizukuPermissionResult
}

/** Where the link stands. [Connected] means a route could run a command now, without any pairing flow. */
sealed interface LinkState {
    data object None : LinkState
    data class Connected(val route: Route) : LinkState
}
