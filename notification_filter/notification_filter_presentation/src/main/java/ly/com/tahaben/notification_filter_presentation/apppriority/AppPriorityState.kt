package ly.com.tahaben.notification_filter_presentation.apppriority

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo

data class AppPriorityState(
    val apps: List<AppPriorityInfo> = emptyList(),
    val isLoading: Boolean = true,
)
