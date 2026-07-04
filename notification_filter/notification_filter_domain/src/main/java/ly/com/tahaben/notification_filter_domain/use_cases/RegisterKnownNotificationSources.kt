package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class RegisterKnownNotificationSources(private val preferences: Preferences) {

    operator fun invoke(sources: List<VisibleNotificationSource>) {
        if (sources.isEmpty()) return
        val existing = preferences.getKnownPackageSources().toMutableMap()
        sources.forEach { source ->
            val current = existing[source.packageName]
            val appName = source.appName?.takeIf { it.isNotBlank() }
                ?: current?.appName
                ?: source.packageName
            val channels = current?.channelIds.orEmpty().toMutableSet()
            source.channelId?.takeIf { it.isNotBlank() }?.let { channels.add(it) }
            val categories = current?.androidCategories.orEmpty().toMutableSet()
            source.androidCategory?.takeIf { it.isNotBlank() }?.let { categories.add(it) }
            existing[source.packageName] = KnownPackageSource(
                packageName = source.packageName,
                appName = appName,
                channelIds = channels,
                androidCategories = categories,
            )
        }
        preferences.setKnownPackageSources(existing.values.toList())
    }
}
