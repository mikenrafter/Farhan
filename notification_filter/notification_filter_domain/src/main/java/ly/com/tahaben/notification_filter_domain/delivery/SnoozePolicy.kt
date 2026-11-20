package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Clock
import java.time.Duration
import java.time.Instant
import ly.com.tahaben.notification_filter_domain.model.BudgetBehavior
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import java.time.ZoneId
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
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
    val isPassthrough: Boolean,
    val isExceptedPackage: Boolean,
    val priority: NotificationPriority,
    val exclusionFlags: SnoozeExclusionFlags,
    val scheduleAt: (Instant, ZoneId) -> List<NotificationSchedulePeriod>,
    val alreadyDelivered: Boolean,
    val liveSnoozes: Int,
    val budget: DeliveryBudgetState = DeliveryBudgetState(),
    val ownedRows: List<NotificationItem> = emptyList(),
    val groupSnoozeVerified: Boolean = false,
)

data class SnoozeExclusionFlags(
    val isSystemExemptCategory: Boolean,
    val isOngoing: Boolean,
    val isForegroundService: Boolean,
    val isNonClearable: Boolean,
    val isGroupSummary: Boolean,
    val hasGroupKey: Boolean,
) {
    companion object {
        // CLAIM(snooze-raw-flags): map Android flags, category, and grouping at intake [Snooze pilot spec V7].
        fun fromNotification(flags: Int, category: String?, groupKey: String?): SnoozeExclusionFlags =
            SnoozeExclusionFlags(
                isSystemExemptCategory = category in exemptCategories,
                isOngoing = flags and 0x2 != 0,
                isForegroundService = flags and 0x40 != 0,
                isNonClearable = flags and 0x20 != 0,
                isGroupSummary = flags and 0x200 != 0,
                hasGroupKey = groupKey != null,
            )

        private val exemptCategories = setOf("service", "call", "alarm", "missed_call", "sys")
    }
}

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
    GROUP_KEY,
    ALREADY_DELIVERED,
    AT_CAPACITY,
    NO_FUNDED_MOMENT,
    NO_CAPACITY,
}

object SnoozePolicy {
    // CLAIM(snooze-exclusions): every exclusion skips native snooze [Snooze pilot spec D2, I1, I5].
    fun decide(input: SnoozeInput): SnoozeDecision {
        val exclusion = when {
            !input.snoozeEnabled -> SnoozeSkipReason.SNOOZE_OFF
            !input.filterEnabled -> SnoozeSkipReason.FILTER_OFF
            !input.platformSupportsSnooze -> SnoozeSkipReason.PLATFORM_TOO_OLD
            input.packageName == input.ownPackageName -> SnoozeSkipReason.OWN_NOTIFICATION
            input.isExceptedPackage -> SnoozeSkipReason.PACKAGE_EXCEPTION
            input.priority == NotificationPriority.CRITICAL -> SnoozeSkipReason.CRITICAL
            input.setting.cadence == DeliveryCadence.IMMEDIATELY -> SnoozeSkipReason.IMMEDIATE_CADENCE
            input.isPassthrough -> SnoozeSkipReason.PASSTHROUGH
            input.exclusionFlags.isSystemExemptCategory -> SnoozeSkipReason.SYSTEM_EXEMPT_CATEGORY
            input.exclusionFlags.isOngoing -> SnoozeSkipReason.ONGOING
            input.exclusionFlags.isForegroundService -> SnoozeSkipReason.FOREGROUND_SERVICE
            input.exclusionFlags.isNonClearable -> SnoozeSkipReason.NON_CLEARABLE
            input.exclusionFlags.isGroupSummary -> SnoozeSkipReason.GROUP_SUMMARY
            input.exclusionFlags.hasGroupKey && !input.groupSnoozeVerified -> SnoozeSkipReason.GROUP_KEY
            input.alreadyDelivered -> SnoozeSkipReason.ALREADY_DELIVERED
            input.liveSnoozes >= 400 -> SnoozeSkipReason.AT_CAPACITY
            else -> null
        }
        if (exclusion != null) return SnoozeDecision.Skip(exclusion)

        val tick = SnoozeTiming.nextDeliverableMoment(
            input.priority, input.setting, input.scheduleAt, input.now, input.zone, input.testingMode,
        ) ?: return SnoozeDecision.Skip(SnoozeSkipReason.NO_FUNDED_MOMENT)
        if (!hasSnoozeCapacity(input.priority, input.setting, input.budget, input.ownedRows, tick, input.zone,
                input.testingMode, input.scheduleAt(tick, input.zone))) {
            return SnoozeDecision.Skip(SnoozeSkipReason.NO_CAPACITY)
        }
        return SnoozeDecision.Snooze(Duration.between(input.now, tick).toMillis().coerceAtLeast(1))
    }
}


// CLAIM(snooze-capacity): reservations share period points and the target hourly window [Snooze pilot spec W4].
internal fun hasSnoozeCapacity(
    priority: NotificationPriority,
    setting: PriorityDeliverySetting,
    budget: DeliveryBudgetState,
    ownedRows: List<NotificationItem>,
    tick: Instant,
    zone: ZoneId,
    testingMode: Boolean,
    schedule: List<NotificationSchedulePeriod>,
): Boolean {
    if (setting.budgetBehavior == BudgetBehavior.IGNORING) return true
    val target = DeliveryClock.current(testingMode, Clock.fixed(tick, zone))
    val period = NotificationDeliveryPolicy.findActivePeriod(schedule, target.scheduleMinuteOfDay) ?: return false
    val currentDay = budget.localDay.isEmpty() || budget.localDay == target.localDay
    var points = if (currentDay && budget.periodStartMinute == period.startMinuteOfDay) budget.spentPoints.toLong() else 0L
    var count = if (currentDay && budget.passthroughWindowHour == target.passthroughWindowKey) budget.hourlyCount else 0
    for (row in ownedRows) {
        val due = row.snoozeDeadline ?: continue
        val storedPriority = row.snoozePriority ?: continue
        if (row.isDelivered || row.isCleared) continue
        val time = DeliveryClock.current(testingMode, Clock.fixed(Instant.ofEpochMilli(due), zone))
        if (time.localDay != target.localDay) continue
        if (time.scheduleMinuteOfDay in period.startMinuteOfDay until period.endMinuteOfDay) points += storedPriority.pointCost
        if (time.passthroughWindowKey == target.passthroughWindowKey) count++
    }
    return points + priority.pointCost <= period.budgetPoints &&
        count < NotificationDeliveryPolicy.MAX_HOURLY_PASSTHROUGH
}
