package ly.com.tahaben.farhan.notifications

import ly.com.tahaben.core.navigation.Routes

internal enum class NotificationTab(val route: String) {
    Overview(Routes.NOTIFICATION_DASHBOARD),
    Inbox(Routes.NOTIFICATION_FILTER),
    Apps(Routes.APP_PRIORITY);

    companion object {
        fun fromRoute(route: String?): NotificationTab? = entries.firstOrNull { it.route == route }
    }
}

internal fun unreadBadgeText(unreadCount: Int): String? =
    if (unreadCount <= 0) null else if (unreadCount > 99) "99+" else unreadCount.toString()
