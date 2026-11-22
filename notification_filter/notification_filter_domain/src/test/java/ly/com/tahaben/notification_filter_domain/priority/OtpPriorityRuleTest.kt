package ly.com.tahaben.notification_filter_domain.priority

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.CRITICAL
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.HIGH
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.LOW
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority.MEDIUM
import org.junit.Test

class OtpPriorityRuleTest {
    private val on = OtpRuleConfig(enabled = true)

    private fun input(
        text: String = "Your verification code is 482913",
        pkg: String = "com.bank",
        priority: NotificationPriority = LOW,
        reason: String = "Category default",
        userDecided: Boolean = false,
    ) = PriorityRuleInput(pkg, "Bank", text, priority, reason, userDecided)

    private fun resolve(config: OtpRuleConfig, input: PriorityRuleInput) =
        PriorityRules.resolve(listOf(OtpPriorityRule { config }), input)

    // why: the default rule lifts a code to CRITICAL (pass-through) and says why.
    // catches: a wrong default target, a missing reason, a dropped code.
    @Test fun `a code raises the priority to critical with the reason and the code`() {
        val result = resolve(on, input())
        assertThat(result).isEqualTo(PriorityResolution(CRITICAL, "Verification code", "482913"))
    }

    // why: the target is configurable, so other upgrades are one setting away.
    // catches: a hard-coded CRITICAL.
    @Test fun `the target priority is configurable`() {
        assertThat(resolve(on.copy(target = HIGH), input()).priority).isEqualTo(HIGH)
    }

    // why: the master switch is Experiments.OTP_CODES; off means the notification is untouched and no code leaves.
    // catches: running the rule while the experiment is off.
    @Test fun `master switch off does nothing`() {
        val result = resolve(on.copy(enabled = false), input())
        assertThat(result).isEqualTo(PriorityResolution(LOW, "Category default", null))
    }

    @Test fun `no code means no change`() {
        val result = resolve(on, input(text = "Your order 884213 has shipped"))
        assertThat(result).isEqualTo(PriorityResolution(LOW, "Category default", null))
    }

    // why: scope is all apps by default or a chosen set.
    // catches: ignoring the set, or ignoring the all-apps flag when a set is stored.
    @Test fun `scope is all apps or the chosen set`() {
        val chosen = on.copy(allApps = false, packages = setOf("com.bank"))
        assertThat(resolve(chosen, input(pkg = "com.bank")).priority).isEqualTo(CRITICAL)
        assertThat(resolve(chosen, input(pkg = "com.shop")).priority).isEqualTo(LOW)
        assertThat(resolve(chosen.copy(allApps = true), input(pkg = "com.shop")).priority).isEqualTo(CRITICAL)
        assertThat(resolve(on.copy(allApps = false), input(pkg = "com.bank")).verificationCode).isNull()
    }

    // why: a lock or override is the user's explicit choice and beats an automated rule for the priority, but the
    //      code is still reported so it gets its own notification (the app page promises codes from covered apps).
    // catches: the rule overriding "Set by user" / "Locked by user", or dropping the code for such an app.
    @Test fun `a user locked or overridden priority wins but the code is still raised`() {
        for (reason in listOf("Set by user", "Locked by user")) {
            val result = resolve(on, input(reason = reason, userDecided = true))
            assertThat(result).isEqualTo(PriorityResolution(LOW, reason, "482913"))
        }
    }

    // why: a rule only raises. A lower target never demotes, but the code is still reported to be raised.
    // catches: demoting a CRITICAL category default, or losing the code when the priority does not change.
    @Test fun `a target below the current priority keeps it but still reports the code`() {
        val result = resolve(on.copy(target = MEDIUM), input(priority = HIGH, reason = "Category default"))
        assertThat(result).isEqualTo(PriorityResolution(HIGH, "Category default", "482913"))
    }

    @Test fun `already critical stays critical with its own reason`() {
        val result = resolve(on, input(priority = CRITICAL, reason = "Category default"))
        assertThat(result).isEqualTo(PriorityResolution(CRITICAL, "Category default", "482913"))
    }

    // why: the seam takes more rules later; the highest wins and the first code is kept.
    // catches: a later, weaker rule overwriting a stronger one.
    @Test fun `several rules compose by raising only`() {
        val high = PriorityRule { PriorityRuleMatch(HIGH, "High rule") }
        val critical = PriorityRule { PriorityRuleMatch(CRITICAL, "Critical rule", "111111") }
        val medium = PriorityRule { PriorityRuleMatch(MEDIUM, "Medium rule", "222222") }
        val result = PriorityRules.resolve(listOf(high, critical, medium), input())
        assertThat(result).isEqualTo(PriorityResolution(CRITICAL, "Critical rule", "111111"))
    }
}
