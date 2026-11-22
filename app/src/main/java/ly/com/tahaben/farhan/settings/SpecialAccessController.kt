package ly.com.tahaben.farhan.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.LinkState
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult
import ly.com.tahaben.privileged_access_presentation.AccessFlow
import ly.com.tahaben.privileged_access_presentation.FlowPhase
import ly.com.tahaben.privileged_access_presentation.PrivilegedAccessViewModel
import ly.com.tahaben.privileged_access_presentation.ToggleOutcome
import ly.com.tahaben.privileged_access_presentation.defaultFlowRoute
import ly.com.tahaben.privileged_access_presentation.toggleOutcome

/**
 * Holds the open flow page and turns the connector's results into what the user sees. It lives in Settings, above
 * the tabs, so the red note under Verification codes, a switch under More and Back all reach the same flow.
 *
 * Every connector call here was started by a tap. The flow's own run (pair, then grant) goes through [flow]'s
 * steps; a result with no flow running is a switch's or the red note's, and comes back as a short message.
 */
@Stable
internal class SpecialAccessController(
    private val viewModel: PrivilegedAccessViewModel,
    private val say: (Int) -> Unit,
    private val showFlowPage: () -> Unit,
) {
    /** The open flow, or null. Saved across rotation and split-screen resizes; a run in flight is not. */
    var flow by mutableStateOf<AccessFlow?>(null)
        internal set

    /** The access the last switch or note tap was for, so a lost link can open the right flow. */
    private var requested: Access? = null

    private val status get() = viewModel.state.value.status

    fun open(access: Access) {
        viewModel.dismissResult()
        flow = AccessFlow(access, defaultFlowRoute(status, viewModel.state.value.routePreference))
        showFlowPage()
    }

    fun close() {
        flow = null
    }

    /** The red note's Allow: over the retained link when there is one, otherwise through the flow. */
    fun allowFromNote() {
        if (status.link is LinkState.Connected) toggle(Access.SENSITIVE_NOTIFICATIONS, on = true) else open(Access.SENSITIVE_NOTIFICATIONS)
    }

    fun toggle(access: Access, on: Boolean) {
        requested = access
        if (on) grant(access, null) else revoke(access)
    }

    fun selectRoute(route: Route) {
        flow = flow?.withRoute(route)
    }

    fun retry() {
        viewModel.dismissResult()
        flow = flow?.retry()
    }

    fun pair(code: String, port: String) {
        val started = flow?.start() ?: return
        if (started.phase != FlowPhase.PAIRING) return
        flow = started
        viewModel.pairSelfAdb(code, port)
    }

    fun allowWithShizuku() {
        val started = flow?.start() ?: return
        if (started.phase != FlowPhase.SHIZUKU_PERMISSION) return
        flow = started
        viewModel.requestShizukuPermission()
    }

    fun onPairing(result: PairingResult) {
        val current = flow
        viewModel.dismissResult()
        if (current == null || current.phase != FlowPhase.PAIRING) return
        val next = current.pairingDone(result)
        flow = next
        if (next.phase == FlowPhase.GRANTING) grant(next.access, Route.SELF_ADB)
    }

    fun onShizukuPermission(result: ShizukuPermissionResult) {
        val current = flow
        viewModel.dismissResult()
        if (current == null || current.phase != FlowPhase.SHIZUKU_PERMISSION) return
        val next = current.shizukuPermissionDone(result)
        flow = next
        if (next.phase == FlowPhase.GRANTING) grant(next.access, Route.SHIZUKU)
    }

    fun onResult(result: AccessResult) {
        val current = flow
        if (current != null && current.phase == FlowPhase.GRANTING) {
            viewModel.dismissResult()
            flow = current.accessDone(result)
            return
        }
        val outcome = toggleOutcome(result, status)
        viewModel.dismissResult()
        when (outcome) {
            is ToggleOutcome.Say -> say(outcome.message.messageRes())
            is ToggleOutcome.Warn -> say(outcome.failure.kind.messageRes())
            is ToggleOutcome.OpenFlow -> {
                val access = requested
                if (access == null) {
                    say(outcome.failure.kind.messageRes())
                } else {
                    flow = AccessFlow.openedWithFailure(
                        access,
                        outcome.route ?: defaultFlowRoute(status, viewModel.state.value.routePreference),
                        outcome.failure,
                    )
                    showFlowPage()
                }
            }
        }
    }

    private fun grant(access: Access, route: Route?) = when (access) {
        Access.SENSITIVE_NOTIFICATIONS -> viewModel.grantSensitiveNotifications(route)
        Access.SECURE_SETTINGS -> viewModel.grantSecureSettings(route)
    }

    private fun revoke(access: Access) = when (access) {
        Access.SENSITIVE_NOTIFICATIONS -> viewModel.revokeSensitiveNotifications()
        Access.SECURE_SETTINGS -> viewModel.revokeSecureSettings()
    }
}

/**
 * [say] gets a string resource and shows it as a snackbar; [showFlowPage] switches Settings to its More tab, where the
 * flow page is drawn.
 */
@Composable
internal fun rememberSpecialAccessController(
    viewModel: PrivilegedAccessViewModel,
    say: (Int) -> Unit,
    showFlowPage: () -> Unit,
): SpecialAccessController {
    val latestSay by rememberUpdatedState(say)
    val latestShow by rememberUpdatedState(showFlowPage)
    val controller = rememberSaveable(
        viewModel,
        saver = Saver<SpecialAccessController, List<String>>(
            save = { c -> c.flow?.let { listOf(it.access.name, it.route.name) } ?: emptyList() },
            restore = { saved ->
                SpecialAccessController(viewModel, { latestSay(it) }, { latestShow() }).also { c ->
                    if (saved.size == 2) c.flow = AccessFlow(Access.valueOf(saved[0]), Route.valueOf(saved[1]))
                }
            },
        ),
    ) { SpecialAccessController(viewModel, { latestSay(it) }, { latestShow() }) }

    val state = viewModel.state
    // The connector reports each finished step through its state; each is consumed once and then dismissed.
    LaunchedEffect(controller) {
        state.collect { s ->
            val pairing = s.lastPairing
            val shizuku = s.lastShizukuPermission
            val result = s.lastResult
            when {
                pairing != null -> controller.onPairing(pairing)
                shizuku != null -> controller.onShizukuPermission(shizuku)
                result != null -> controller.onResult(result)
            }
        }
    }
    return controller
}
