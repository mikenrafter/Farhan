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
    val snoozeDemoExperiment: Boolean = false,
    val snoozeDemoEvents: List<ly.com.tahaben.notification_filter_domain.delivery.ExperimentEvent> = emptyList(),
)
