package ly.com.tahaben.farhan.shell

/**
 * The Launcher tab is out of the build until the owner says otherwise (2026-11-22). The launcher code, its settings
 * route and the saved switch stay; set this to true to bring the tab and its Features switch back.
 */
internal const val LAUNCHER_TAB_AVAILABLE = false

/** Bottom-bar destinations, in bar order. */
internal enum class ShellTab { Inbox, Summary, Launcher, Focus, Settings }

/**
 * The owner's feature switches. Inbox, Summary and Settings have no switch: they are always present.
 * Usage is a section of Summary, not a tab.
 */
internal data class FeatureSwitches(
    val usage: Boolean = true,
    val launcher: Boolean = true,
    val focus: Boolean = true,
)

/** The bar, in order. A disabled feature has no tab; Settings is the only way to bring it back. */
internal fun visibleTabs(switches: FeatureSwitches): List<ShellTab> = buildList {
    add(ShellTab.Inbox)
    add(ShellTab.Summary)
    if (LAUNCHER_TAB_AVAILABLE && switches.launcher) add(ShellTab.Launcher)
    if (switches.focus) add(ShellTab.Focus)
    add(ShellTab.Settings)
}

/**
 * The tab to show: [selected] when it is still on the bar, else Settings, where the owner turned the feature off
 * and where they would turn it back on.
 */
internal fun resolveTab(selected: ShellTab, switches: FeatureSwitches): ShellTab =
    if (selected in visibleTabs(switches)) selected else ShellTab.Settings

internal fun unreadBadgeText(unreadCount: Int): String? =
    if (unreadCount <= 0) null else if (unreadCount > 99) "99+" else unreadCount.toString()
