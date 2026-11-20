package ly.com.tahaben.notification_filter_data.service

import ly.com.tahaben.notification_filter_domain.delivery.NotificationActionStore

internal const val PROXY_TAG = "farhan:"
internal data class PostedNotification(val tag: String?, val id: Int)

// CLAIM(scoped-proxy-effects): one active snapshot bounds cancellation to live matching proxies [ScopedProxyClearTest].
internal fun clearScopedProxies(
    ids: List<String>,
    actions: NotificationActionStore<*>,
    activeNotifications: () -> List<PostedNotification>,
    cancel: (String, Int) -> Unit,
) {
    if (ids.isEmpty()) return
    val tags = ids.mapTo(mutableSetOf()) { id ->
        actions.remove(id)
        PROXY_TAG + id
    }
    for (notification in activeNotifications()) {
        val tag = notification.tag ?: continue
        if (tag in tags) cancel(tag, notification.id)
    }
}
