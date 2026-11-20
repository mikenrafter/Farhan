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
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
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
            "Snooze (pilot)",
            "Android hides eligible notifications until the next delivery time for their priority. Your budget decides what happens when they return, and a denied return can beep once. The daily retry limit resets when Farhan restarts. Farhan cannot release or cancel a notification Android is holding, even if you turn snooze off or change the schedule. Android can lose a snoozed notification at reboot. If the app does not post it again, Farhan can deliver its saved copy. Calls, alarms, ongoing notifications, exceptions and critical notifications are never snoozed.",
            state.snoozeExperiment,
        ) { onEvent(NotificationSettingsEvent.SetSnoozeExperiment(it)) }
        if (state.snoozeExperiment || state.snoozeEvents.isNotEmpty()) {
            val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            state.snoozeNext.forEach { (priority, next) ->
                val moment = when {
                    priority in state.snoozeImmediate -> "immediately"
                    next == null -> "no time with budget left in the next 48 hours"
                    else -> dateTime.format(Date.from(next))
                }
                Text("${priority.displayName()} · $moment", style = MaterialTheme.typography.bodySmall)
            }
            val counts = state.snoozeCounts
            Text(
                "${counts[ExperimentEventKind.SNOOZED] ?: 0} snoozed · " +
                    "${counts[ExperimentEventKind.RETURNED] ?: 0} returned · " +
                    "${counts[ExperimentEventKind.RESNOOZED] ?: 0} snoozed again · " +
                    "${counts[ExperimentEventKind.REMOVED] ?: 0} removed · " +
                    "${counts[ExperimentEventKind.FAILED] ?: 0} failed · " +
                    "${counts[ExperimentEventKind.LOST] ?: 0} lost",
                style = MaterialTheme.typography.bodySmall,
            )
            state.snoozeEvents.forEach { event ->
                val label = remember(event.packageName) { appLabel(context, event.packageName) }
                Text(describeExperimentEvent(event, label), style = MaterialTheme.typography.bodySmall)
            }
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
internal fun ExperimentRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

private fun NotificationPriority.displayName(): String = when (this) {
    NotificationPriority.CRITICAL -> "Critical"
    NotificationPriority.HIGH -> "High"
    NotificationPriority.MEDIUM -> "Medium"
    NotificationPriority.LOW -> "Low"
}

private fun appLabel(context: android.content.Context, packageName: String): String = try {
    val manager = context.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    packageName
}

private fun describeExperimentEvent(event: ExperimentEvent, label: String): String {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.atEpochMillis))
    val action = when (event.kind) {
        ExperimentEventKind.SNOOZED -> "snoozed"
        ExperimentEventKind.RETURNED -> "returned"
        ExperimentEventKind.RESNOOZED -> "snoozed again"
        ExperimentEventKind.REMOVED -> "removed"
        ExperimentEventKind.FAILED -> "could not snooze"
        ExperimentEventKind.LOST -> "recovered after reboot"
    }
    return "$time · $label · $action"
}
