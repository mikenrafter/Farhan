package ly.com.tahaben.notification_filter_data.sound

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import ly.com.tahaben.notification_filter_data.R
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import ly.com.tahaben.notification_filter_domain.sound.AccessLost
import ly.com.tahaben.notification_filter_domain.sound.CallAudience
import ly.com.tahaben.notification_filter_domain.sound.MutePolicy
import ly.com.tahaben.notification_filter_domain.sound.ZenRule
import ly.com.tahaben.notification_filter_domain.sound.ZenRulePort

internal class AndroidZenRulePort(private val context: Context) : ZenRulePort {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val condition = Uri.parse(soundControlConditionId(context.packageName))

    override fun add(conditionId: String, policy: MutePolicy): String = withPolicyAccess {
        require(conditionId == condition.toString())
        manager.addAutomaticZenRule(mode(context.getString(R.string.sound_control_mode), policy)).orEmpty()
    }

    override fun update(id: String, policy: MutePolicy): Boolean = withPolicyAccess {
        val rule = managedRule(id) ?: return@withPolicyAccess false
        manager.updateAutomaticZenRule(id, mode(rule.name, policy))
    }

    private fun mode(name: String, policy: MutePolicy): AutomaticZenRule = AutomaticZenRule(
        name, null, ComponentName(context, SoundControlModeActivity::class.java),
        condition, policy.toAndroidPolicy(), NotificationManager.INTERRUPTION_FILTER_PRIORITY, true,
    )

    override fun remove(id: String): Boolean = withPolicyAccess {
        managedRule(id) != null && manager.removeAutomaticZenRule(id)
    }

    override fun ownedRules(): List<ZenRule> {
        if (Build.VERSION.SDK_INT < 35) return emptyList()
        return withPolicyAccess {
            manager.automaticZenRules.mapNotNull { (id, rule) ->
                if (owns(rule)) snapshot(id, rule) else null
            }
        }
    }

    override fun ruleState(id: String): ZenRule? = withPolicyAccess {
        managedRule(id)?.let { snapshot(id, it) }
    }

    override fun setState(id: String, active: Boolean) = withPolicyAccess {
        require(managedRule(id) != null)
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        manager.setAutomaticZenRuleState(id, Condition(condition, context.getString(R.string.sound_control_mode), state))
    }

    override fun consolidatedPolicy(): MutePolicy? = if (Build.VERSION.SDK_INT < 35) null else withPolicyAccess {
        val policy = manager.consolidatedNotificationPolicy
        fun allows(category: Int) = policy.priorityCategories and category != 0
        fun shows(effect: Int) = policy.suppressedVisualEffects and effect == 0
        MutePolicy(
            calls = when {
                !allows(Policy.PRIORITY_CATEGORY_CALLS) -> CallAudience.NONE
                policy.priorityCallSenders == Policy.PRIORITY_SENDERS_CONTACTS -> CallAudience.CONTACTS
                policy.priorityCallSenders == Policy.PRIORITY_SENDERS_STARRED -> CallAudience.STARRED
                else -> CallAudience.ANY
            },
            repeatCallers = allows(Policy.PRIORITY_CATEGORY_REPEAT_CALLERS),
            alarms = allows(Policy.PRIORITY_CATEGORY_ALARMS),
            media = allows(Policy.PRIORITY_CATEGORY_MEDIA),
            systemSounds = allows(Policy.PRIORITY_CATEGORY_SYSTEM),
            priorityChannels = null,
            messages = allows(Policy.PRIORITY_CATEGORY_MESSAGES),
            conversations = allows(Policy.PRIORITY_CATEGORY_CONVERSATIONS),
            reminders = allows(Policy.PRIORITY_CATEGORY_REMINDERS),
            events = allows(Policy.PRIORITY_CATEGORY_EVENTS),
            showFullScreenIntent = shows(Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT),
            showLights = shows(Policy.SUPPRESSED_EFFECT_LIGHTS),
            showPeek = shows(Policy.SUPPRESSED_EFFECT_PEEK),
            showStatusBar = shows(Policy.SUPPRESSED_EFFECT_STATUS_BAR),
            showBadge = shows(Policy.SUPPRESSED_EFFECT_BADGE),
            showAmbient = shows(Policy.SUPPRESSED_EFFECT_AMBIENT),
            showNotificationList = shows(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST),
        )
    }

    override fun currentFilter() = manager.currentInterruptionFilter
    override fun policyAccess() = Build.VERSION.SDK_INT >= 35 && manager.isNotificationPolicyAccessGranted

    private fun managedRule(id: String): AutomaticZenRule? =
        manager.getAutomaticZenRule(id)?.takeIf(::owns)

    private fun owns(rule: AutomaticZenRule): Boolean =
        (rule.owner?.packageName ?: rule.configurationActivity?.packageName) == context.packageName &&
            rule.conditionId == condition

    private fun snapshot(id: String, rule: AutomaticZenRule): ZenRule = ZenRule(
        id, context.packageName, condition.toString(),
        rule.isEnabled && manager.getAutomaticZenRuleState(id) == Condition.STATE_TRUE,
    )

    private inline fun <T> withPolicyAccess(action: () -> T): T {
        if (!policyAccess()) throw AccessLost()
        return try {
            action()
        } catch (_: SecurityException) {
            throw AccessLost()
        }
    }
}

private fun MutePolicy.toAndroidPolicy(): ZenPolicy = ZenPolicy.Builder()
    .allowCalls(when (calls) {
        CallAudience.ANY -> ZenPolicy.PEOPLE_TYPE_ANYONE
        CallAudience.CONTACTS -> ZenPolicy.PEOPLE_TYPE_CONTACTS
        CallAudience.STARRED -> ZenPolicy.PEOPLE_TYPE_STARRED
        CallAudience.NONE -> ZenPolicy.PEOPLE_TYPE_NONE
    })
    .allowRepeatCallers(repeatCallers)
    .allowAlarms(alarms)
    .allowMedia(media)
    .allowSystem(systemSounds)
    .allowPriorityChannels(requireNotNull(priorityChannels))
    .allowMessages(if (messages) ZenPolicy.PEOPLE_TYPE_ANYONE else ZenPolicy.PEOPLE_TYPE_NONE)
    .allowConversations(if (conversations) ZenPolicy.CONVERSATION_SENDERS_ANYONE else ZenPolicy.CONVERSATION_SENDERS_NONE)
    .allowReminders(reminders)
    .allowEvents(events)
    .showFullScreenIntent(showFullScreenIntent)
    .showLights(showLights)
    .showPeeking(showPeek)
    .showStatusBarIcons(showStatusBar)
    .showBadges(showBadge)
    .showInAmbientDisplay(showAmbient)
    .showInNotificationList(showNotificationList)
    .build()
