package ly.com.tahaben.notification_filter_data.sound

import android.content.Context
import ly.com.tahaben.notification_filter_domain.sound.MutePolicy
import ly.com.tahaben.notification_filter_domain.sound.ZenRule
import ly.com.tahaben.notification_filter_domain.sound.ZenRulePort

internal class AndroidZenRulePort(private val context: Context) : ZenRulePort {
    override fun add(conditionId: String, policy: MutePolicy): String = ""
    override fun update(id: String, policy: MutePolicy): Boolean = false
    override fun remove(id: String): Boolean = false
    override fun ownedRules(): List<ZenRule> = emptyList()
    override fun ruleState(id: String): ZenRule? = null
    override fun setState(id: String, active: Boolean) = Unit
    override fun consolidatedPolicy(): MutePolicy? = null
    override fun currentFilter(): Int = 0
    override fun policyAccess(): Boolean = false
}
