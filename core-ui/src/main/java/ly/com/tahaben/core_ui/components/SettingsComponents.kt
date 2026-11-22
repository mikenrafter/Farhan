package ly.com.tahaben.core_ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core.R

/**
 * Building blocks for the calm settings style: neutral surface, thin outline, ink-coloured switches. The only
 * strong colour on a settings page is the active tab indicator and small status dots.
 */

@Composable
fun calmSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.background,
    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
    checkedBorderColor = MaterialTheme.colorScheme.onSurface,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.background,
    disabledCheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/** A thin-outlined box with a small caption, holding rows that belong together. */
@Composable
fun SettingsBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp).semantics { heading() },
            )
            content()
        }
    }
}

/** Hairline between rows of one [SettingsBox]. */
@Composable
fun SettingsRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One setting with a single short line under it. Long explanation goes in [info], behind an icon and a dialog.
 * With [onCheckedChange] null the switch is shown but disabled, for a setting that is not wired up yet.
 */
@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    info: String? = null,
) {
    val enabled = onCheckedChange != null
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(
                if (enabled) Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange!!)
                else Modifier
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
        if (info != null) InfoButton(title, info)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = calmSwitchColors())
    }
}

/** One setting that opens something. [onClick] null shows it disabled. */
@Composable
fun SettingLinkRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    info: String? = null,
    onClick: (() -> Unit)?,
) {
    val enabled = onClick != null
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
        if (info != null) InfoButton(title, info)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        )
    }
}

/** One setting with a status or a control at the right (a pill, a button) instead of a switch or an arrow. */
@Composable
fun SettingValueRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RowText(title, summary, enabled = true, Modifier.weight(1f))
        trailing()
    }
}

/** A small outlined label with a status dot: filled with the ink colour when [on], a hollow outline when not. */
@Composable
fun StatusPill(text: String, on: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).then(
                if (on) Modifier.background(MaterialTheme.colorScheme.onSurface)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ),
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowText(title: String, summary: String?, enabled: Boolean, modifier: Modifier) {
    Column(modifier.alpha(if (enabled) 1f else 0.5f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The small info icon. Tapping it opens [text] in a dialog titled [title]. */
@Composable
fun InfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.settings_more_info, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
