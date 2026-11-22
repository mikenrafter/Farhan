package ly.com.tahaben.farhan.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ly.com.tahaben.core.R
import ly.com.tahaben.privileged_access_presentation.FailureKind
import ly.com.tahaben.privileged_access_presentation.FailureView
import ly.com.tahaben.privileged_access_presentation.isPairing
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_presentation.GroupNote
import ly.com.tahaben.privileged_access_presentation.ToggleMessage

/** One string per failure kind. The mapping lives here so the model stays free of Android. */
@StringRes
internal fun FailureKind.messageRes(): Int = when (this) {
    FailureKind.NOT_PAIRED -> R.string.special_access_fail_not_paired
    FailureKind.KEY_REJECTED -> R.string.special_access_fail_key_rejected
    FailureKind.WIRELESS_DEBUGGING_OFF -> R.string.special_access_fail_wireless_off
    FailureKind.ANDROID_TOO_OLD -> R.string.special_access_fail_android_too_old
    FailureKind.CONNECTION_FAILED -> R.string.special_access_fail_connection
    FailureKind.SHIZUKU_NOT_RUNNING -> R.string.special_access_fail_shizuku_not_running
    FailureKind.SHIZUKU_NOT_INSTALLED -> R.string.special_access_fail_shizuku_not_installed
    FailureKind.SHIZUKU_TOO_OLD -> R.string.special_access_fail_shizuku_too_old
    FailureKind.SHIZUKU_PERMISSION_NEEDED -> R.string.special_access_fail_shizuku_permission_needed
    FailureKind.SHIZUKU_PERMISSION_DENIED -> R.string.special_access_fail_shizuku_permission_denied
    FailureKind.SHIZUKU_SERVICE_FAILED -> R.string.special_access_fail_shizuku_service
    FailureKind.COMMAND_FAILED -> R.string.special_access_fail_command
    FailureKind.NOT_EFFECTIVE -> R.string.special_access_fail_not_effective
    FailureKind.BUSY -> R.string.special_access_fail_busy
    FailureKind.INVALID_APP -> R.string.special_access_fail_invalid_app
    FailureKind.PAIRING_REJECTED -> R.string.special_access_fail_pairing_rejected
    FailureKind.PAIRING_SERVICE_NOT_FOUND -> R.string.special_access_fail_pairing_service
    FailureKind.PAIRING_FAILED -> R.string.special_access_fail_pairing_failed
    FailureKind.NETWORK_DENIED -> R.string.special_access_fail_network_denied
    FailureKind.NOT_CONNECTED -> R.string.special_access_fail_not_connected
}

@StringRes
internal fun ToggleMessage.messageRes(): Int = when (this) {
    ToggleMessage.ALLOWED -> R.string.special_access_toast_allowed
    ToggleMessage.REVOKED -> R.string.special_access_toast_revoked
    ToggleMessage.BOTH_OFF_FORGOTTEN -> R.string.special_access_toast_both_off
    ToggleMessage.ONLY_ACCESS_OFF_FORGOTTEN -> R.string.special_access_toast_off
    ToggleMessage.BOTH_OFF_SHIZUKU_STAYS -> R.string.special_access_toast_both_off_shizuku
    ToggleMessage.ONLY_ACCESS_OFF_SHIZUKU_STAYS -> R.string.special_access_toast_off_shizuku
}

@StringRes
internal fun GroupNote.messageRes(): Int = when (this) {
    GroupNote.NO_LINK -> R.string.special_access_note_no_link
    GroupNote.WIRELESS_DEBUGGING -> R.string.special_access_note_wireless
    GroupNote.WIRELESS_DEBUGGING_ONE -> R.string.special_access_note_wireless_one
    GroupNote.SHIZUKU -> R.string.special_access_note_shizuku
}

@StringRes
internal fun SelfAdbStep.labelRes(): Int = when (this) {
    SelfAdbStep.DISCOVERY -> R.string.special_access_step_discovery
    SelfAdbStep.CONNECT_SOCKET -> R.string.special_access_step_connect_socket
    SelfAdbStep.TLS_HANDSHAKE -> R.string.special_access_step_tls_handshake
    SelfAdbStep.FIRST_READ -> R.string.special_access_step_first_read
    SelfAdbStep.EXPORT_KEYING_MATERIAL -> R.string.special_access_step_export_keying_material
    SelfAdbStep.SPAKE2 -> R.string.special_access_step_spake2
    SelfAdbStep.PEER_INFO -> R.string.special_access_step_peer_info
    SelfAdbStep.CONNECT -> R.string.special_access_step_connect
    SelfAdbStep.KEY_SETUP -> R.string.special_access_step_key_setup
}

/** "Pairing failed at: TLS handshake (SSLHandshakeException)": the step and the exception class, never a message. */
@Composable
internal fun FailureView.stepLine(): String? {
    val at = step ?: return null
    val line = if (kind.isPairing()) R.string.special_access_failed_at_pairing else R.string.special_access_failed_at_connecting
    // The message travels only for a network refusal (EPERM), where it is the operating system's own text.
    val what = at.detail?.let { "${at.exceptionClass}: $it" } ?: at.exceptionClass
    return stringResource(line, stringResource(at.step.labelRes()), what)
}
