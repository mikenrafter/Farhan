package ly.com.tahaben.notification_filter_domain.util

import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource

interface KnownPackageSourceRegistry {
    fun getAll(): Map<String, KnownPackageSource>
    fun register(sources: List<VisibleNotificationSource>)
}
