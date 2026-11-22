package ly.com.tahaben.notification_filter_domain.priority

import ly.com.tahaben.notification_filter_domain.delivery.Experiments
import ly.com.tahaben.notification_filter_domain.inbox.OtpCodeExtractor
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences

/** Settings of [OtpPriorityRule]. [target] is the priority a matching notification is raised to. */
data class OtpRuleConfig(
    val enabled: Boolean = false,
    val target: NotificationPriority = NotificationPriority.CRITICAL,
    val allApps: Boolean = true,
    val packages: Set<String> = emptySet(),
) {
    fun covers(packageName: String): Boolean = allApps || packageName in packages

    companion object {
        /** How long a code stays raised at the top of the Inbox. */
        const val FRESH_MILLIS = 10 * 60 * 1000L

        fun from(preferences: Preferences) = OtpRuleConfig(
            enabled = preferences.isExperimentEnabled(Experiments.OTP_CODES),
            target = preferences.getOtpPriority(),
            allApps = preferences.isOtpAllApps(),
            packages = preferences.getOtpPackages(),
        )
    }
}

/**
 * Raises a notification that carries a one-time code to [OtpRuleConfig.target] (CRITICAL by default, which is
 * pass-through, so the original stays in the shade).
 *
 * Applies when all hold: the [Experiments.OTP_CODES] switch is on, the package is in scope (all apps, or the
 * chosen set), and [OtpCodeExtractor] finds a code in the title or text.
 *
 * **User decisions win.** When the user fixed the priority ("Set by user" through an app or channel override,
 * or "Locked by user") the rule leaves that priority alone but still reports the code, so the code is raised as
 * its own notification (the app page promises codes from every covered app). A lock is defined as
 * "automated adjustments cannot change this priority", and this rule is one; the per-app choice is also more
 * specific than the rule's all-apps default. The cost is that a locked low-priority messaging app will not get
 * its codes raised. The remedy is the user's, in the same settings: unlock that app, or keep the rule to a chosen
 * set of apps. A rule that overrode the lock would make "Locked" mean "locked except for codes", which the
 * Inbox cannot show.
 *
 * The rule never lowers a priority: a target below the current priority leaves it as is, though the code is still
 * reported so it can be raised as its own notification.
 */
class OtpPriorityRule(private val config: () -> OtpRuleConfig) : PriorityRule {
    override fun evaluate(input: PriorityRuleInput): PriorityRuleMatch? {
        val settings = config()
        if (!settings.enabled || !settings.covers(input.packageName)) return null
        val code = OtpCodeExtractor.extract(input.title, input.text) ?: return null
        // A fixed priority stands, but the code is still reported so it is raised as its own notification.
        if (input.userDecided) return PriorityRuleMatch(input.priority, input.reason, code)
        return PriorityRuleMatch(settings.target, REASON, code)
    }

    companion object {
        const val REASON = "Verification code"
    }
}
