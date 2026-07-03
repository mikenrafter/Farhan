package ly.com.tahaben.notification_filter_domain.model

data class TopChatter(
    val appName: String,
    val packageName: String,
    val count: Int
)
