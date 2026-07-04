package ly.com.tahaben.notification_filter_domain.model

enum class DeliveryCadence(val label: String) {
    IMMEDIATELY("Immediately"),
    EVERY_HOUR("Every hour"),
    EVERY_OTHER_HOUR("Every other hour"),
    EVERY_THIRD_HOUR("Every third hour"),
    ONCE_DAILY("Once daily"),
}
