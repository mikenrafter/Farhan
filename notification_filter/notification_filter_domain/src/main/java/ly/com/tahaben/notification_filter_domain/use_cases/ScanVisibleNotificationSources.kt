package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class ScanVisibleNotificationSources(
    private val serviceUtil: ServiceUtil,
    private val registerKnownNotificationSources: RegisterKnownNotificationSources,
) {
    operator fun invoke(): List<VisibleNotificationSource> {
        val sources = serviceUtil.getVisibleNotificationSources()
        registerKnownNotificationSources(sources)
        return sources
    }
}
