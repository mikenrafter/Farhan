package ly.com.tahaben.privileged_access_presentation

import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessDecider
import ly.com.tahaben.privileged_access_domain.AccessFailure
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.AccessState
import ly.com.tahaben.privileged_access_domain.LinkLostReason
import ly.com.tahaben.privileged_access_domain.LinkState
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.RoutePreference
import ly.com.tahaben.privileged_access_domain.RouteReadiness

/*
 * What the Special access screens show, worked out from the connector's state. Nothing here touches Android or Compose:
 * the screens map each value to a string and a control.
 */

/** What sits at the right of an access row. */
enum class AccessControl {
    /** Android has nothing to grant (sensitive notifications before Android 15): no row. */
    HIDDEN,

    /** The first check has not finished: the row shows without a control. */
    PENDING,

    /** Not allowed and no link: the button opens the flow. */
    ALLOW_BUTTON,

    /** On means allowed. The switch is shown when a link is retained, or when the access is already allowed. */
    SWITCH_ON,
    SWITCH_OFF,
}

fun accessControl(status: PrivilegedStatus, access: Access): AccessControl = when (status.stateOf(access)) {
    null -> AccessControl.PENDING
    AccessState.NOT_APPLICABLE -> AccessControl.HIDDEN
    AccessState.ALLOWED -> AccessControl.SWITCH_ON
    AccessState.NOT_ALLOWED -> if (status.link is LinkState.Connected) AccessControl.SWITCH_OFF else AccessControl.ALLOW_BUTTON
}

/** The route of the retained link, or null when there is none. */
fun linkRoute(status: PrivilegedStatus): Route? = (status.link as? LinkState.Connected)?.route

/** Which of the group's notes to show. [WIRELESS_DEBUGGING_ONE] is for a phone with one access to switch (before Android 15). */
enum class GroupNote { NO_LINK, WIRELESS_DEBUGGING, WIRELESS_DEBUGGING_ONE, SHIZUKU }

fun groupNote(status: PrivilegedStatus): GroupNote = when (linkRoute(status)) {
    null -> GroupNote.NO_LINK
    Route.SELF_ADB ->
        if (status.accesses.values.any { it == AccessState.NOT_APPLICABLE }) GroupNote.WIRELESS_DEBUGGING_ONE else GroupNote.WIRELESS_DEBUGGING
    Route.SHIZUKU -> GroupNote.SHIZUKU
}

/** True when the red note under Verification codes should offer to fix the hidden codes. */
fun sensitiveNeedsAllowing(status: PrivilegedStatus): Boolean = status.stateOf(Access.SENSITIVE_NOTIFICATIONS) == AccessState.NOT_ALLOWED

/** The route the flow starts on: the retained link's, then a stored Shizuku preference, then wireless debugging. */
fun defaultFlowRoute(status: PrivilegedStatus, preference: RoutePreference): Route =
    linkRoute(status) ?: if (preference == RoutePreference.SHIZUKU) Route.SHIZUKU else Route.SELF_ADB

enum class ShizukuStatus { RUNNING, NOT_RUNNING, NOT_INSTALLED, TOO_OLD }

fun shizukuStatus(status: PrivilegedStatus): ShizukuStatus =
    when ((status.routes[Route.SHIZUKU] as? RouteReadiness.Blocked)?.blocker) {
        RouteBlocker.SHIZUKU_NOT_RUNNING -> ShizukuStatus.NOT_RUNNING
        RouteBlocker.SHIZUKU_NOT_INSTALLED -> ShizukuStatus.NOT_INSTALLED
        RouteBlocker.SHIZUKU_TOO_OLD -> ShizukuStatus.TOO_OLD
        else -> ShizukuStatus.RUNNING
    }

/** Every reason the connector can give for not finishing, as one short plain message each. [COMMAND_FAILED] also carries the shell's output. */
enum class FailureKind {
    NOT_PAIRED, KEY_REJECTED, WIRELESS_DEBUGGING_OFF, ANDROID_TOO_OLD, CONNECTION_FAILED,
    SHIZUKU_NOT_RUNNING, SHIZUKU_NOT_INSTALLED, SHIZUKU_TOO_OLD,
    SHIZUKU_PERMISSION_NEEDED, SHIZUKU_PERMISSION_DENIED, SHIZUKU_SERVICE_FAILED,
    COMMAND_FAILED, NOT_EFFECTIVE, BUSY, INVALID_APP,
    PAIRING_REJECTED, PAIRING_SERVICE_NOT_FOUND, PAIRING_FAILED,

    /** The operating system refuses Farhan any socket. Nothing was tried; the user turns the network setting on. */
    NETWORK_DENIED,

    /** There is no retained link and no stored route, so nothing could run. */
    NOT_CONNECTED,
}

/** [step] is where a self-ADB attempt broke, shown as one extra line under the message. */
data class FailureView(val kind: FailureKind, val detail: String? = null, val step: StepFailure? = null)

fun failureKindOf(blocker: RouteBlocker): FailureKind = when (blocker) {
    RouteBlocker.ANDROID_TOO_OLD -> FailureKind.ANDROID_TOO_OLD
    RouteBlocker.NOT_PAIRED -> FailureKind.NOT_PAIRED
    RouteBlocker.KEY_REJECTED -> FailureKind.KEY_REJECTED
    RouteBlocker.WIRELESS_DEBUGGING_OFF -> FailureKind.WIRELESS_DEBUGGING_OFF
    RouteBlocker.CONNECTION_FAILED -> FailureKind.CONNECTION_FAILED
    RouteBlocker.NETWORK_DENIED -> FailureKind.NETWORK_DENIED
    RouteBlocker.SHIZUKU_NOT_INSTALLED -> FailureKind.SHIZUKU_NOT_INSTALLED
    RouteBlocker.SHIZUKU_NOT_RUNNING -> FailureKind.SHIZUKU_NOT_RUNNING
    RouteBlocker.SHIZUKU_TOO_OLD -> FailureKind.SHIZUKU_TOO_OLD
    RouteBlocker.SHIZUKU_PERMISSION_NEEDED -> FailureKind.SHIZUKU_PERMISSION_NEEDED
    RouteBlocker.SHIZUKU_PERMISSION_DENIED -> FailureKind.SHIZUKU_PERMISSION_DENIED
    RouteBlocker.SHIZUKU_SERVICE_FAILED -> FailureKind.SHIZUKU_SERVICE_FAILED
}

fun failureViewOf(failure: AccessFailure): FailureView = when (failure) {
    is AccessFailure.LinkLost -> FailureView(
        when (failure.reason) {
            LinkLostReason.NOT_PAIRED -> FailureKind.NOT_PAIRED
            LinkLostReason.KEY_REJECTED -> FailureKind.KEY_REJECTED
            LinkLostReason.WIRELESS_DEBUGGING_OFF -> FailureKind.WIRELESS_DEBUGGING_OFF
        },
        step = failure.at,
    )
    AccessFailure.ShizukuNotRunning -> FailureView(FailureKind.SHIZUKU_NOT_RUNNING)
    is AccessFailure.RouteBlocked -> FailureView(failureKindOf(failure.blocker), step = failure.at)
    is AccessFailure.CommandFailed -> FailureView(FailureKind.COMMAND_FAILED, failure.output.ifBlank { null })
    is AccessFailure.NotEffective -> FailureView(FailureKind.NOT_EFFECTIVE)
    AccessFailure.Busy -> FailureView(FailureKind.BUSY)
    AccessFailure.InvalidAppId -> FailureView(FailureKind.INVALID_APP)
}

/** Null for [PairingResult.Paired]: it is not a failure. */
fun failureViewOf(pairing: PairingResult): FailureView? = when (pairing) {
    PairingResult.Paired -> null
    PairingResult.AndroidTooOld -> FailureView(FailureKind.ANDROID_TOO_OLD)
    PairingResult.PairingServiceNotFound -> FailureView(FailureKind.PAIRING_SERVICE_NOT_FOUND)
    PairingResult.Rejected -> FailureView(FailureKind.PAIRING_REJECTED)
    PairingResult.Failed -> FailureView(FailureKind.PAIRING_FAILED)
    is PairingResult.NetworkDenied -> FailureView(FailureKind.NETWORK_DENIED, step = pairing.at)
    // Refused at the port or in the last two steps reads as a wrong code, a wrong port or a closed dialog; the steps
    // before the code is used (TLS, key export) are the app's or the phone's, not the user's.
    is PairingResult.StepFailed -> FailureView(
        if (pairing.at.step in CODE_SIDE_STEPS) FailureKind.PAIRING_REJECTED else FailureKind.PAIRING_FAILED,
        step = pairing.at,
    )
}

private val CODE_SIDE_STEPS = setOf(SelfAdbStep.CONNECT_SOCKET, SelfAdbStep.SPAKE2, SelfAdbStep.PEER_INFO)

/** True for the kinds whose step line says "Pairing"; every other kind is a connection. */
fun FailureKind.isPairing(): Boolean = this == FailureKind.PAIRING_REJECTED || this == FailureKind.PAIRING_FAILED || this == FailureKind.PAIRING_SERVICE_NOT_FOUND

/** What to say after a switch or the red note's Allow ran a command. */
enum class ToggleMessage {
    ALLOWED, REVOKED,

    /** The last allowed access went off over wireless debugging, so the core dropped the key. */
    BOTH_OFF_FORGOTTEN, ONLY_ACCESS_OFF_FORGOTTEN,

    /** The last allowed access went off over Shizuku. The core cannot remove Shizuku's own grant. */
    BOTH_OFF_SHIZUKU_STAYS, ONLY_ACCESS_OFF_SHIZUKU_STAYS,
}

sealed interface ToggleOutcome {
    data class Say(val message: ToggleMessage) : ToggleOutcome

    /** The link is gone: open the flow with [failure] shown. [route] is the way back in, or null to keep the current choice. */
    data class OpenFlow(val failure: FailureView, val route: Route?) : ToggleOutcome

    /** Anything else that went wrong: a short message, no flow. */
    data class Warn(val failure: FailureView) : ToggleOutcome
}

/** [statusAfter] is the status after the command, which is where "nothing left allowed" shows. */
fun toggleOutcome(result: AccessResult, statusAfter: PrivilegedStatus): ToggleOutcome = when (result) {
    is AccessResult.Done -> ToggleOutcome.Say(doneMessage(result, statusAfter))
    is AccessResult.ChooseRoute -> ToggleOutcome.OpenFlow(FailureView(FailureKind.NOT_CONNECTED), route = null)
    is AccessResult.Failed -> {
        val failure = result.failure
        when {
            failure is AccessFailure.LinkLost -> ToggleOutcome.OpenFlow(failureViewOf(failure), Route.SELF_ADB)
            // The message needs its button, so it opens the flow page rather than a short note.
            failure is AccessFailure.RouteBlocked && failure.blocker == RouteBlocker.NETWORK_DENIED ->
                ToggleOutcome.OpenFlow(failureViewOf(failure), Route.SELF_ADB)
            else -> ToggleOutcome.Warn(failureViewOf(failure))
        }
    }
}

private fun doneMessage(done: AccessResult.Done, statusAfter: PrivilegedStatus): ToggleMessage {
    if (done.command.grants) return ToggleMessage.ALLOWED
    if (!AccessDecider.nothingAllowed(statusAfter.accesses)) return ToggleMessage.REVOKED
    val both = statusAfter.accesses.values.none { it == AccessState.NOT_APPLICABLE }
    return when {
        done.route == Route.SHIZUKU && both -> ToggleMessage.BOTH_OFF_SHIZUKU_STAYS
        done.route == Route.SHIZUKU -> ToggleMessage.ONLY_ACCESS_OFF_SHIZUKU_STAYS
        both -> ToggleMessage.BOTH_OFF_FORGOTTEN
        else -> ToggleMessage.ONLY_ACCESS_OFF_FORGOTTEN
    }
}


/** The connection check on the flow page: whether it is running and the lines of the last run. */
data class ConnectionCheckState(val running: Boolean = false, val lines: List<String> = emptyList())
