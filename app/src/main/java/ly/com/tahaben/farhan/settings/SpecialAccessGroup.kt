package ly.com.tahaben.farhan.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.components.SettingToggleRow
import ly.com.tahaben.core_ui.components.SettingValueRow
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.core_ui.components.SettingsRowDivider
import ly.com.tahaben.core_ui.components.StatusPill
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_presentation.AccessControl
import ly.com.tahaben.privileged_access_presentation.accessControl
import ly.com.tahaben.privileged_access_presentation.groupNote
import ly.com.tahaben.privileged_access_presentation.linkRoute

/**
 * Settings, More: every access Farhan holds and why. The two Android-managed ones only report. The two the connector
 * grants each show the control that [accessControl] picks: Allow (opens the flow) or a switch.
 */
@Composable
internal fun SpecialAccessGroup(
    status: PrivilegedStatus,
    system: SystemAccessChecks,
    working: Boolean,
    onAllow: (Access) -> Unit,
    onToggle: (Access, Boolean) -> Unit,
) {
    SettingsBox(stringResource(R.string.special_access_caption)) {
        SystemAccessRow(R.string.special_access_notification, R.string.special_access_notification_summary, system.notificationAccess)
        SettingsRowDivider()
        SystemAccessRow(R.string.special_access_accessibility, R.string.special_access_accessibility_summary, system.accessibility)
        if (accessControl(status, Access.SENSITIVE_NOTIFICATIONS) != AccessControl.HIDDEN) {
            SettingsRowDivider()
            GrantedAccessRow(
                Access.SENSITIVE_NOTIFICATIONS, R.string.special_access_sensitive, R.string.special_access_sensitive_summary,
                accessControl(status, Access.SENSITIVE_NOTIFICATIONS), working, onAllow, onToggle,
            )
        }
        SettingsRowDivider()
        GrantedAccessRow(
            Access.SECURE_SETTINGS, R.string.special_access_grayscale, R.string.special_access_grayscale_summary,
            accessControl(status, Access.SECURE_SETTINGS), working, onAllow, onToggle,
        )
        when (linkRoute(status)) {
            null -> Unit
            Route.SELF_ADB -> {
                SettingsRowDivider()
                SettingValueRow(
                    stringResource(R.string.special_access_route_wireless),
                    summary = stringResource(R.string.special_access_route_wireless_caption),
                ) { StatusPill(stringResource(R.string.special_access_connected), on = true) }
            }
            Route.SHIZUKU -> {
                SettingsRowDivider()
                SettingValueRow(
                    stringResource(R.string.special_access_route_shizuku),
                    summary = stringResource(R.string.special_access_route_shizuku_caption),
                ) { StatusPill(stringResource(R.string.special_access_connected), on = true) }
            }
        }
        Text(
            stringResource(groupNote(status).messageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SystemAccessRow(title: Int, summary: Int, allowed: Boolean) {
    SettingValueRow(stringResource(title), summary = stringResource(summary)) {
        StatusPill(
            stringResource(if (allowed) R.string.special_access_allowed else R.string.special_access_not_allowed),
            on = allowed,
        )
    }
}

@Composable
private fun GrantedAccessRow(
    access: Access,
    title: Int,
    summary: Int,
    control: AccessControl,
    working: Boolean,
    onAllow: (Access) -> Unit,
    onToggle: (Access, Boolean) -> Unit,
) {
    when (control) {
        AccessControl.SWITCH_ON, AccessControl.SWITCH_OFF -> SettingToggleRow(
            title = stringResource(title),
            summary = stringResource(summary),
            checked = control == AccessControl.SWITCH_ON,
            onCheckedChange = if (working) null else { on -> onToggle(access, on) },
        )
        AccessControl.ALLOW_BUTTON -> SettingValueRow(stringResource(title), summary = stringResource(summary)) {
            CalmButton(stringResource(R.string.special_access_allow), onClick = { onAllow(access) }, enabled = !working)
        }
        AccessControl.PENDING, AccessControl.HIDDEN -> SettingValueRow(stringResource(title), summary = stringResource(summary)) {}
    }
}

/**
 * A quiet button: a thin outline on a neutral fill. [filled] is the ink colour, for the one action a page is about.
 * Neither uses the theme's accent.
 */
@Composable
internal fun CalmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(16.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    val sized = modifier.heightIn(min = 36.dp)
    if (filled) {
        Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = ink,
                contentColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = ink.copy(alpha = 0.12f),
                disabledContentColor = ink.copy(alpha = 0.38f),
            ),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = ink,
            ),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}
