package ly.com.tahaben.privileged_access_domain

/** Why the phone no longer lets Farhan in. The UI sends the user to pairing (or to Developer options). */
enum class LinkLostReason { NOT_PAIRED, KEY_REJECTED, WIRELESS_DEBUGGING_OFF }

sealed interface AccessFailure {
    /** The retained link is gone or unusable: pair again, or switch wireless debugging back on. */
    data class LinkLost(val reason: LinkLostReason) : AccessFailure

    /** Shizuku is installed but its service is not running: start it from the Shizuku app. */
    data object ShizukuNotRunning : AccessFailure

    /** Any other reason the route could not run the command. [blocker] says which. */
    data class RouteBlocked(val route: Route, val blocker: RouteBlocker) : AccessFailure

    /** The shell ran the command and it failed. [output] is cleaned text from the shell. */
    data class CommandFailed(val route: Route, val exitCode: Int, val output: String) : AccessFailure

    /** The command ran cleanly and the phone still disagrees. */
    data class NotEffective(val route: Route) : AccessFailure

    /** Another command is already running. */
    data object Busy : AccessFailure

    /** Farhan's own application id was not a valid package name, so no command was built. */
    data object InvalidAppId : AccessFailure
}

sealed interface AccessResult {
    /** [verified] is false when the phone could not be read back and the shell's clean exit was trusted. */
    data class Done(val command: PrivilegedCommand, val route: Route, val verified: Boolean) : AccessResult

    /** The preference is "ask" and there is no link: the caller picks a route and calls again. */
    data class ChooseRoute(val options: List<Route>) : AccessResult

    data class Failed(val failure: AccessFailure) : AccessResult
}

object OutputText {
    private const val MAX_CHARS = 1000

    /** Shell output for display: control characters other than newline removed, trimmed, at most 1000 characters. */
    fun clean(raw: String): String =
        raw.filter { it == '\n' || !it.isISOControl() }.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "…" else it }
}

object AccessMapper {
    /** A shell result that did not run the command. */
    fun failureOf(route: Route, blocker: RouteBlocker): AccessFailure = when (blocker) {
        RouteBlocker.NOT_PAIRED -> AccessFailure.LinkLost(LinkLostReason.NOT_PAIRED)
        RouteBlocker.KEY_REJECTED -> AccessFailure.LinkLost(LinkLostReason.KEY_REJECTED)
        RouteBlocker.WIRELESS_DEBUGGING_OFF -> AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF)
        RouteBlocker.SHIZUKU_NOT_RUNNING -> AccessFailure.ShizukuNotRunning
        else -> AccessFailure.RouteBlocked(route, blocker)
    }

    /** A shell result that ran: a clean exit goes on to be checked against the phone, anything else is a failure. */
    fun commandFailure(route: Route, ran: ShellResult.Ran): AccessFailure =
        AccessFailure.CommandFailed(route, ran.exitCode, OutputText.clean(ran.output))
}
