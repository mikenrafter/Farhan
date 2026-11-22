package ly.com.tahaben.privileged_access_domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The layer the UI calls. Rules it keeps:
 *  - It runs a command only inside [grant] or [revoke], which the user starts. [refresh] reads the phone and stored
 *    state and opens no connection.
 *  - Every command is one of [PrivilegedCommand]; nothing here takes free text.
 *  - The link outlives a grant: a second access runs over the same retained key (or Shizuku permission), with no
 *    pairing flow. A pairing code is used once and never kept.
 */
interface PrivilegedAccess {
    /** Link and per-access state. Starts as [PrivilegedStatus.UNCHECKED] until the first [refresh]. */
    val status: StateFlow<PrivilegedStatus>
    val routePreference: StateFlow<RoutePreference>

    /** Re-reads everything. Call when the app returns to the foreground. */
    suspend fun refresh(): PrivilegedStatus

    suspend fun grant(access: Access, route: Route? = null): AccessResult
    suspend fun revoke(access: Access, route: Route? = null): AccessResult

    suspend fun pairSelfAdb(code: PairingCode, pairingPort: Int?): PairingResult
    suspend fun requestShizukuPermission(): ShizukuPermissionResult

    suspend fun setRoutePreference(preference: RoutePreference)

    /** The user asked to forget the retained adb key. */
    suspend fun forgetSelfAdbKey()

    /** The user asked for the connection check. Runs no command and uses no pairing code; see [SelfAdbRoute.checkConnection]. */
    suspend fun checkSelfAdbConnection(typedPort: Int?): List<String> = emptyList()

    /** The whole connection log, for Copy and Share. */
    fun selfAdbLog(): String = ""

    /** Asked when the pairing page opens, before the user is asked for a code. */
    suspend fun checkNetworkAccess(): NetworkAccess = NetworkAccess.Allowed
}

class DefaultPrivilegedAccess(
    appId: String,
    private val phone: PhoneFacts,
    private val selfAdb: SelfAdbRoute,
    private val shizuku: ShizukuRoute,
    private val preferences: PrivilegedPreferences,
    private val rebinder: ListenerRebinder,
    private val redaction: OtpRedactionFlag,
    private val grayscale: GrayscaleReset,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PrivilegedAccess {
    private val app: AppId? = AppId.parse(appId)
    private val statusFlow = MutableStateFlow(PrivilegedStatus.UNCHECKED)
    private val preferenceFlow = MutableStateFlow(preferences.route)
    private val busy = Mutex()

    /** Self-ADB looked usable on paper but the daemon was not there. In memory: a restart starts clean. */
    private val lostRoutes = ConcurrentHashMap<Route, RouteBlocker>()
    @Volatile private var lastSelfAdbReadiness: RouteReadiness? = null

    override val status: StateFlow<PrivilegedStatus> = statusFlow.asStateFlow()
    override val routePreference: StateFlow<RoutePreference> = preferenceFlow.asStateFlow()

    override suspend fun refresh(): PrivilegedStatus = withContext(io) { publish(settlePending = !busy.isLocked) }

    override suspend fun grant(access: Access, route: Route?) = execute(PrivilegedCommand.of(access, grants = true), route)

    override suspend fun revoke(access: Access, route: Route?) = execute(PrivilegedCommand.of(access, grants = false), route)

    override suspend fun pairSelfAdb(code: PairingCode, pairingPort: Int?): PairingResult = withContext(io) {
        if (pairingPort != null && pairingPort !in 1..65535) return@withContext PairingResult.Failed
        if (!busy.tryLock()) return@withContext PairingResult.Failed
        try {
            selfAdb.pair(code, pairingPort).also { result ->
                if (result == PairingResult.Paired) lostRoutes.remove(Route.SELF_ADB)
                publish(settlePending = false)
            }
        } finally {
            busy.unlock()
        }
    }

    override suspend fun checkSelfAdbConnection(typedPort: Int?): List<String> = withContext(io) {
        if (!busy.tryLock()) return@withContext listOf("Another change is still running. Wait a moment, then try again.")
        try { selfAdb.checkConnection(typedPort) } finally { busy.unlock() }
    }

    override fun selfAdbLog(): String = selfAdb.connectionLogText()

    override suspend fun checkNetworkAccess(): NetworkAccess = withContext(io) { selfAdb.networkAccess() }

    override suspend fun requestShizukuPermission(): ShizukuPermissionResult =
        shizuku.requestPermission().also { withContext(io) { publish(settlePending = !busy.isLocked) } }

    override suspend fun setRoutePreference(preference: RoutePreference) {
        withContext(io) {
            preferences.route = preference
            preferenceFlow.value = preference
            publish(settlePending = !busy.isLocked)
        }
    }

    override suspend fun forgetSelfAdbKey() {
        withContext(io) {
            selfAdb.forgetPairing()
            lostRoutes.remove(Route.SELF_ADB)
            publish(settlePending = !busy.isLocked)
        }
    }

    private suspend fun execute(command: PrivilegedCommand, requested: Route?): AccessResult = withContext(io) {
        val appId = app ?: return@withContext AccessResult.Failed(AccessFailure.InvalidAppId)
        if (!busy.tryLock()) return@withContext AccessResult.Failed(AccessFailure.Busy)
        try {
            val before = publish(settlePending = false)
            val route = when (val choice = RouteChooser.choose(requested, preferenceFlow.value, before.link)) {
                is RouteChoice.AskUser -> return@withContext AccessResult.ChooseRoute(choice.options)
                is RouteChoice.Use -> choice.route
            }
            val otherAllowed = before.accesses.any { (access, state) -> access != command.access && state == AccessState.ALLOWED }
            if (!command.grants) {
                if (command.access == Access.SECURE_SETTINGS) grayscale.resetBeforeRevoke()
                if (!otherAllowed) preferences.linkDropPending = true
            }
            val shell = if (route == Route.SELF_ADB) selfAdb else shizuku
            val result = when (val ran = shell.run(command.shellCommand(appId))) {
                is ShellResult.Blocked -> {
                    onBlocked(route, ran.blocker)
                    AccessResult.Failed(AccessMapper.failureOf(route, ran.blocker, ran.at))
                }
                is ShellResult.Ran -> {
                    lostRoutes.remove(route)
                    if (ran.exitCode != 0) AccessResult.Failed(AccessMapper.commandFailure(route, ran)) else verified(command, route)
                }
            }
            if (result is AccessResult.Done && command.grants && command.access == Access.SENSITIVE_NOTIFICATIONS) {
                rebinder.rebind()
                redaction.clear()
            }
            publish(settlePending = true)
            result
        } finally {
            busy.unlock()
        }
    }

    private fun onBlocked(route: Route, blocker: RouteBlocker) {
        when {
            // The phone removed our key: it is useless, so the link is cleared and the next grant needs pairing.
            blocker == RouteBlocker.KEY_REJECTED -> selfAdb.forgetPairing()
            route == Route.SELF_ADB && blocker == RouteBlocker.WIRELESS_DEBUGGING_OFF -> lostRoutes[route] = blocker
        }
    }

    /** Compares the phone with what the command should have done. A clean exit with no change is [AccessFailure.NotEffective]. */
    private fun verified(command: PrivilegedCommand, route: Route): AccessResult {
        val done = { verified: Boolean -> AccessResult.Done(command, route, verified) }
        val notEffective = AccessResult.Failed(AccessFailure.NotEffective(route))
        return when (command) {
            PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS -> when (phone.sensitiveNotificationsOp()) {
                AppOpMode.ALLOWED -> done(true)
                AppOpMode.UNKNOWN -> done(false)
                AppOpMode.NOT_ALLOWED -> notEffective
            }
            PrivilegedCommand.REVOKE_SENSITIVE_NOTIFICATIONS -> when (phone.sensitiveNotificationsOp()) {
                AppOpMode.ALLOWED -> notEffective
                AppOpMode.NOT_ALLOWED -> done(true)
                AppOpMode.UNKNOWN -> done(false)
            }
            PrivilegedCommand.GRANT_SECURE_SETTINGS ->
                if (!phone.secureSettingsPermissionHeld() || phone.secureSettingsWriteWorks() == false) notEffective else done(true)
            PrivilegedCommand.REVOKE_SECURE_SETTINGS ->
                if (phone.secureSettingsPermissionHeld()) notEffective else done(true)
        }
    }

    private suspend fun readReadiness(): Map<Route, RouteReadiness> {
        val own = selfAdb.readiness()
        // Wireless debugging switched back on clears the "daemon not found" mark; otherwise it stays until an attempt reaches the daemon or the user pairs again.
        if (own == RouteReadiness.Ready && lastSelfAdbReadiness == RouteReadiness.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)) lostRoutes.remove(Route.SELF_ADB)
        lastSelfAdbReadiness = own
        val selfAdbReadiness = if (own == RouteReadiness.Ready) lostRoutes[Route.SELF_ADB]?.let { RouteReadiness.Blocked(it) } ?: own else own
        return mapOf(Route.SELF_ADB to selfAdbReadiness, Route.SHIZUKU to shizuku.readiness())
    }

    /**
     * Reads the phone, finishes an interrupted revoke when [settlePending], and publishes the result. A revoke that
     * left nothing allowed drops the retained adb key; one that did not take just clears the marker.
     */
    private suspend fun publish(settlePending: Boolean): PrivilegedStatus {
        var status = compute()
        if (settlePending && preferences.linkDropPending) {
            if (AccessDecider.nothingAllowed(status.accesses)) {
                selfAdb.forgetPairing()
                lostRoutes.remove(Route.SELF_ADB)
            }
            preferences.linkDropPending = false
            status = compute()
        }
        statusFlow.value = status
        return status
    }

    private suspend fun compute(): PrivilegedStatus {
        val facts = AccessFacts(
            sdk = phone.sdk,
            sensitiveNotificationsOp = phone.sensitiveNotificationsOp(),
            secureSettingsPermissionHeld = phone.secureSettingsPermissionHeld(),
        )
        return AccessDecider.statusOf(facts, preferenceFlow.value, readReadiness())
    }
}
