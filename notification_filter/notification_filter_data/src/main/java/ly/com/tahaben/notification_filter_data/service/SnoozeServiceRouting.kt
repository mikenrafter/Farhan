package ly.com.tahaben.notification_filter_data.service

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryCoordinator
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeExclusionFlags
import ly.com.tahaben.notification_filter_domain.delivery.SnoozeReturn
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

/** A post's title and text as Farhan captures and stores them (same truncation), compared by exact equality. */
internal data class PostedText(val title: String?, val text: String?)

internal class SnoozeServiceRouting(
    private val coordinator: NotificationDeliveryCoordinator,
    private val handler: SnoozeHandler,
    private val find: suspend (String) -> NotificationItem?,
    private val inputAt: suspend (Instant) -> SnoozeReturnInput,
) {
    suspend fun handleStoredPost(item: NotificationItem, order: Long, now: Instant): Boolean {
        return handleStoredPost(item.id, item.timestamp, now, order, PostedText(item.title, item.text))
    }

    /**
     * True when the stored custody handled the post. A cleared row is disposed of (cancelled) only for a post with
     * the same title and text; anything else closes the custody and returns false before the order is accepted,
     * so the post takes the normal arrival path. A caller without [posted] fields fails visible.
     */
    suspend fun handleStoredPost(
        id: String,
        postTime: Long,
        now: Instant,
        order: Long? = null,
        posted: PostedText? = null,
    ): Boolean {
        val row = find(id) ?: return false
        val deadline = row.snoozeDeadline ?: return false
        if (row.isCleared) {
            // CLAIM(snooze-cleared-return-content): Android folds a follow-up into the hidden record without a callback.
            if (postTime < deadline || posted != PostedText(row.title, row.text)) {
                handler.retireClearedSnooze(id, deadline)
                return false
            }
            if (order != null) coordinator.acceptSnoozeEvent(id, order)
            return handler.disposeClearedReturn(row, deadline, postTime, now)
        }
        if (!row.hasLiveSnooze) return false
        if (order != null) coordinator.acceptSnoozeEvent(id, order)
        settle(SnoozeReturn(id, postTime), now)
        return true
    }

    suspend fun handleRemoval(
        key: String,
        reason: Int,
        at: Long,
        farhanCancelledSummary: Boolean,
        postTime: Long,
    ): Boolean = handler.onRemoved(key, reason, at, farhanCancelledSummary, postTime)

    suspend fun settle(returned: SnoozeReturn, now: Instant): Boolean =
        handler.settle(returned, inputAt(now))
}

internal suspend fun routeSnoozeAdmission(
    snoozeEnabled: Boolean,
    flags: SnoozeExclusionFlags,
    admit: suspend () -> Boolean,
    onAdmitted: () -> Unit,
): Boolean {
    if (!snoozeEnabled) return false
    if (flags.isGroupSummary) return true
    if (!admit()) return false
    onAdmitted()
    return true
}

internal suspend fun snoozeReturnFilterEnabled(preferences: Preferences): Boolean =
    preferences.isServiceEnabled()

internal fun previousSnoozeTick(now: Instant, zone: ZoneId, testingMode: Boolean): Instant {
    val interval = if (testingMode) DeliveryTimeContext.TESTING_BOUNDARY_MINUTES
        else DeliveryTimeContext.PRODUCTION_BOUNDARY_MINUTES
    val minute = now.atZone(zone).minute
    return now.truncatedTo(ChronoUnit.MINUTES).minusSeconds((minute % interval + interval) * 60L)
}
