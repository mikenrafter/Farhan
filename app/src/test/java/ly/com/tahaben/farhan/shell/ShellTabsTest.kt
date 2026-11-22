package ly.com.tahaben.farhan.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellTabsTest {
    @Test
    fun `every feature on shows the bar in order without Launcher`() {
        // why: the signed-off bar (mock-up v6) is Inbox, Summary, Focus, Settings; Launcher is out of the build.
        // catches: reordering tabs, dropping one, or the Launcher tab coming back while LAUNCHER_TAB_AVAILABLE is false.
        assertEquals(
            listOf(ShellTab.Inbox, ShellTab.Summary, ShellTab.Focus, ShellTab.Settings),
            visibleTabs(FeatureSwitches()),
        )
    }

    @Test
    fun `a disabled feature has no tab`() {
        // why: turning Focus off removes the tab, and only Settings brings it back.
        // catches: hiding the wrong tab.
        assertEquals(
            listOf(ShellTab.Inbox, ShellTab.Summary, ShellTab.Settings),
            visibleTabs(FeatureSwitches(focus = false)),
        )
    }

    @Test
    fun `inbox summary and settings are present whatever the switches say`() {
        // why: the owner must always be able to reach Settings > Features to undo a switch.
        // catches: a switch combination that leaves no way back.
        val tabs = visibleTabs(FeatureSwitches(usage = false, launcher = false, focus = false))
        assertEquals(listOf(ShellTab.Inbox, ShellTab.Summary, ShellTab.Settings), tabs)
    }

    @Test
    fun `usage is a Summary section and never a tab`() {
        // why: the usage switch changes Summary's content, not the bar.
        // catches: giving the usage switch a tab of its own.
        assertEquals(visibleTabs(FeatureSwitches(usage = true)), visibleTabs(FeatureSwitches(usage = false)))
    }

    @Test
    fun `a selected tab that was switched off falls back to Settings`() {
        // why: the owner turns Focus off while standing on it in Settings, and a rotation restores the old choice.
        // catches: showing a tab that is no longer on the bar.
        assertEquals(ShellTab.Settings, resolveTab(ShellTab.Focus, FeatureSwitches(focus = false)))
        assertEquals(ShellTab.Focus, resolveTab(ShellTab.Focus, FeatureSwitches()))
        assertEquals(ShellTab.Inbox, resolveTab(ShellTab.Inbox, FeatureSwitches(launcher = false, focus = false)))
    }

    @Test
    fun `badge hides zero and caps displays above 99`() {
        // why: zero unread needs no badge and large counts must fit beside the icon.
        // catches: capping 99 itself or displaying a zero badge.
        assertNull(unreadBadgeText(0))
        assertEquals("1", unreadBadgeText(1))
        assertEquals("99", unreadBadgeText(99))
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99+", unreadBadgeText(1_000))
    }
}
