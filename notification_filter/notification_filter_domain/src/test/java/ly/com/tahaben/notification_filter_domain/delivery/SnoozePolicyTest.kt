package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import java.time.ZoneOffset
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.model.DeliveryCadence
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import org.junit.Test

class SnoozePolicyTest {
    private val eligible = SnoozeInput(
        packageName = "com.whatsapp",
        ownPackageName = "ly.com.tahaben.farhan_alpha",
        snoozeEnabled = true,
        filterEnabled = true,
        platformSupportsSnooze = true,
        priority = NotificationPriority.HIGH,
        setting = PriorityDeliverySetting.defaultFor(NotificationPriority.HIGH),
        now = Instant.parse("2026-11-20T08:55:00Z"),
        zone = ZoneOffset.UTC,
        isPassthrough = false,
        isExceptedPackage = false,
        exclusionFlags = SnoozeExclusionFlags.fromNotification(0, null, null),
        scheduleAt = { _, _ -> NotificationSchedulePeriod.DEFAULT_SCHEDULE },
        alreadyDelivered = false,
        liveSnoozes = 0,
    )

    private val exclusions = mapOf(
        SnoozeSkipReason.SNOOZE_OFF to eligible.copy(snoozeEnabled = false),
        SnoozeSkipReason.FILTER_OFF to eligible.copy(filterEnabled = false),
        SnoozeSkipReason.PLATFORM_TOO_OLD to eligible.copy(platformSupportsSnooze = false),
        SnoozeSkipReason.OWN_NOTIFICATION to eligible.copy(packageName = "ly.com.tahaben.farhan_alpha"),
        SnoozeSkipReason.PACKAGE_EXCEPTION to eligible.copy(isExceptedPackage = true),
        SnoozeSkipReason.CRITICAL to eligible.copy(priority = NotificationPriority.CRITICAL),
        SnoozeSkipReason.IMMEDIATE_CADENCE to eligible.copy(
            setting = eligible.setting.copy(cadence = DeliveryCadence.IMMEDIATELY),
        ),
        SnoozeSkipReason.PASSTHROUGH to eligible.copy(isPassthrough = true),
        SnoozeSkipReason.SYSTEM_EXEMPT_CATEGORY to eligible.copy(exclusionFlags = eligible.exclusionFlags.copy(isSystemExemptCategory = true)),
        SnoozeSkipReason.ONGOING to eligible.copy(exclusionFlags = eligible.exclusionFlags.copy(isOngoing = true)),
        SnoozeSkipReason.FOREGROUND_SERVICE to eligible.copy(exclusionFlags = eligible.exclusionFlags.copy(isForegroundService = true)),
        SnoozeSkipReason.NON_CLEARABLE to eligible.copy(exclusionFlags = eligible.exclusionFlags.copy(isNonClearable = true)),
        SnoozeSkipReason.GROUP_SUMMARY to eligible.copy(exclusionFlags = eligible.exclusionFlags.copy(isGroupSummary = true)),
        SnoozeSkipReason.GROUP_KEY to eligible.copy(
            exclusionFlags = SnoozeExclusionFlags.fromNotification(0, null, "linked-group"),
        ),
        SnoozeSkipReason.ALREADY_DELIVERED to eligible.copy(alreadyDelivered = true),
        SnoozeSkipReason.AT_CAPACITY to eligible.copy(liveSnoozes = 400),
        SnoozeSkipReason.NO_FUNDED_MOMENT to eligible.copy(
            priority = NotificationPriority.LOW,
            setting = PriorityDeliverySetting.defaultFor(NotificationPriority.LOW),
        ),
    )

    // why: the policy must skip when snooze is off, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the opt-in gate is missing.
    @Test
    fun `skip when snooze is off`() = assertSkip(SnoozeSkipReason.SNOOZE_OFF)

    // why: the policy must skip when the filter is off, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: snooze runs with the filter disabled or listener access revoked.
    @Test
    fun `skip when the filter is off`() = assertSkip(SnoozeSkipReason.FILTER_OFF)

    // why: the policy must skip when timed snooze is unavailable, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy assumes all supported Android releases implement timed snooze.
    @Test
    fun `skip when timed snooze is unavailable`() = assertSkip(SnoozeSkipReason.PLATFORM_TOO_OLD)

    // why: the policy must skip when Farhan posts the notification, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the service snoozes its own notification.
    @Test
    fun `skip when Farhan posts the notification`() = assertSkip(SnoozeSkipReason.OWN_NOTIFICATION)

    // why: the policy must skip when the app is in the owner's exceptions, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy only checks the priority.
    @Test
    fun `skip when the app is in the owner's exceptions`() = assertSkip(SnoozeSkipReason.PACKAGE_EXCEPTION)

    // why: the policy must skip when the notification is critical, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: an ordinary category makes the policy ignore critical priority.
    @Test
    fun `skip when the notification is critical`() = assertSkip(SnoozeSkipReason.CRITICAL)

    // why: a HIGH priority can use immediate cadence and must go straight through just like CRITICAL.
    // proves: CLAIM(snooze-exclusions)
    // catches: checking only CRITICAL and ignoring owner-configured immediate cadence.
    @Test
    fun `skip when high priority uses immediate cadence`() = assertSkip(SnoozeSkipReason.IMMEDIATE_CADENCE)

    // why: intake already allows passthrough notifications through the normal path (D7).
    // proves: CLAIM(snooze-exclusions)
    // catches: snoozing a non-critical notification that intake marked passthrough.
    @Test
    fun `skip when intake marks the notification passthrough`() = assertSkip(SnoozeSkipReason.PASSTHROUGH)

    // why: the policy must skip when the category is system exempt, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: an alarm with medium priority is snoozed.
    @Test
    fun `skip when the category is system exempt`() = assertSkip(SnoozeSkipReason.SYSTEM_EXEMPT_CATEGORY)

    // why: the policy must skip when the notification is ongoing, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy checks foreground-service flags but ignores ongoing.
    @Test
    fun `skip when the notification is ongoing`() = assertSkip(SnoozeSkipReason.ONGOING)

    // why: the policy must skip when a foreground service posts the notification, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy only checks ongoing and assumes all services set it.
    @Test
    fun `skip when a foreground service posts the notification`() = assertSkip(SnoozeSkipReason.FOREGROUND_SERVICE)

    // why: the policy must skip when the notification cannot be cleared, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy assumes a non-ongoing notification is clearable.
    @Test
    fun `skip when the notification cannot be cleared`() = assertSkip(SnoozeSkipReason.NON_CLEARABLE)

    // why: the policy must skip when the notification is a group summary, even with all other inputs eligible.
    // proves: CLAIM(snooze-exclusions)
    // catches: the policy treats a summary as an ordinary child.
    @Test
    fun `skip when the notification is a group summary`() = assertSkip(SnoozeSkipReason.GROUP_SUMMARY)

    // why: a child with a group key can be lost if Android drops the summary.
    // catches: checking only the group summary flag.
    @Test
    fun `skip a grouped child without a summary flag`() = assertSkip(SnoozeSkipReason.GROUP_KEY)

    // why: an update to a delivered proxy row must keep its ordinary update path.
    // catches: claiming a row already delivered through the proxy.
    @Test
    fun `skip an already delivered row`() = assertSkip(SnoozeSkipReason.ALREADY_DELIVERED)

    // why: 400 owned snoozes is the pilot limit and the prior slot remains usable.
    // catches: counting the boundary incorrectly or applying the limit one slot too early.
    @Test
    fun `skip at four hundred owned snoozes`() {
        assertSkip(SnoozeSkipReason.AT_CAPACITY)
        assertThat(SnoozePolicy.decide(eligible.copy(liveSnoozes = 399))).isEqualTo(SnoozeDecision.Snooze(300_000L))
    }

    // why: LOW costs 8 and its midnight tick has only 2 points under the shipped schedule.
    // catches: delaying LOW to an unfunded tick or reusing a non-cadence period start.
    @Test
    fun `default low cadence skips when no funded moment exists`() = assertSkip(SnoozeSkipReason.NO_FUNDED_MOMENT)

    // why: D1 makes both an arbitrary app and a package that resembles a former fixture eligible.
    // catches: keeping the four-package allowlist or substituting a package-prefix rule.
    @Test
    fun `eligible packages snooze until the next cadence tick`() {
        for (packageName in listOf("com.whatsapp", "ly.com.tahaben.test.messenger.evil")) {
            assertWithMessage(packageName)
                .that(SnoozePolicy.decide(eligible.copy(packageName = packageName)))
                .isEqualTo(SnoozeDecision.Snooze(300_000L)) // 08:55 to the 09:00 hourly tick
        }
    }

    // why: every skip reason has a reachable input and none of those inputs may request native snooze.
    // proves: CLAIM(snooze-exclusions)
    // catches: an untested exclusion, a catch-all skip reason, or snoozing an excluded input.
    @Test
    fun `every exclusion has an input that produces its skip reason`() {
        assertThat(exclusions.keys).containsExactlyElementsIn(SnoozeSkipReason.entries)
        exclusions.keys.forEach(::assertSkip)
    }

    private fun assertSkip(reason: SnoozeSkipReason) {
        assertWithMessage("exclusion %s", reason)
            .that(SnoozePolicy.decide(exclusions.getValue(reason)))
            .isEqualTo(SnoozeDecision.Skip(reason))
    }
}
