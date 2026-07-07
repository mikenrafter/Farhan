package ly.com.tahaben.notification_filter_domain.model

data class KnownPackageSource(
    val packageName: String,
    val appName: String,
    val channelIds: Set<String> = emptySet(),
    val androidCategories: Set<String> = emptySet(),
    val appCategoryCode: Int? = null,
    val appCategoryLabel: String? = null,
)

data class VisibleNotificationSource(
    val packageName: String,
    val appName: String?,
    val channelId: String?,
    val androidCategory: String?,
    val appCategoryCode: Int? = null,
    val appCategoryLabel: String? = null,
)
