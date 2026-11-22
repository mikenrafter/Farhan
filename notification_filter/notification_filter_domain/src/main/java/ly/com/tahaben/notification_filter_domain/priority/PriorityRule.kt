package ly.com.tahaben.notification_filter_domain.priority

import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

/** What a [PriorityRule] sees: the notification's text and where intake's own resolution landed. */
data class PriorityRuleInput(
    val packageName: String,
    val title: String?,
    val text: String?,
    /** Priority after the category default and the flood / chatter adjustments. */
    val priority: NotificationPriority,
    val reason: String,
    /**
     * True when the user fixed this notification's priority: an app override, a channel override or a lock
     * ("Set by user" / "Locked by user"). See [OtpPriorityRule] for why rules stand down for it.
     */
    val userDecided: Boolean,
)

/**
 * A rule's wish: raise to [priority] and say why. [verificationCode] is set by a rule that found a one-time code
 * so the caller can raise the code as its own notification.
 */
data class PriorityRuleMatch(
    val priority: NotificationPriority,
    val reason: String,
    val verificationCode: String? = null,
)

/** One condition that can lift a notification's priority. Pure: it reads [PriorityRuleInput] and its own config. */
fun interface PriorityRule {
    /** Null when the rule does not apply. */
    fun evaluate(input: PriorityRuleInput): PriorityRuleMatch?
}

/** The outcome of running the rules over a notification. */
data class PriorityResolution(
    val priority: NotificationPriority,
    val reason: String,
    /** The first code any matching rule found, whether or not it changed the priority. */
    val verificationCode: String? = null,
)

object PriorityRules {
    /**
     * Rules run in order. A rule can only raise priority: the first match that is strictly higher than what the
     * notification already has sets priority and reason; a later rule can still raise it further. A match that
     * would not raise it leaves priority and reason alone but still reports its code.
     */
    fun resolve(rules: List<PriorityRule>, input: PriorityRuleInput): PriorityResolution {
        var priority = input.priority
        var reason = input.reason
        var code: String? = null
        for (rule in rules) {
            val match = rule.evaluate(input.copy(priority = priority, reason = reason)) ?: continue
            if (match.priority.level < priority.level) {
                priority = match.priority
                reason = match.reason
            }
            if (code == null) code = match.verificationCode
        }
        return PriorityResolution(priority, reason, code)
    }
}
