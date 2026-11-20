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
import ly.com.tahaben.core.R
import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core.model.UIModeAppearance
import ly.com.tahaben.core.navigation.Routes
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.core_ui.theme.FarhanTheme
import ly.com.tahaben.core_ui.util.isCurrentlyDark
import ly.com.tahaben.onboarding_presentaion.ThemeColorSwatch
import ly.com.tahaben.onboarding_presentaion.main.MainScreenEvent
import ly.com.tahaben.onboarding_presentaion.main.MainScreenState

@Composable
internal fun SettingsScreen(
    state: MainScreenState,
    onEvent: (MainScreenEvent) -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (String) -> Unit,
) {
    SettingsPage(R.string.settings, onNavigateUp) {
        GroupHeading(R.string.settings_look)
        SettingsRow(R.string.appearance, Icons.Filled.Brightness6)
        AppearanceOptions(state.uiMode) { onEvent(MainScreenEvent.SaveUiMode(it)) }
        SettingsRow(R.string.theme_colors, Icons.Filled.Palette)
        ThemeOptions(state) { onEvent(MainScreenEvent.SaveThemeColorsMode(it)) }

        GroupHeading(R.string.settings_your_data)
        SettingsLink(
            R.string.backup_and_restore, Icons.Filled.FileDownload, R.string.backup_and_restore_summary,
        ) { onOpen(Routes.BACKUP_RESTORE) }

        GroupHeading(R.string.settings_features)
        SettingsLink(R.string.usage, Icons.Filled.BarChart) { onOpen(Routes.USAGE_SETTINGS) }
        SettingsLink(R.string.notifications, Icons.Filled.Notifications) { onOpen(Routes.NOTIFICATION_FILTER_SETTINGS) }
        SettingsLink(R.string.delayed_launch, Icons.Filled.AccessTime) { onOpen(Routes.DELAYED_LAUNCH_SETTINGS) }

        GroupHeading(R.string.settings_about)
        SettingsLink(R.string.settings_about_farhan, Icons.Filled.Info) { onOpen(Routes.ABOUT_APP) }
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
private fun GroupHeading(@StringRes title: Int) {
    Text(
        stringResource(title),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun SettingsLink(
    @StringRes title: Int,
    icon: ImageVector,
    @StringRes summary: Int? = null,
    onClick: () -> Unit,
) {
    SettingsRow(
        title, icon, summary,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
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
