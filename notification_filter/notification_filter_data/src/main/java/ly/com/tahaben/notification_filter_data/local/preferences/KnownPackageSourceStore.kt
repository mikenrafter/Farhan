package ly.com.tahaben.notification_filter_data.local.preferences

import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnownPackageSourceStore @Inject constructor(
    private val preferences: Preferences,
) : KnownPackageSourceRegistry {

    private val lock = Any()
    private var cache: MutableMap<String, KnownPackageSource>? = null

    override fun getAll(): Map<String, KnownPackageSource> = synchronized(lock) {
        ensureLoadedLocked()
        cache!!.toMap()
    }

    override fun register(sources: List<VisibleNotificationSource>) {
        if (sources.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            var changed = false
            sources.forEach { source ->
                val merged = mergeSource(source, cache!![source.packageName])
                if (merged != cache!![source.packageName]) {
                    cache!![source.packageName] = merged
                    changed = true
                }
            }
            if (changed) {
                persistLocked()
            }
        }
    }

    fun replaceAll(sources: Collection<KnownPackageSource>) {
        synchronized(lock) {
            cache = sources.associateBy { it.packageName }.toMutableMap()
            persistLocked()
        }
    }

    private fun ensureLoadedLocked() {
        if (cache == null) {
            cache = preferences.getKnownPackageSources().toMutableMap()
        }
    }

    private fun persistLocked() {
        preferences.setKnownPackageSources(cache!!.values.toList())
    }

    private fun mergeSource(
        source: VisibleNotificationSource,
        current: KnownPackageSource?,
    ): KnownPackageSource {
        val appName = source.appName?.takeIf { it.isNotBlank() }
            ?: current?.appName
            ?: source.packageName
        val channels = current?.channelIds.orEmpty().toMutableSet()
        source.channelId?.takeIf { it.isNotBlank() }?.let { channels.add(it) }
        val categories = current?.androidCategories.orEmpty().toMutableSet()
        source.androidCategory?.takeIf { it.isNotBlank() }?.let { categories.add(it) }
        val appCategoryCode = source.appCategoryCode ?: current?.appCategoryCode
        val appCategoryLabel = source.appCategoryLabel?.takeIf { it.isNotBlank() }
            ?: current?.appCategoryLabel
        return KnownPackageSource(
            packageName = source.packageName,
            appName = appName,
            channelIds = channels,
            androidCategories = categories,
            appCategoryCode = appCategoryCode,
            appCategoryLabel = appCategoryLabel,
        )
    }
}
