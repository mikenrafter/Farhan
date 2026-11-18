package ly.com.tahaben.notification_filter_domain.util

import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource

interface KnownPackageSourceRegistry {
    fun getAll(): Map<String, KnownPackageSource>

    /** One source without copying the catalog. RED STUB: still goes through [getAll]. */
    fun get(packageName: String): KnownPackageSource? = getAll()[packageName]
    fun register(sources: List<VisibleNotificationSource>)
}
