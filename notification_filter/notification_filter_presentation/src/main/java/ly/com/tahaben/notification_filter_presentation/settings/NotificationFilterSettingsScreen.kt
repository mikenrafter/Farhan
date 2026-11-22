package ly.com.tahaben.notification_filter_presentation.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.components.MyDialog
import ly.com.tahaben.core_ui.components.SettingLinkRow
import ly.com.tahaben.core_ui.components.SettingToggleRow
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.core_ui.components.SettingsRowDivider
import ly.com.tahaben.core_ui.components.PermissionDialog
import ly.com.tahaben.core_ui.components.PostNotificationPermissionTextProvider
import ly.com.tahaben.core_ui.components.ScheduleExactAlarmPermissionTextProvider
import ly.com.tahaben.core_ui.mirror
import ly.com.tahaben.core_ui.util.getAnnotatedStringResource
import ly.com.tahaben.showcase_layout_compose.model.Arrow
import ly.com.tahaben.showcase_layout_compose.model.Head
import ly.com.tahaben.showcase_layout_compose.model.ShowcaseMsg
import ly.com.tahaben.showcase_layout_compose.model.Side
import ly.com.tahaben.showcase_layout_compose.ui.ShowcaseLayout
import timber.log.Timber

/**
 * The Notifications tab of Settings: one scrolling page of boxed sections. The sound-control pages open full
 * screen through [onOpenSound], and the schedule editor through [onOpenSchedule], both hosted by Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsPage(
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
    shouldShowRational: (String) -> Boolean,
    snackbarHostState: SnackbarHostState,
    isDarkMode: Boolean,
    onOpenSchedule: () -> Unit,
    onOpenSound: () -> Unit,
    codeAccessNeeded: Boolean,
    codeAccessBusy: Boolean,
    onAllowCodeAccess: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val state = viewModel._state.collectAsStateWithLifecycle().value
    // The service can note that Android hid a code while this screen is open; check every few seconds.
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onEvent(NotificationSettingsEvent.RefreshCodeDiagnostics)
            kotlinx.coroutines.delay(3_000)
        }
    }
    val dialogQueue = state.visiblePermissionDialogQueue
    val scope = rememberCoroutineScope()
    var checkKey by remember {
        mutableStateOf(false)
    }

    val postNotificationPermissionResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                viewModel.onPermissionResult(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    isGranted = isGranted
                )
            }
        }
    )

    val postNotificationPermissionSilentCheck = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted) {
                viewModel.onEvent(NotificationSettingsEvent.DeclinedPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
            checkKey = !checkKey
        }
    )

    LaunchedEffect(key1 = true) {
        viewModel.event.collect { event ->
            when (event) {
                is UiEventNotificationSettings.NotifyMeEnabled -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        viewModel.checkExactAlarmsPermissionGranted()
                        postNotificationPermissionResultLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }

                is UiEventNotificationSettings.PerformSilentChecks -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.isNotifyMeEnabled) {
                        postNotificationPermissionSilentCheck.launch(Manifest.permission.POST_NOTIFICATIONS)
                        viewModel.exactAlarmsSilentCheck()
                    }
                }
            }
        }
    }
    LaunchedEffect(key1 = checkKey) {
        if (state.declinedPermissions.isNotEmpty()) {
            if (dialogQueue.isEmpty()) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        context.getString(R.string.notify_me_permissions_needed_not_granted),
                        actionLabel = context.getString(
                            R.string.grant_access
                        ),
                        duration = SnackbarDuration.Indefinite
                    )
                    when (result) {
                        SnackbarResult.ActionPerformed -> {
                            Timber.d("action performed: declined permissions: ${state.declinedPermissions.toList()} size: ${state.declinedPermissions.size}")
                            dialogQueue.addAll(state.declinedPermissions.toList())
                            state.declinedPermissions.clear()
                        }

                        else -> Unit
                    }
                }
            }
        } else {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }
    val msgStyle = ShowcaseMsg("", msgBackground = MaterialTheme.colorScheme.background, roundedCorner = 15.dp, textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground))
    val annotatedString = getAnnotatedStringResource( R.string.notification_filter_settings_greeting)

    ShowcaseLayout(
        isDarkLayout = isDarkMode,
        greeting = msgStyle.copy(annotatedString),
        isShowcasing = state.isShowcaseOn,
        onFinish = {
            viewModel.onEvent(NotificationSettingsEvent.OnShouldShowcase(false))
        }) {
        val filterSwitchMsg = msgStyle.copy(getAnnotatedStringResource(id = R.string.notification_filter_settings_showcase_msg1), arrow = Arrow(head = Head.TRIANGLE, color = MaterialTheme.colorScheme.background))
        val notifyMeMsg1 = msgStyle.copy(getAnnotatedStringResource(id = R.string.notification_filter_settings_showcase_msg5))
        val notifyMeMsg2 = msgStyle.copy(getAnnotatedStringResource(id = R.string.notification_filter_settings_showcase_msg6), arrow = Arrow(targetFrom = Side.Top, head = Head.TRIANGLE, color = MaterialTheme.colorScheme.background))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.spaceMedium, vertical = spacing.spaceSmall),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
        ) {
            SettingsBox("Delivery") {
                SettingToggleRow(
                    title = stringResource(R.string.notifications_filter),
                    summary = "Hold notifications until the next delivery",
                    info = "While the filter is on, most apps cannot interrupt you. Their notifications wait in the Inbox and are delivered on your schedule. Apps you mark Always allow still come through at once.",
                    checked = state.isServiceEnabled,
                    onCheckedChange = { viewModel.setServiceStats(it) },
                    modifier = Modifier.showcase(1, filterSwitchMsg),
                )
                SettingsRowDivider()
                SettingLinkRow(
                    title = "Daily schedule",
                    summary = "When notifications are delivered",
                    onClick = onOpenSchedule,
                )
                SettingsRowDivider()
                SettingToggleRow(
                    title = stringResource(R.string.notify_me),
                    summary = if (state.isNotifyMeEnabled && state.notifyMeHour != -1) {
                        "Daily at " + String.format("%d:%02d", state.notifyMeHour, state.notifyMeMinute)
                    } else {
                        "A daily reminder to check the Inbox"
                    },
                    info = "Farhan posts one reminder at this time each day. Android may ask for permission to send it and to schedule exact alarms.",
                    checked = state.isNotifyMeEnabled,
                    onCheckedChange = { checked ->
                        if (!checked) viewModel.setNotifyMeTime(-1, -1)
                        else viewModel.onEvent(NotificationSettingsEvent.ShowNotifyMeTimePicker)
                    },
                    modifier = Modifier.showcase(2, notifyMeMsg1).showcase(3, notifyMeMsg2),
                )
                if (state.isNotifyMeEnabled) {
                    SettingLinkRow(
                        title = stringResource(R.string.tap_to_set_new_time),
                        onClick = { viewModel.onEvent(NotificationSettingsEvent.ShowNotifyMeTimePicker) },
                    )
                }
                SettingsRowDivider()
                SettingToggleRow(
                    title = "Delivery testing mode",
                    summary = "Use 3-minute delivery windows",
                    info = "Replaces the hourly delivery boundaries with 3-minute boundaries so you can test delivery quickly. Turn it off for normal use.",
                    checked = state.isDeliveryTestingMode,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.OnSetDeliveryTestingMode(it)) },
                )
                val debuggable = androidx.compose.ui.platform.LocalContext.current.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                if (debuggable) TextButton(onClick = { viewModel.onEvent(NotificationSettingsEvent.ClearBudgetSpend) }) {
                    Text(text = "Clear budget spend (debug)", color = MaterialTheme.colorScheme.error)
                }
            }

            SettingsBox("Verification codes") {
                SettingToggleRow(
                    title = "Raise codes",
                    summary = "Show codes at the top of the Inbox with Copy",
                    info = "The Inbox lifts a one-time code found in a notification to the top, with Copy. Copy marks the clip sensitive and never copies on its own. Farhan only sees the code if Android passes it on; some phones hide it first. A delivered notification keeps its original text.",
                    checked = state.otpCodes,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.SetOtpCodes(it)) },
                )
                SettingsRowDivider()
                if (showCodeAccessNote(state.otpCodes, state.otpRedactionSeen, codeAccessNeeded)) {
                    val appPackage = androidx.compose.ui.platform.LocalContext.current.packageName
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.codes_hidden_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onAllowCodeAccess,
                                enabled = !codeAccessBusy,
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text(
                                    stringResource(if (codeAccessBusy) R.string.special_access_flow_allowing else R.string.special_access_allow),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.codes_hidden_adb_fallback, appPackage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsRowDivider()
                }
                SettingLinkRow(
                    title = "Raise to",
                    summary = state.otpPriority.name.lowercase().replaceFirstChar { it.uppercase() } + " priority",
                    onClick = {
                        val order = ly.com.tahaben.notification_filter_domain.model.NotificationPriority.entries
                        viewModel.onEvent(NotificationSettingsEvent.SetOtpPriority(order[(order.indexOf(state.otpPriority) + 1) % order.size]))
                    },
                )
                SettingsRowDivider()
                SettingToggleRow(
                    title = "Any app",
                    summary = if (state.otpAllApps) "Codes from every app" else "Only apps you pick in Settings, Apps",
                    checked = state.otpAllApps,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.SetOtpAllApps(it)) },
                )
            }

            SettingsBox("Sound") {
                SettingLinkRow(
                    title = "Sound control",
                    summary = if (Build.VERSION.SDK_INT >= 35) "Choose which notifications make sound" else "Needs Android 15 or later",
                    onClick = if (Build.VERSION.SDK_INT >= 35) onOpenSound else null,
                )
            }

            SettingsBox("Automation and labs") {
                AutomationAndExperimentsSection(
                    state = state,
                    onEvent = viewModel::onEvent,
                    credentialForCopy = viewModel::credentialForCopy,
                )
            }
        }
    }

    val timePickerState = rememberTimePickerState()
    if (state.isTimePickerVisible) {
        Dialog(onDismissRequest = {
            viewModel.onEvent(NotificationSettingsEvent.DismissNotifyMeTimePicker)
        }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(
                    modifier = Modifier.padding(spacing.spaceLarge)
                ) {
                    TimePicker(
                        state = timePickerState,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            viewModel.onEvent(NotificationSettingsEvent.DismissNotifyMeTimePicker)
                        }) {
                            Text(
                                text = stringResource(id = R.string.cancel),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.spaceSmall))
                        TextButton(onClick = {
                            viewModel.onEvent(
                                NotificationSettingsEvent.SaveNotifyMeTime(
                                    timePickerState.hour,
                                    timePickerState.minute
                                )
                            )
                        }) {
                            Text(
                                text = stringResource(id = R.string.ok),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }

    dialogQueue
        .reversed()
        .forEach { permission ->
            PermissionDialog(
                permissionTextProvider = when (permission) {
                    Manifest.permission.POST_NOTIFICATIONS -> PostNotificationPermissionTextProvider()
                    Manifest.permission.SCHEDULE_EXACT_ALARM -> ScheduleExactAlarmPermissionTextProvider()
                    else -> return@forEach
                },
                isPermanentlyDeclined = !shouldShowRational(permission),
                onDismiss = { viewModel.onEvent(NotificationSettingsEvent.DismissPermissionDialog) },
                onOkClick = {
                    viewModel.onEvent(NotificationSettingsEvent.DismissPermissionDialog)
                    if (permission == Manifest.permission.SCHEDULE_EXACT_ALARM) {
                        viewModel.openExactAlarmsPermissionScreen()
                    } else {
                        postNotificationPermissionResultLauncher.launch(
                            permission
                        )
                    }
                },
                onGoToAppSettingsClick = {
                    if (permission == Manifest.permission.SCHEDULE_EXACT_ALARM) {
                        viewModel.openExactAlarmsPermissionScreen()
                    } else {
                        viewModel.openAppSettings()
                    }
                    viewModel.onEvent(NotificationSettingsEvent.DismissPermissionDialog)
                }
            )
        }
    if (state.isWarningDialogVisible) {
        MyDialog(onDismissRequest = {}) {
            var doNotShowAgain by rememberSaveable {
                mutableStateOf(false)
            }
            Text(text = stringResource(id = R.string.notification_filter_warning_title), style = MaterialTheme.typography.titleLarge)
            Text(text = stringResource(id = R.string.notification_filter_warning_message))
            Row(
                modifier = Modifier
                    .selectable(
                        selected = doNotShowAgain,
                        onClick = { doNotShowAgain = !doNotShowAgain },
                        role = Role.Checkbox
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(id = R.string.do_not_show_again))
                Checkbox(checked = doNotShowAgain, onCheckedChange = {doNotShowAgain = it})
            }
            Row {
                TextButton(onClick = {
                    viewModel.onEvent(
                        NotificationSettingsEvent.DismissWarningDialog(
                            doNotShowAgain
                        )
                    )
                }) {
                    Text(text = stringResource(id = R.string.i_understand))
                }
                Spacer(modifier = Modifier.width(spacing.spaceMedium))
                TextButton(onClick = {
                    viewModel.onEvent(NotificationSettingsEvent.OnShouldShowcase(true))
                    viewModel.onEvent(NotificationSettingsEvent.DismissWarningDialog(false))
                }) {
                    Text(text = stringResource(id = R.string.tell_me_more))
                }

            }
        }
    }
}


