package ly.com.tahaben.notification_filter_presentation.apppriority

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo

data class AppPriorityState(
    val apps: List<AppPriorityInfo> = emptyList(),
    val expandedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)
