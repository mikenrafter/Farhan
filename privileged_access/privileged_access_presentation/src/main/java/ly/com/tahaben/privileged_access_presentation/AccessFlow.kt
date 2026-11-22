package ly.com.tahaben.privileged_access_presentation

import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult

/** Where the flow page is in its one run. */
enum class FlowPhase {
    IDLE,

    /** Pairing over wireless debugging. A paired result goes straight on to the grant. */
    PAIRING,

    /** Asking Shizuku to allow Farhan. A granted result goes straight on to the grant. */
    SHIZUKU_PERMISSION,

    /** The grant itself is running. */
    GRANTING,
}

sealed interface FlowOutcome {
    /** [verified] is false when the phone could not be read back and the shell's clean exit was trusted. */
    data class Allowed(val route: Route, val verified: Boolean) : FlowOutcome
    data class Failed(val failure: FailureView) : FlowOutcome
}

/**
 * The open flow page for one [access]: which route is picked, how far its one run has got and how it ended. The
 * Settings screen holds it so the red note, a switch and Back can all reach it. Each step is a pure move; the screen
 * makes the matching connector call when [phase] changes.
 */
data class AccessFlow(
    val access: Access,
    val route: Route,
    val phase: FlowPhase = FlowPhase.IDLE,
    val outcome: FlowOutcome? = null,
) {
    val busy: Boolean get() = phase != FlowPhase.IDLE

    /** The way in is picked before the run, not during it. */
    fun withRoute(route: Route): AccessFlow = if (busy || outcome != null) this else copy(route = route)

    /** The button was tapped. Wireless debugging pairs first, Shizuku asks for its permission first. */
    fun start(): AccessFlow = when {
        busy || outcome != null -> this
        route == Route.SELF_ADB -> copy(phase = FlowPhase.PAIRING)
        else -> copy(phase = FlowPhase.SHIZUKU_PERMISSION)
    }

    fun pairingDone(result: PairingResult): AccessFlow {
        if (phase != FlowPhase.PAIRING) return this
        val failure = failureViewOf(result) ?: return copy(phase = FlowPhase.GRANTING)
        return copy(phase = FlowPhase.IDLE, outcome = FlowOutcome.Failed(failure))
    }

    fun shizukuPermissionDone(result: ShizukuPermissionResult): AccessFlow {
        if (phase != FlowPhase.SHIZUKU_PERMISSION) return this
        return when (result) {
            ShizukuPermissionResult.Granted -> copy(phase = FlowPhase.GRANTING)
            is ShizukuPermissionResult.Blocked ->
                copy(phase = FlowPhase.IDLE, outcome = FlowOutcome.Failed(FailureView(failureKindOf(result.blocker))))
        }
    }

    fun accessDone(result: AccessResult): AccessFlow {
        if (phase != FlowPhase.GRANTING) return this
        val outcome = when (result) {
            is AccessResult.Done -> FlowOutcome.Allowed(result.route, result.verified)
            is AccessResult.ChooseRoute -> FlowOutcome.Failed(FailureView(FailureKind.NOT_CONNECTED))
            is AccessResult.Failed -> FlowOutcome.Failed(failureViewOf(result.failure))
        }
        return copy(phase = FlowPhase.IDLE, outcome = outcome)
    }

    /** Try again: back to the form, with the route the user had. */
    fun retry(): AccessFlow = copy(phase = FlowPhase.IDLE, outcome = null)

    companion object {
        /** A flow opened with a failure already shown, as when a switch finds the link gone. */
        fun openedWithFailure(access: Access, route: Route, failure: FailureView) =
            AccessFlow(access, route, outcome = FlowOutcome.Failed(failure))
    }
}

/** The pairing code and port as typed. Keeps only digits, and at most six for the code. */
fun codeInput(raw: String): String = raw.filter { it.isDigit() }.take(6)

fun portInput(raw: String): String = raw.filter { it.isDigit() }.take(5)

/** The button is live for a six-digit code and a port that is blank or in range. */
fun canPair(code: String, port: String): Boolean =
    code.length == 6 && code.all { it.isDigit() } && (port.isEmpty() || port.toIntOrNull() in 1..65535)
