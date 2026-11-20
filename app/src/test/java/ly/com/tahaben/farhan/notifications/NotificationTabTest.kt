package ly.com.tahaben.farhan.notifications

import ly.com.tahaben.core.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTabTest {
    @Test
    fun `each notification destination selects its own tab`() {
        // why: the selected tab follows the displayed destination.
        // catches: wiring Overview and Inbox to each other's routes.
        assertEquals(NotificationTab.Overview, NotificationTab.fromRoute(Routes.NOTIFICATION_DASHBOARD))
        assertEquals(NotificationTab.Inbox, NotificationTab.fromRoute(Routes.NOTIFICATION_FILTER))
        assertEquals(NotificationTab.Apps, NotificationTab.fromRoute(Routes.APP_PRIORITY))
    }

    @Test
    fun `routes outside the tab host do not select a tab`() {
        // why: a missing or non-tab route has no selected destination to report.
        // catches: reporting Overview for any route prefixed with "notification".
        assertNull(NotificationTab.fromRoute(Routes.NOTIFICATION_FILTER_SETTINGS))
        assertNull(NotificationTab.fromRoute(null))
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
