package ly.com.tahaben.notification_filter_presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent
import ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import ly.com.tahaben.notification_filter_domain.schedule.AutomationReceipt
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleMode
import java.text.DateFormat
import java.util.Date

/**
 * Schedule automation (opt-in, off by default) and the two notification experiments. The credential is shown only
 * on request and copied with the clipboard's sensitive flag; it is never logged.
 */
@Composable
fun AutomationAndExperimentsSection(
    state: NotificationFilterSettingsState,
    onEvent: (NotificationSettingsEvent) -> Unit,
    credentialForCopy: () -> String?,
) {
    val context = LocalContext.current
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schedule automation", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lets another app, such as Tasker, replace your notification schedule when you send a broadcast with your credential. Off by default. Anything you send follows the same rules as the editor, and the last change wins.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Allow automation")
            Switch(checked = state.automationEnabled, onCheckedChange = { onEvent(NotificationSettingsEvent.SetAutomationEnabled(it)) })
        }
        if (state.hasAutomationCredential) {
            Text(state.revealedCredential ?: "Credential hidden", style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onEvent(if (state.revealedCredential == null) NotificationSettingsEvent.ShowAutomationCredential
                        else NotificationSettingsEvent.HideAutomationCredential)
                }) { Text(if (state.revealedCredential == null) "Show" else "Hide") }
                OutlinedButton(onClick = { credentialForCopy()?.let { copySensitive(context, it) } }) { Text("Copy") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmRotate = true }) { Text("Rotate") }
                OutlinedButton(onClick = { confirmRevoke = true }) { Text("Revoke") }
            }
        }
        state.automationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(describeReceipt(state.lastAutomationReceipt), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onEvent(NotificationSettingsEvent.RefreshAutomation) }) { Text("Refresh last result") }
        Text(
            "Send action ly.com.tahaben.notification_filter.action.SET_SCHEDULE to package ${context.packageName}, with string extras credential and payload. The payload is JSON: {\"version\":1,\"windows\":[{\"start\":0,\"end\":420,\"budget\":0},{\"start\":420,\"end\":1440,\"budget\":12}]}. Add \"mode\":\"dated\" and \"date\":\"2026-11-20\" for one date. Setup steps and every error are in notification_filter/AUTOMATION.md.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Experiments", style = MaterialTheme.typography.titleMedium)
        ExperimentRow(
            "Show the original app's icon",
            "Delivered notifications still come from Farhan, with Farhan's name and actions. When the original app gave a usable small icon, Farhan shows it; otherwise it shows its own. Off by default.",
            state.sourceIconExperiment,
        ) { onEvent(NotificationSettingsEvent.SetSourceIconExperiment(it)) }
        ExperimentRow(
            "Timed snooze demo",
            "Only for Farhan's own test apps (messenger, storm, social, calendar). Android hides each test notification for one minute and shows it again. This is outside your budget: nothing is counted or limited, and Farhan cannot cancel or bring back a snoozed notification. Real apps are never snoozed. Off by default.",
            state.snoozeDemoExperiment,
        ) { onEvent(NotificationSettingsEvent.SetSnoozeDemoExperiment(it)) }
        if (state.snoozeDemoEvents.isNotEmpty()) {
            Text("Recent snooze callbacks (newest first, no text kept)", style = MaterialTheme.typography.labelMedium)
            state.snoozeDemoEvents.take(8).forEach { Text(describeExperimentEvent(it), style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmRotate) AlertDialog(
        onDismissRequest = { confirmRotate = false },
        title = { Text("Rotate the credential?") },
        text = { Text("Automation using the old credential stops working until you give it the new one.") },
        confirmButton = { TextButton(onClick = { confirmRotate = false; onEvent(NotificationSettingsEvent.RotateAutomationCredential) }) { Text("Rotate") } },
        dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
    )
    if (confirmRevoke) AlertDialog(
        onDismissRequest = { confirmRevoke = false },
        title = { Text("Revoke the credential?") },
        text = { Text("Automation is turned off and the credential is deleted. Turn it on again to get a new one.") },
        confirmButton = { TextButton(onClick = { confirmRevoke = false; onEvent(NotificationSettingsEvent.RevokeAutomationCredential) }) { Text("Revoke") } },
        dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ExperimentRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Copies with the sensitive flag so the keyboard and clipboard preview do not show it. */
private fun copySensitive(context: android.content.Context, credential: String) {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Farhan automation credential", credential)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    manager.setPrimaryClip(clip)
}

internal fun describeReceipt(receipt: AutomationReceipt?): String {
    if (receipt == null) return "No automation request received yet."
    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(receipt.atEpochMillis))
    val scope = when (receipt.mode) { ScheduleMode.REPEATING -> " (every day)"; ScheduleMode.DATED -> " (one date)"; null -> "" }
    return if (receipt.error == null) "Last request: accepted$scope, $whenText." else "Last request: rejected, ${describeError(receipt.error!!)}, $whenText."
}

internal fun describeError(error: AutomationError): String = when (error) {
    AutomationError.DISABLED -> "automation was off"
    AutomationError.BAD_CREDENTIAL -> "wrong credential"
    AutomationError.UNSUPPORTED_VERSION -> "version must be 1"
    AutomationError.UNKNOWN_MODE -> "mode must be repeating or dated"
    AutomationError.DATE_REQUIRED -> "dated mode needs a date"
    AutomationError.MALFORMED_DATE -> "date must look like 2026-11-20"
    AutomationError.MALFORMED_WINDOW -> "a window is missing a whole-number start, end or budget"
    AutomationError.INVALID_SCHEDULE -> "the windows must cover 00:00 to 24:00 with no gaps and at least an hour each"
    AutomationError.DATE_NOT_ALLOWED -> "an every-day schedule cannot have a date"
    AutomationError.PAYLOAD_TOO_LARGE -> "payload too large"
    AutomationError.MALFORMED_PAYLOAD -> "payload is not the documented JSON"
    AutomationError.STORAGE_FAILED -> "saving failed, nothing changed"
    AutomationError.BUSY -> "too many requests at once, retry"
}

private fun describeExperimentEvent(event: ExperimentEvent): String {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.atEpochMillis))
    return when (event.kind) {
        ExperimentEventKind.SNOOZED -> "$time  snoozed for one minute"
        ExperimentEventKind.RETURNED -> "$time  Android showed it again"
        ExperimentEventKind.FAILED, ExperimentEventKind.RESNOOZED -> ""
        ExperimentEventKind.REMOVED -> "$time  removed, Android reason ${event.removalReason}"
    }
}
