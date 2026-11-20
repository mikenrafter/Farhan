package ly.com.tahaben.notification_filter_domain.delivery

enum class SnoozeFallbackReason {
    SAME_BOUNDARY,
    NATIVE_FAILURE,
    INVALID_RETRY,
    NO_FUNDED_MOMENT,
    DAILY_LIMIT,
    NOT_A_RETURN,
    BEFORE_DEADLINE,
    SNOOZE_OFF,
    UNCONFIRMED,
}
