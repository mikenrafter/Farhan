package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Instant
import java.time.ZoneId
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting

data class SnoozeInput(
    val packageName: String,
    val ownPackageName: String,
    val snoozeEnabled: Boolean,
    val filterEnabled: Boolean,
    val platformSupportsSnooze: Boolean,
    val setting: PriorityDeliverySetting,
    val now: Instant,
    val zone: ZoneId,
    val testingMode: Boolean = false,
    val isPassthrough: Boolean = false,
    val isExceptedPackage: Boolean = false,
    val priority: NotificationPriority = NotificationPriority.MEDIUM,
    val isSystemExemptCategory: Boolean = false,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isNonClearable: Boolean = false,
    val isGroupSummary: Boolean = false,
)

sealed interface SnoozeDecision {
    data class Snooze(val durationMillis: Long) : SnoozeDecision
    data class Skip(val reason: SnoozeSkipReason) : SnoozeDecision
}

enum class SnoozeSkipReason {
    SNOOZE_OFF,
    FILTER_OFF,
    PLATFORM_TOO_OLD,
    OWN_NOTIFICATION,
    PACKAGE_EXCEPTION,
    CRITICAL,
    IMMEDIATE_CADENCE,
    PASSTHROUGH,
    SYSTEM_EXEMPT_CATEGORY,
    ONGOING,
    FOREGROUND_SERVICE,
    NON_CLEARABLE,
    GROUP_SUMMARY,
}

object SnoozePolicy {
    // CLAIM(snooze-exclusions): every exclusion skips native snooze [Snooze pilot spec D2, I1, I5].
    fun decide(input: SnoozeInput): SnoozeDecision = SnoozeDecision.Snooze(0)
}
