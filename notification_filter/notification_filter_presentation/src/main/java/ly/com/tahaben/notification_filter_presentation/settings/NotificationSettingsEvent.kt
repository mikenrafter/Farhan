package ly.com.tahaben.notification_filter_presentation.settings

sealed class NotificationSettingsEvent {
    object CancelNotifyMe : NotificationSettingsEvent()
    object ShowNotifyMeTimePicker : NotificationSettingsEvent()
    object DismissNotifyMeTimePicker : NotificationSettingsEvent()
    data class SaveNotifyMeTime(val hour: Int, val min: Int) : NotificationSettingsEvent()
    object DismissPermissionDialog : NotificationSettingsEvent()
    object OpenExactAlarmPermissionPage : NotificationSettingsEvent()
    data class DeclinedPermission(val permission: String) : NotificationSettingsEvent()
    data class DismissWarningDialog(val doNotShowAgain: Boolean) : NotificationSettingsEvent()
    data class OnShouldShowcase(val showcase: Boolean): NotificationSettingsEvent()
    data class OnSetDeliveryTestingMode(val enabled: Boolean) : NotificationSettingsEvent()
    object ClearBudgetSpend : NotificationSettingsEvent()
    data class SetAutomationEnabled(val enabled: Boolean) : NotificationSettingsEvent()
    object ShowAutomationCredential : NotificationSettingsEvent()
    object HideAutomationCredential : NotificationSettingsEvent()
    object RotateAutomationCredential : NotificationSettingsEvent()
    object RevokeAutomationCredential : NotificationSettingsEvent()
    object RefreshAutomation : NotificationSettingsEvent()
    data class SetSourceIconExperiment(val enabled: Boolean) : NotificationSettingsEvent()
    data class SetSnoozeExperiment(val enabled: Boolean) : NotificationSettingsEvent()
    data class SetOtpCodes(val enabled: Boolean) : NotificationSettingsEvent()
    /** Re-reads the "Android hid a code" diagnostic, which the notification service sets while Settings is open. */
    object RefreshCodeDiagnostics : NotificationSettingsEvent()
    data class SetOtpPriority(val priority: ly.com.tahaben.notification_filter_domain.model.NotificationPriority) : NotificationSettingsEvent()
    data class SetOtpAllApps(val allApps: Boolean) : NotificationSettingsEvent()
}
