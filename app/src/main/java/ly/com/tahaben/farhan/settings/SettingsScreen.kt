package ly.com.tahaben.farhan.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ly.com.tahaben.core.R
import ly.com.tahaben.privileged_access_domain.NetworkAccess
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import ly.com.tahaben.core_ui.components.SettingLinkRow
import ly.com.tahaben.core_ui.components.SettingToggleRow
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.core_ui.components.SettingsRowDivider
import ly.com.tahaben.farhan.shell.LAUNCHER_TAB_AVAILABLE
import ly.com.tahaben.farhan.shell.FeatureSwitches
import ly.com.tahaben.notification_filter_presentation.apppriority.AppPriorityDetail
import ly.com.tahaben.notification_filter_presentation.apppriority.AppPriorityScreen
import ly.com.tahaben.notification_filter_presentation.settings.NotificationSettingsPage
import ly.com.tahaben.notification_filter_presentation.settings.NotificationSoundControlPage
import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core.model.UIModeAppearance
import ly.com.tahaben.core.navigation.Routes
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.core_ui.theme.FarhanTheme
import ly.com.tahaben.core_ui.util.isCurrentlyDark
import ly.com.tahaben.onboarding_presentaion.ThemeColorSwatch
import ly.com.tahaben.onboarding_presentaion.main.MainScreenEvent
import ly.com.tahaben.onboarding_presentaion.main.MainScreenState
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_presentation.PrivilegedAccessViewModel
import ly.com.tahaben.privileged_access_presentation.sensitiveNeedsAllowing

internal enum class SettingsTab(@StringRes val label: Int) {
    Features(R.string.settings_tab_features),
    Notifications(R.string.settings_tab_notifications),
    Apps(R.string.settings_tab_apps),
    Focus(R.string.settings_tab_focus),
    More(R.string.settings_tab_more),
}

/** A page one level below a Settings tab. It covers the whole Settings area, tabs included, and Back closes it. */
private sealed interface SettingsSubPage {
    data object Sound : SettingsSubPage
    data class App(val packageName: String) : SettingsSubPage
}

/**
 * Settings as top tabs: Features, Notifications, Apps, Focus, More. Inside a tab there is at most one level more:
 * the sound-control pages and a single app's page open over the tabs, and the schedule editor, backup, about and
 * the focus tools' own screens are pushed routes.
 */
@Composable
internal fun SettingsScreen(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    state: MainScreenState,
    onEvent: (MainScreenEvent) -> Unit,
    switches: FeatureSwitches,
    onUsageChange: (Boolean) -> Unit,
    onLauncherChange: (Boolean) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
    isDarkMode: Boolean,
    shouldShowRational: (String) -> Boolean,
    onOpen: (String) -> Unit,
    accessViewModel: PrivilegedAccessViewModel = hiltViewModel(),
    checksViewModel: SpecialAccessChecksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessState by accessViewModel.state.collectAsStateWithLifecycle()
    val say: (Int) -> Unit = remember(context, scope, snackbarHostState) {
        { res ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(context.getString(res))
            }
        }
    }
    // The flow page lives here, above the tabs, so the red note under Notifications can open it on More.
    val access = rememberSpecialAccessController(accessViewModel, say) { onSelectTab(SettingsTab.More) }
    // Wireless debugging and Shizuku change outside the app; the status read is cheap and runs no command.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(selectedTab, lifecycle) {
        if (selectedTab == SettingsTab.Notifications || selectedTab == SettingsTab.More) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    accessViewModel.status()
                    checksViewModel.refresh()
                    delay(ACCESS_REFRESH_MS)
                }
            }
        }
    }
    val flowOpen = selectedTab == SettingsTab.More && access.flow != null
    // The app's package name, not a SettingsSubPage, so it survives rotation and process death.
    var appPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    // Switching tab, including through the Inbox gear, drops an app page or sound page left open on another tab.
    LaunchedEffect(selectedTab) {
        appPackage = null
        soundOpen = false
        if (selectedTab != SettingsTab.More) access.close()
    }
    val subPage: SettingsSubPage? = when {
        soundOpen -> SettingsSubPage.Sound
        appPackage != null -> SettingsSubPage.App(appPackage!!)
        else -> null
    }
    BackHandler(enabled = subPage != null || flowOpen) {
        when {
            soundOpen -> soundOpen = false
            appPackage != null -> appPackage = null
            else -> access.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CommonAppBar(title = stringResource(R.string.settings), onNavigateUp = null)
            SettingsTabRow(selectedTab) { tab ->
                access.close()
                onSelectTab(tab)
            }
            when (selectedTab) {
                SettingsTab.Features -> FeaturesTab(
                    state, onEvent, switches, onUsageChange, onLauncherChange, onFocusChange, onOpen,
                )
                SettingsTab.Notifications -> NotificationSettingsPage(
                    shouldShowRational = shouldShowRational,
                    snackbarHostState = snackbarHostState,
                    isDarkMode = isDarkMode,
                    onOpenSchedule = { onOpen(Routes.NOTIFICATION_SCHEDULE_EDITOR) },
                    onOpenSound = { soundOpen = true },
                    codeAccessNeeded = sensitiveNeedsAllowing(accessState.status),
                    codeAccessBusy = accessState.working,
                    onAllowCodeAccess = access::allowFromNote,
                )
                // The list stays composed under an app's page, so Back returns to the same search and scroll.
                SettingsTab.Apps -> AppPriorityScreen(onOpenApp = { appPackage = it })
                SettingsTab.Focus -> FocusSettingsTab(onOpen)
                SettingsTab.More -> {
                    val flow = access.flow
                    if (flow != null) {
                        val actions = remember(access, context, say) {
                            SpecialAccessFlowActions(
                                onBack = access::close,
                                onRoute = access::selectRoute,
                                onPair = access::pair,
                                onShizuku = access::allowWithShizuku,
                                onRetry = access::retry,
                                onOpenDeveloperOptions = { openDeveloperOptions(context, say) },
                                onOpenAppSettings = { openAppSettings(context, say) },
                                onCheck = access::checkConnection,
                                onCopyLog = {
                                    val log = access.connectionLog()
                                    if (log.isBlank()) say(R.string.special_access_diag_empty)
                                    else { ConnectionLogSharing.copy(context, log); say(R.string.special_access_diag_copied) }
                                },
                                onShareLog = {
                                    val log = access.connectionLog()
                                    if (log.isBlank()) say(R.string.special_access_diag_empty)
                                    else ConnectionLogSharing.share(context, log, context.getString(R.string.special_access_diag_share_title))
                                },
                            )
                        }
                        SettingsTabColumn { SpecialAccessFlowPage(flow, accessState.status, accessState.connectionCheck, access.lastPort, accessState.networkAccess is NetworkAccess.Denied, actions) }
                    } else {
                        MoreTab(
                            state, onEvent, onOpen,
                            status = accessState.status,
                            system = checksViewModel.checks,
                            working = accessState.working,
                            onAllow = access::open,
                            onToggle = access::toggle,
                        )
                    }
                }
            }
        }
        when (val page = subPage) {
            null -> Unit
            SettingsSubPage.Sound -> SubPageSurface {
                NotificationSoundControlPage(onBack = { soundOpen = false })
            }
            is SettingsSubPage.App -> SubPageSurface {
                CommonAppBar(title = stringResource(R.string.settings_tab_apps), onNavigateUp = { appPackage = null })
                AppPriorityDetail(page.packageName) { AppFocusSection(page.packageName) }
            }
        }
    }
}

private const val ACCESS_REFRESH_MS = 3_000L

/**
 * Opens Developer options. In split screen it opens beside Farhan, so the pairing code stays on screen; elsewhere the
 * flag does nothing.
 */
private fun openAppSettings(context: android.content.Context, say: (Int) -> Unit) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        say(R.string.special_access_flow_open_developer_failed)
    } catch (_: SecurityException) {
        say(R.string.special_access_flow_open_developer_failed)
    }
}

private fun openDeveloperOptions(context: android.content.Context, say: (Int) -> Unit) {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        say(R.string.special_access_flow_open_developer_failed)
    } catch (_: SecurityException) {
        say(R.string.special_access_flow_open_developer_failed)
    }
}

@Composable
private fun SubPageSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), content = content)
    }
}

/**
 * The active tab gets the one strong colour: a thin primary indicator. Everything else is neutral. All five
 * tabs share the width, so none sits off-screen; the labels are small and padded little to make that fit.
 */
@Composable
private fun SettingsTabRow(selected: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(positions[selected.ordinal]),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        SettingsTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    stringResource(tab.label),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SettingsTabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingsNote(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun FeaturesTab(
    state: MainScreenState,
    onEvent: (MainScreenEvent) -> Unit,
    switches: FeatureSwitches,
    onUsageChange: (Boolean) -> Unit,
    onLauncherChange: (Boolean) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
) = SettingsTabColumn {
    SettingsBox(stringResource(R.string.app_name)) {
        SettingToggleRow(
            title = stringResource(R.string.feature_farhan_on),
            summary = stringResource(R.string.feature_farhan_on_summary),
            checked = state.isMainSwitchEnabled,
            onCheckedChange = { onEvent(MainScreenEvent.SaveMainSwitchState(it)) },
        )
    }
    SettingsBox(stringResource(R.string.settings_features)) {
        SettingToggleRow(
            title = stringResource(R.string.feature_inbox),
            summary = stringResource(R.string.feature_always_on),
            checked = true,
            onCheckedChange = null,
        )
        SettingsRowDivider()
        SettingToggleRow(
            title = stringResource(R.string.feature_summary),
            summary = stringResource(R.string.feature_always_on),
            checked = true,
            onCheckedChange = null,
        )
        SettingsRowDivider()
        SettingToggleRow(
            title = stringResource(R.string.feature_usage),
            summary = stringResource(R.string.feature_usage_summary),
            info = stringResource(R.string.feature_off_note),
            checked = switches.usage,
            onCheckedChange = onUsageChange,
        )
        if (switches.usage) {
            SettingLinkRow(title = stringResource(R.string.usage_settings), onClick = { onOpen(Routes.USAGE_SETTINGS) })
        }
        if (LAUNCHER_TAB_AVAILABLE) {
            SettingsRowDivider()
            SettingToggleRow(
                title = stringResource(R.string.feature_launcher),
                summary = stringResource(R.string.feature_launcher_summary),
                info = stringResource(R.string.feature_off_note),
                checked = switches.launcher,
                onCheckedChange = onLauncherChange,
            )
        }
        SettingsRowDivider()
        SettingToggleRow(
            title = stringResource(R.string.feature_focus),
            summary = stringResource(R.string.feature_focus_summary),
            info = stringResource(R.string.feature_off_note),
            checked = switches.focus,
            onCheckedChange = onFocusChange,
        )
    }
}

@Composable
private fun FocusSettingsTab(onOpen: (String) -> Unit) = SettingsTabColumn {
    SettingsBox(stringResource(R.string.feature_focus)) {
        SettingLinkRow(title = stringResource(R.string.infinite_scrolling), onClick = { onOpen(Routes.INFINITE_SCROLLING) })
        SettingsRowDivider()
        SettingLinkRow(title = stringResource(R.string.grayscale), onClick = { onOpen(Routes.SCREEN_GRAY_SCALE) })
        SettingsRowDivider()
        SettingLinkRow(title = stringResource(R.string.app_time_limiter), onClick = { onOpen(Routes.TimeLimiter_SETTINGS) })
        SettingsRowDivider()
        SettingLinkRow(title = stringResource(R.string.delayed_launch), onClick = { onOpen(Routes.DELAYED_LAUNCH_SETTINGS) })
    }
    SettingsNote(R.string.settings_focus_note)
}

@Composable
private fun MoreTab(
    state: MainScreenState,
    onEvent: (MainScreenEvent) -> Unit,
    onOpen: (String) -> Unit,
    status: ly.com.tahaben.privileged_access_domain.PrivilegedStatus,
    system: SystemAccessChecks,
    working: Boolean,
    onAllow: (Access) -> Unit,
    onToggle: (Access, Boolean) -> Unit,
) = SettingsTabColumn {
    SpecialAccessGroup(status, system, working, onAllow, onToggle)
    SettingsBox(stringResource(R.string.settings_look_caption)) {
        SettingsRow(R.string.appearance, Icons.Filled.Brightness6)
        AppearanceOptions(state.uiMode) { onEvent(MainScreenEvent.SaveUiMode(it)) }
        SettingsRow(R.string.theme_colors, Icons.Filled.Palette)
        ThemeOptions(state) { onEvent(MainScreenEvent.SaveThemeColorsMode(it)) }
    }
    SettingsBox(stringResource(R.string.settings_data_caption)) {
        SettingLinkRow(
            title = stringResource(R.string.backup_and_restore),
            summary = stringResource(R.string.backup_and_restore_summary),
            onClick = { onOpen(Routes.BACKUP_RESTORE) },
        )
    }
    SettingsBox(stringResource(R.string.settings_app_caption)) {
        SettingLinkRow(title = stringResource(R.string.settings_about_farhan), onClick = { onOpen(Routes.ABOUT_APP) })
    }
}

@Composable
internal fun BackupRestoreScreen(
    onNavigateUp: () -> Unit,
    onStartBackup: (BackupAction) -> Unit,
) {
    SettingsPage(R.string.backup_and_restore, onNavigateUp) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackupNote(R.string.backup_and_restore_intro)
            OutlinedCard(onClick = { onStartBackup(BackupAction.Export) }) {
                SettingsRow(R.string.export_settings, Icons.Filled.FileUpload, R.string.export_settings_summary)
            }
            OutlinedCard(onClick = { onStartBackup(BackupAction.Import) }) {
                SettingsRow(R.string.import_settings, Icons.Filled.FileDownload, R.string.import_settings_summary)
            }
            BackupNote(R.string.backup_and_restore_confirmation)
        }
    }
}

@Composable
private fun BackupNote(@StringRes text: Int) {
    Text(
        stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SettingsPage(
    @StringRes title: Int,
    onNavigateUp: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { CommonAppBar(title = stringResource(title), onNavigateUp = onNavigateUp) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsRow(
    @StringRes title: Int,
    icon: ImageVector,
    @StringRes summary: Int? = null,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceOptions(
    selectedMode: UIModeAppearance,
    onSelect: (UIModeAppearance) -> Unit,
) {
    val modes = listOf(UIModeAppearance.LIGHT_MODE, UIModeAppearance.DARK_MODE, UIModeAppearance.FOLLOW_SYSTEM)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) {
        modes.forEachIndexed { index, mode ->
            val label = when (mode) {
                UIModeAppearance.LIGHT_MODE -> R.string.light
                UIModeAppearance.DARK_MODE -> R.string.appearance_dark
                UIModeAppearance.FOLLOW_SYSTEM -> R.string.appearance_system
            }
            SegmentedButton(
                selected = mode == selectedMode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    activeContentColor = MaterialTheme.colorScheme.onSurface,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                    inactiveContainerColor = MaterialTheme.colorScheme.background,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun ThemeOptions(state: MainScreenState, onSelect: (ThemeColors) -> Unit) {
    val options = ThemeColors.entries.filter { it != ThemeColors.Dynamic || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val outline = MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.padding(horizontal = 12.dp).selectableGroup()) {
        options.sortedBy { it != ThemeColors.Classic }.forEach { option ->
            val selected = state.themeColors == option
            val label = when (option) {
                ThemeColors.Classic -> R.string.classic
                ThemeColors.Vibrant -> R.string.vibrant
                ThemeColors.Dynamic -> R.string.dynamic
            }
            val description = stringResource(label)
            Box(
                modifier = Modifier.size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(28.dp)
                        .border(2.dp, if (selected) outline else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    FarhanTheme(darkMode = state.uiMode.isCurrentlyDark(), colorStyle = option) {
                        ThemeColorSwatch(MaterialTheme.colorScheme.primary, Modifier.size(22.dp), CircleShape)
                    }
                }
            }
        }
    }
}
