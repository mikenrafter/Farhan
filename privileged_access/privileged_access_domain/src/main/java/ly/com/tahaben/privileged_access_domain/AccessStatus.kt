package ly.com.tahaben.privileged_access_domain

/** What Android says about an app-op, reduced to what the connector needs. */
enum class AppOpMode {
    ALLOWED, NOT_ALLOWED,
    /** The platform could not tell (unknown op name on this build). Granting is harmless, so it is treated as not allowed. */
    UNKNOWN;

    companion object {
        /** `AppOpsManager.MODE_ALLOWED` is 0; ignored, errored, default and foreground are all "not allowed" for a listener. */
        fun fromPlatformMode(mode: Int?): AppOpMode = when (mode) {
            null -> UNKNOWN
            0 -> ALLOWED
            else -> NOT_ALLOWED
        }
    }
}

/** Facts read from the phone, with no shell and no side effect. */
data class AccessFacts(
    val sdk: Int,
    val sensitiveNotificationsOp: AppOpMode,
    /** `PackageManager.checkPermission(WRITE_SECURE_SETTINGS)`. */
    val secureSettingsPermissionHeld: Boolean,
    /** Whether a write actually went through; null when it was not tried (it is tried only after a grant). */
    val secureSettingsWriteWorked: Boolean? = null,
)

enum class AccessState {
    ALLOWED,
    NOT_ALLOWED,
    /** The phone has nothing to grant (the app-op exists from Android 15). */
    NOT_APPLICABLE,
}

/** What the UI shows: where the link stands and, per access, whether it is allowed. All of it comes from [AccessFacts] and [RouteReadiness]. */
data class PrivilegedStatus(
    val checked: Boolean,
    val link: LinkState,
    val accesses: Map<Access, AccessState>,
    val routes: Map<Route, RouteReadiness>,
) {
    fun stateOf(access: Access): AccessState? = accesses[access]

    /** Not allowed and no route can run the grant: the user has to pair or start Shizuku first. */
    fun needsSetup(access: Access): Boolean = accesses[access] == AccessState.NOT_ALLOWED && link == LinkState.None &&
        routes.values.none { it is RouteReadiness.Ready || (it as? RouteReadiness.Blocked)?.blocker?.askInApp == true }

    /** True when the allowed accesses can be revoked without a new pairing. */
    val canRevoke: Boolean get() = link != LinkState.None

    companion object {
        val UNCHECKED = PrivilegedStatus(checked = false, link = LinkState.None, accesses = emptyMap(), routes = emptyMap())
    }
}

object AccessDecider {
    /** First Android with RECEIVE_SENSITIVE_NOTIFICATIONS. */
    const val SENSITIVE_NOTIFICATIONS_SINCE_SDK = 35

    fun decide(access: Access, facts: AccessFacts): AccessState = when (access) {
        Access.SENSITIVE_NOTIFICATIONS -> when {
            facts.sdk < SENSITIVE_NOTIFICATIONS_SINCE_SDK -> AccessState.NOT_APPLICABLE
            facts.sensitiveNotificationsOp == AppOpMode.ALLOWED -> AccessState.ALLOWED
            else -> AccessState.NOT_ALLOWED
        }
        // The permission is what Android checks for every write, so holding it is enough unless a write was tried and failed.
        Access.SECURE_SETTINGS ->
            if (facts.secureSettingsPermissionHeld && facts.secureSettingsWriteWorked != false) AccessState.ALLOWED else AccessState.NOT_ALLOWED
    }

    fun decideAll(facts: AccessFacts): Map<Access, AccessState> = Access.entries.associateWith { decide(it, facts) }

    /**
     * The link is the route that could run a command now. Under a fixed [RoutePreference] only that route counts;
     * under ASK, Self-ADB is looked at first because it needs no other app.
     */
    fun linkOf(preference: RoutePreference, readiness: Map<Route, RouteReadiness>): LinkState {
        val candidates = when (preference) {
            RoutePreference.ASK -> listOf(Route.SELF_ADB, Route.SHIZUKU)
            RoutePreference.SELF_ADB -> listOf(Route.SELF_ADB)
            RoutePreference.SHIZUKU -> listOf(Route.SHIZUKU)
        }
        val ready = candidates.firstOrNull { readiness[it] == RouteReadiness.Ready } ?: return LinkState.None
        return LinkState.Connected(ready)
    }

    fun statusOf(facts: AccessFacts, preference: RoutePreference, readiness: Map<Route, RouteReadiness>) = PrivilegedStatus(
        checked = true,
        link = linkOf(preference, readiness),
        accesses = decideAll(facts),
        routes = readiness,
    )

    /** True when neither access is allowed, so a retained key has no further use. */
    fun nothingAllowed(accesses: Map<Access, AccessState>): Boolean =
        accesses.isNotEmpty() && accesses.values.none { it == AccessState.ALLOWED }
}
