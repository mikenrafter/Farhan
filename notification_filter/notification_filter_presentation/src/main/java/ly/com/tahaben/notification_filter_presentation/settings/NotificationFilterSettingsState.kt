package ly.com.tahaben.notification_filter_presentation.settings

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

data class NotificationFilterSettingsState(
    val isServiceEnabled: Boolean = false,
    val isNotifyMeEnabled: Boolean = false,
    val notifyMeHour: Int = -1,
    val notifyMeMinute: Int = -1,
    val isTimePickerVisible: Boolean = false,
    val visiblePermissionDialogQueue: SnapshotStateList<String> = mutableStateListOf(),
    val declinedPermissions: SnapshotStateList<String> = mutableStateListOf(),
    val isWarningDialogVisible: Boolean = false,
    val isShowcaseOn: Boolean = false,
    val isDeliveryTestingMode: Boolean = false,
    val automationEnabled: Boolean = false,
    val hasAutomationCredential: Boolean = false,
    /** Set only while the owner has chosen to show it; cleared on hide, rotate, revoke and when the screen closes. */
    val revealedCredential: String? = null,
    val lastAutomationReceipt: ly.com.tahaben.notification_filter_domain.schedule.AutomationReceipt? = null,
    val automationError: String? = null,
    val sourceIconExperiment: Boolean = false,
    val snoozeExperiment: Boolean = false,
    val otpCodes: Boolean = false,
    /** Android hid a code from Farhan at least once, so codes cannot be raised until the app-op is allowed. */
    val otpRedactionSeen: Boolean = false,
    val otpPriority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority = ly.com.tahaben.notification_filter_domain.model.NotificationPriority.CRITICAL,
    val otpAllApps: Boolean = true,
    val snoozeCounts: Map<ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventKind, Long> = emptyMap(),
    val snoozeNext: Map<ly.com.tahaben.notification_filter_domain.model.NotificationPriority, java.time.Instant?> = emptyMap(),
    val snoozeImmediate: Set<ly.com.tahaben.notification_filter_domain.model.NotificationPriority> = emptySet(),
    val snoozeEvents: List<ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent> = emptyList(),
)

/**
 * The red note under Verification codes: shown while codes are raised, Android has hidden one from Farhan, and the
 * phone does not yet allow sensitive notifications. [accessNeeded] is the connector's word on that last part, so the
 * note goes away as soon as access is allowed, even when the "hidden" flag has not been cleared yet.
 */
fun showCodeAccessNote(otpCodes: Boolean, redactionSeen: Boolean, accessNeeded: Boolean): Boolean =
    otpCodes && redactionSeen && accessNeeded
