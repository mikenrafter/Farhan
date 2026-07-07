package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.AppStoreCategory
import ly.com.tahaben.notification_filter_domain.model.ChannelPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.NotificationCategoryResolver
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil

class GetAppPriorityInfoList(
    private val preferences: Preferences,
    private val knownPackageSourceRegistry: KnownPackageSourceRegistry,
    private val classifyNotification: ClassifyNotification,
    private val scanInstalledNotificationSources: ScanInstalledNotificationSources,
    private val scanVisibleNotificationSources: ScanVisibleNotificationSources,
    private val serviceUtil: ServiceUtil,
) {
    suspend operator fun invoke(forceRescan: Boolean = false): List<AppPriorityInfo> {
        scanInstalledNotificationSources(force = forceRescan)
        scanVisibleNotificationSources()
        return buildList()
    }

    fun forPackage(packageName: String): AppPriorityInfo? {
        val source = knownPackageSourceRegistry.getAll()[packageName] ?: return null
        return buildInfo(source)
    }

    private fun buildList(): List<AppPriorityInfo> {
        val knownPackages = knownPackageSourceRegistry.getAll().toMutableMap()

        preferences.getNotificationFilterExceptionsList().forEach { packageName ->
            if (packageName !in knownPackages) {
                knownPackages[packageName] = KnownPackageSource(
                    packageName = packageName,
                    appName = packageName,
                )
            }
        }

        return knownPackages.values
            .map(::buildInfo)
            .sortedWith(
                compareByDescending<AppPriorityInfo> { it.notificationsEnabled }
                    .thenBy { it.appName.lowercase() }
            )
    }

    private fun buildInfo(source: KnownPackageSource): AppPriorityInfo {
        val userOverride = preferences.getAppPriorityOverride(source.packageName)
        val appCategoryCode = source.appCategoryCode?.takeIf { it != AppStoreCategory.UNDEFINED }
        val appCategoryLabel = source.appCategoryLabel?.takeIf { it.isNotBlank() }
        val (defaultPriority, resolvedCategory) = classifyNotification.classifyForApp(
            packageName = source.packageName,
            androidCategories = source.androidCategories,
            appCategoryCode = appCategoryCode,
        )
        val currentPriority = userOverride ?: defaultPriority
        val notificationCategoryLabels = source.androidCategories
            .sorted()
            .map(NotificationCategoryResolver::androidNotificationCategoryLabel)
        val priorityReason = when {
            userOverride != null -> "Set by user"
            source.androidCategories.isNotEmpty() ->
                "Notification category default (${resolvedCategory.name.lowercase().replaceFirstChar { it.uppercase() }})"
            appCategoryLabel != null ->
                "App category default ($appCategoryLabel → ${resolvedCategory.name.lowercase().replaceFirstChar { it.uppercase() }})"
            else -> "Default priority (${resolvedCategory.name.lowercase().replaceFirstChar { it.uppercase() }})"
        }

        val channels = source.channelIds.sorted().map { channelId ->
            ChannelPriorityInfo(
                channelId = channelId,
                override = preferences.getChannelPriorityOverride(source.packageName, channelId),
            )
        }

        return AppPriorityInfo(
            packageName = source.packageName,
            appName = source.appName,
            currentPriority = currentPriority,
            defaultPriority = defaultPriority,
            resolvedCategory = resolvedCategory,
            priorityReason = priorityReason,
            appCategoryLabel = appCategoryLabel,
            androidNotificationCategoryLabels = notificationCategoryLabels,
            isLocked = preferences.isAppPriorityLocked(source.packageName),
            userOverride = userOverride,
            channels = channels,
            notificationsEnabled = serviceUtil.areAppNotificationsEnabled(source.packageName),
        )
    }
}
