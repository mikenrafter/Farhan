package ly.com.tahaben.farhan.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_presentation.AccessFlow
import ly.com.tahaben.privileged_access_presentation.FailureView
import ly.com.tahaben.privileged_access_presentation.FlowOutcome
import ly.com.tahaben.privileged_access_presentation.ShizukuStatus
import ly.com.tahaben.privileged_access_presentation.accessControl
import ly.com.tahaben.privileged_access_presentation.canPair
import ly.com.tahaben.privileged_access_presentation.codeInput
import ly.com.tahaben.privileged_access_presentation.portInput
import ly.com.tahaben.privileged_access_presentation.shizukuStatus
import ly.com.tahaben.privileged_access_presentation.AccessControl

/** What the flow page can ask of its host. */
internal class SpecialAccessFlowActions(
    val onBack: () -> Unit,
    val onRoute: (Route) -> Unit,
    val onPair: (code: String, port: String) -> Unit,
    val onShizuku: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenDeveloperOptions: () -> Unit,
)

/**
 * The page a grant runs on: Back, what the access does, the way in, then the steps for that way. A finished run
 * replaces the form with its result. Failures read in plain words with Try again.
 */
@Composable
internal fun SpecialAccessFlowPage(flow: AccessFlow, status: PrivilegedStatus, actions: SpecialAccessFlowActions) {
    val sensitive = flow.access == Access.SENSITIVE_NOTIFICATIONS
    TextButton(onClick = actions.onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.special_access_flow_back),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
    FlowCard(stringResource(if (sensitive) R.string.special_access_flow_title_sensitive else R.string.special_access_flow_title_grayscale)) {
        Text(
            stringResource(if (sensitive) R.string.special_access_flow_what_sensitive else R.string.special_access_flow_what_grayscale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (val outcome = flow.outcome) {
        is FlowOutcome.Allowed -> AllowedCard(flow.access, outcome, status, actions.onBack)
        is FlowOutcome.Failed -> FailureCard(outcome.failure, actions.onRetry)
        null -> FlowForm(flow, status, actions)
    }
}

/** The page's own boxes: the same thin outline as [SettingsBox], with a bold title instead of a small caption. */
@Composable
private fun FlowCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun AllowedCard(access: Access, outcome: FlowOutcome.Allowed, status: PrivilegedStatus, onDone: () -> Unit) {
    val other = if (access == Access.SENSITIVE_NOTIFICATIONS) Access.SECURE_SETTINGS else Access.SENSITIVE_NOTIFICATIONS
    FlowCard(stringResource(R.string.special_access_flow_allowed)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (access == Access.SENSITIVE_NOTIFICATIONS) R.string.special_access_flow_allowed_sensitive
                    else R.string.special_access_flow_allowed_grayscale,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (accessControl(status, other) != AccessControl.HIDDEN) {
                Text(
                    stringResource(R.string.special_access_flow_allowed_other),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!outcome.verified) {
                Text(
                    stringResource(R.string.special_access_flow_allowed_unverified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    CalmButton(stringResource(R.string.special_access_flow_done), onClick = onDone, filled = true)
}

@Composable
private fun FailureCard(failure: FailureView, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(failure.kind.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            failure.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    CalmButton(stringResource(R.string.special_access_flow_try_again), onClick = onRetry)
}

@Composable
private fun FlowForm(flow: AccessFlow, status: PrivilegedStatus, actions: SpecialAccessFlowActions) {
    Text(
        stringResource(R.string.special_access_flow_route_heading),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteOption(
            R.string.special_access_flow_route_wireless, R.string.special_access_flow_route_wireless_summary,
            selected = flow.route == Route.SELF_ADB, enabled = !flow.busy,
        ) { actions.onRoute(Route.SELF_ADB) }
        RouteOption(
            R.string.special_access_flow_route_shizuku, R.string.special_access_flow_route_shizuku_summary,
            selected = flow.route == Route.SHIZUKU, enabled = !flow.busy,
        ) { actions.onRoute(Route.SHIZUKU) }
    }
    if (flow.route == Route.SELF_ADB) WirelessDebuggingSteps(flow, actions) else ShizukuStep(flow, status, actions)
    Text(
        stringResource(
            if (flow.route == Route.SELF_ADB) R.string.special_access_flow_footer_wireless else R.string.special_access_flow_footer_shizuku,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RouteOption(title: Int, summary: Int, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (selected) ink else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.padding(8.dp),
                colors = RadioButtonDefaults.colors(selectedColor = ink, unselectedColor = MaterialTheme.colorScheme.outline),
            )
            Column(Modifier.padding(top = 6.dp, end = 6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WirelessDebuggingSteps(flow: AccessFlow, actions: SpecialAccessFlowActions) {
    // The code is one-use and never kept: it lives in this composition only, and is cleared the moment it is submitted.
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    SettingsBox(stringResource(R.string.special_access_flow_steps)) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Step("1", R.string.special_access_flow_step1)
            Step("2", R.string.special_access_flow_step2)
            Step("3", R.string.special_access_flow_step3)
            Text(
                stringResource(R.string.special_access_flow_split),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    CalmButton(stringResource(R.string.special_access_flow_open_developer), onClick = actions.onOpenDeveloperOptions)
    OutlinedTextField(
        value = code,
        onValueChange = { code = codeInput(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.special_access_flow_code_label)) },
        singleLine = true,
        enabled = !flow.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = portInput(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.special_access_flow_port_label)) },
        supportingText = { Text(stringResource(R.string.special_access_flow_port_hint)) },
        singleLine = true,
        enabled = !flow.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    CalmButton(
        text = stringResource(if (flow.busy) R.string.special_access_flow_allowing else R.string.special_access_flow_pair),
        onClick = {
            actions.onPair(code, port)
            code = ""
        },
        filled = true,
        enabled = !flow.busy && canPair(code, port),
    )
}

@Composable
private fun Step(number: String, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ShizukuStep(flow: AccessFlow, status: PrivilegedStatus, actions: SpecialAccessFlowActions) {
    val shizuku = shizukuStatus(status)
    SettingsBox(stringResource(R.string.special_access_route_shizuku)) {
        Text(
            stringResource(
                when (shizuku) {
                    ShizukuStatus.RUNNING -> R.string.special_access_flow_shizuku_running
                    ShizukuStatus.NOT_RUNNING -> R.string.special_access_flow_shizuku_not_running
                    ShizukuStatus.NOT_INSTALLED -> R.string.special_access_flow_shizuku_not_installed
                    ShizukuStatus.TOO_OLD -> R.string.special_access_flow_shizuku_too_old
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    CalmButton(
        text = stringResource(if (flow.busy) R.string.special_access_flow_allowing else R.string.special_access_flow_shizuku_allow),
        onClick = actions.onShizuku,
        filled = true,
        enabled = !flow.busy,
    )
}
