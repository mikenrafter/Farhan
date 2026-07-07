package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationCategoryResolver
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

class ClassifyNotification(private val preferences: Preferences) {

    operator fun invoke(
        androidCategory: String?,
        packageName: String,
        channelId: String? = null,
        appCategoryCode: Int? = null,
        knownAndroidCategories: Set<String> = emptySet(),
    ): Pair<NotificationPriority, NotificationCategory> {
        val category = NotificationCategoryResolver.resolve(
            primaryAndroidCategory = androidCategory,
            knownAndroidCategories = knownAndroidCategories,
            appCategoryCode = appCategoryCode,
        )
        return resolvePriority(
            packageName = packageName,
            channelId = channelId,
            category = category,
        )
    }

    fun classifyForApp(
        packageName: String,
        androidCategories: Set<String>,
        appCategoryCode: Int?,
    ): Pair<NotificationPriority, NotificationCategory> {
        val category = NotificationCategoryResolver.resolve(
            primaryAndroidCategory = null,
            knownAndroidCategories = androidCategories,
            appCategoryCode = appCategoryCode,
        )
        return resolvePriority(
            packageName = packageName,
            channelId = null,
            category = category,
        )
    }

    private fun resolvePriority(
        packageName: String,
        channelId: String?,
        category: NotificationCategory,
    ): Pair<NotificationPriority, NotificationCategory> {
        if (channelId != null) {
            val channelOverride = preferences.getChannelPriorityOverride(packageName, channelId)
            if (channelOverride != null) {
                return Pair(channelOverride, category)
            }
        }

        val isLocked = preferences.isAppPriorityLocked(packageName)
        val priorityOverride = preferences.getAppPriorityOverride(packageName)

        if (isLocked) {
            val lockedPriority = priorityOverride
                ?: (preferences.getCategoryPriorityOverride(category) ?: category.defaultPriority)
            return Pair(lockedPriority, category)
        }

        if (priorityOverride != null) {
            return Pair(priorityOverride, category)
        }

        val categoryPriority = preferences.getCategoryPriorityOverride(category) ?: category.defaultPriority
        return Pair(categoryPriority, category)
    }
}
