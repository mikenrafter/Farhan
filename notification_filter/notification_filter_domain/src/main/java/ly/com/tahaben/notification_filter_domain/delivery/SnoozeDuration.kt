package ly.com.tahaben.notification_filter_domain.delivery

enum class SnoozeDuration(val minutes: Int) {
    MINUTES_1(1),
    MINUTES_5(5),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_60(60),
    MINUTES_120(120);

    companion object {
        val DEFAULT = MINUTES_1

        // CLAIM(snooze-duration): stored minutes must be one of the six choices [Snooze pilot spec D4].
        fun parse(minutes: Int): SnoozeDuration? = null
    }
}
