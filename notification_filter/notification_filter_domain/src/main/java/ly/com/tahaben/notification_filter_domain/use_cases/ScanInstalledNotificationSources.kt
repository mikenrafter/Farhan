package ly.com.tahaben.notification_filter_domain.use_cases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class ScanInstalledNotificationSources(
    private val getInstalledAppsList: GetInstalledAppsList,
    private val serviceUtil: ServiceUtil,
    private val registerKnownNotificationSources: RegisterKnownNotificationSources,
) {
    @Volatile
    private var lastScanAtMillis = 0L

    suspend operator fun invoke(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAtMillis < SCAN_INTERVAL_MS) return@withContext

        val ownPackageName = serviceUtil.getOwnPackageName()
        val sources = getInstalledAppsList()
            .asSequence()
            .filter { !it.isSystemApp }
            .filter { it.packageName != ownPackageName }
            .flatMap { app ->
                val packageName = app.packageName
                val appName = app.name?.takeIf { it.isNotBlank() } ?: packageName
                val appCategoryCode = app.appCategoryCode.takeIf { it != 0 }
                val appCategoryLabel = app.category?.takeIf { it.isNotBlank() }
                val channelIds = serviceUtil.getNotificationChannelsForPackage(packageName)
                if (channelIds.isEmpty()) {
                    sequenceOf(
                        VisibleNotificationSource(
                            packageName = packageName,
                            appName = appName,
                            channelId = null,
                            androidCategory = null,
                            appCategoryCode = appCategoryCode,
                            appCategoryLabel = appCategoryLabel,
                        )
                    )
                } else {
                    channelIds.asSequence().map { channelId ->
                        VisibleNotificationSource(
                            packageName = packageName,
                            appName = appName,
                            channelId = channelId,
                            androidCategory = null,
                            appCategoryCode = appCategoryCode,
                            appCategoryLabel = appCategoryLabel,
                        )
                    }
                }
            }
            .toList()
        registerKnownNotificationSources(sources)
        lastScanAtMillis = now
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
