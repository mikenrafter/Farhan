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
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val ownPackageName = serviceUtil.getOwnPackageName()
        val sources = getInstalledAppsList()
            .asSequence()
            .filter { !it.isSystemApp }
            .filter { it.packageName != ownPackageName }
            .flatMap { app ->
                val packageName = app.packageName
                val appName = app.name?.takeIf { it.isNotBlank() } ?: packageName
                val channelIds = serviceUtil.getNotificationChannelsForPackage(packageName)
                if (channelIds.isEmpty()) {
                    sequenceOf(
                        VisibleNotificationSource(
                            packageName = packageName,
                            appName = appName,
                            channelId = null,
                            androidCategory = null,
                        )
                    )
                } else {
                    channelIds.asSequence().map { channelId ->
                        VisibleNotificationSource(
                            packageName = packageName,
                            appName = appName,
                            channelId = channelId,
                            androidCategory = null,
                        )
                    }
                }
            }
            .toList()
        registerKnownNotificationSources(sources)
    }
}
