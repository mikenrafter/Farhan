package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry

class RegisterKnownNotificationSources(
    private val registry: KnownPackageSourceRegistry,
) {

    operator fun invoke(sources: List<VisibleNotificationSource>) {
        registry.register(sources)
    }
}
