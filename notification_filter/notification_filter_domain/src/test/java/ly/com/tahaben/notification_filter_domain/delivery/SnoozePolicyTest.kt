package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import org.junit.Test

class SnoozePolicyTest {
    private val eligible = SnoozeInput(
        packageName = "com.whatsapp",
        ownPackageName = "ly.com.tahaben.farhan_alpha",
        snoozeEnabled = true,
        filterEnabled = true,
        platformSupportsSnooze = true,
        duration = SnoozeDuration.MINUTES_5,
    )

    private val exclusions = mapOf(
        SnoozeSkipReason.SNOOZE_OFF to eligible.copy(snoozeEnabled = false),
        SnoozeSkipReason.FILTER_OFF to eligible.copy(filterEnabled = false),
        SnoozeSkipReason.PLATFORM_TOO_OLD to eligible.copy(platformSupportsSnooze = false),
        SnoozeSkipReason.OWN_NOTIFICATION to eligible.copy(packageName = "ly.com.tahaben.farhan_alpha"),
        SnoozeSkipReason.PACKAGE_EXCEPTION to eligible.copy(isExceptedPackage = true),
        SnoozeSkipReason.CRITICAL to eligible.copy(priority = NotificationPriority.CRITICAL),
        SnoozeSkipReason.SYSTEM_EXEMPT_CATEGORY to eligible.copy(isSystemExemptCategory = true),
        SnoozeSkipReason.ONGOING to eligible.copy(isOngoing = true),
        SnoozeSkipReason.FOREGROUND_SERVICE to eligible.copy(isForegroundService = true),
        SnoozeSkipReason.NON_CLEARABLE to eligible.copy(isNonClearable = true),
        SnoozeSkipReason.GROUP_SUMMARY to eligible.copy(isGroupSummary = true),
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

    // why: D1 makes both an arbitrary app and a package that resembles a former fixture eligible.
    // catches: keeping the four-package allowlist or substituting a package-prefix rule.
    @Test
    fun `eligible packages snooze for the selected duration`() {
        for (packageName in listOf("com.whatsapp", "ly.com.tahaben.test.messenger.evil")) {
            assertWithMessage(packageName)
                .that(SnoozePolicy.decide(eligible.copy(packageName = packageName)))
                .isEqualTo(SnoozeDecision.Snooze(300_000L)) // 5 × 60 × 1000
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
