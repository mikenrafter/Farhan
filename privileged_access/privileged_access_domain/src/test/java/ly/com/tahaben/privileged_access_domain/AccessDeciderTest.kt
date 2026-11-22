package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.AccessState.ALLOWED
import ly.com.tahaben.privileged_access_domain.AccessState.NOT_ALLOWED
import ly.com.tahaben.privileged_access_domain.AccessState.NOT_APPLICABLE
import ly.com.tahaben.privileged_access_domain.Route.SELF_ADB
import ly.com.tahaben.privileged_access_domain.Route.SHIZUKU
import org.junit.Test

class AccessDeciderTest {
    private fun facts(sdk: Int = 35, op: AppOpMode = AppOpMode.NOT_ALLOWED, held: Boolean = false, write: Boolean? = null) =
        AccessFacts(sdk, op, held, write)

    private val ready = RouteReadiness.Ready
    private fun blocked(b: RouteBlocker) = RouteReadiness.Blocked(b)

    @Test
    fun `the sensitive-notification app-op is judged only on Android 15 and later`() {
        assertThat(AccessDecider.decide(Access.SENSITIVE_NOTIFICATIONS, facts(sdk = 34, op = AppOpMode.NOT_ALLOWED))).isEqualTo(NOT_APPLICABLE)
        assertThat(AccessDecider.decide(Access.SENSITIVE_NOTIFICATIONS, facts(sdk = 35, op = AppOpMode.ALLOWED))).isEqualTo(ALLOWED)
        assertThat(AccessDecider.decide(Access.SENSITIVE_NOTIFICATIONS, facts(sdk = 36, op = AppOpMode.NOT_ALLOWED))).isEqualTo(NOT_ALLOWED)
    }

    // why: an unreadable op must not claim a grant that may not exist; granting again is harmless.
    @Test
    fun `an app-op the platform cannot read counts as not allowed`() {
        assertThat(AccessDecider.decide(Access.SENSITIVE_NOTIFICATIONS, facts(op = AppOpMode.UNKNOWN))).isEqualTo(NOT_ALLOWED)
    }

    @Test
    fun `platform modes map to allowed only for mode zero`() {
        assertThat(AppOpMode.fromPlatformMode(0)).isEqualTo(AppOpMode.ALLOWED)
        listOf(1, 2, 3, 4, -1).forEach { assertThat(AppOpMode.fromPlatformMode(it)).isEqualTo(AppOpMode.NOT_ALLOWED) }
        assertThat(AppOpMode.fromPlatformMode(null)).isEqualTo(AppOpMode.UNKNOWN)
    }

    @Test
    fun `secure settings are allowed when the permission is held unless a write was tried and failed`() {
        assertThat(AccessDecider.decide(Access.SECURE_SETTINGS, facts(held = false))).isEqualTo(NOT_ALLOWED)
        assertThat(AccessDecider.decide(Access.SECURE_SETTINGS, facts(held = true))).isEqualTo(ALLOWED)
        assertThat(AccessDecider.decide(Access.SECURE_SETTINGS, facts(held = true, write = true))).isEqualTo(ALLOWED)
        assertThat(AccessDecider.decide(Access.SECURE_SETTINGS, facts(held = true, write = false))).isEqualTo(NOT_ALLOWED)
        assertThat(AccessDecider.decide(Access.SECURE_SETTINGS, facts(held = false, write = true))).isEqualTo(NOT_ALLOWED)
    }

    @Test
    fun `under ask the link is Self-ADB first, then Shizuku`() {
        val both = mapOf(SELF_ADB to ready, SHIZUKU to ready)
        assertThat(AccessDecider.linkOf(RoutePreference.ASK, both)).isEqualTo(LinkState.Connected(SELF_ADB))
        assertThat(AccessDecider.linkOf(RoutePreference.ASK, both + (SELF_ADB to blocked(RouteBlocker.NOT_PAIRED)))).isEqualTo(LinkState.Connected(SHIZUKU))
        assertThat(AccessDecider.linkOf(RoutePreference.ASK, mapOf(SELF_ADB to blocked(RouteBlocker.NOT_PAIRED), SHIZUKU to blocked(RouteBlocker.SHIZUKU_NOT_RUNNING)))).isEqualTo(LinkState.None)
    }

    // why: a user who chose Shizuku is not shown a Self-ADB link they did not ask for.
    @Test
    fun `a fixed preference looks at its own route only`() {
        val both = mapOf(SELF_ADB to ready, SHIZUKU to blocked(RouteBlocker.SHIZUKU_NOT_RUNNING))
        assertThat(AccessDecider.linkOf(RoutePreference.SHIZUKU, both)).isEqualTo(LinkState.None)
        assertThat(AccessDecider.linkOf(RoutePreference.SELF_ADB, both)).isEqualTo(LinkState.Connected(SELF_ADB))
    }

    @Test
    fun `status carries the link and every access`() {
        val status = AccessDecider.statusOf(facts(op = AppOpMode.ALLOWED, held = false), RoutePreference.ASK, mapOf(SELF_ADB to ready, SHIZUKU to blocked(RouteBlocker.SHIZUKU_NOT_INSTALLED)))
        assertThat(status.checked).isTrue()
        assertThat(status.link).isEqualTo(LinkState.Connected(SELF_ADB))
        assertThat(status.accesses).containsExactly(Access.SENSITIVE_NOTIFICATIONS, ALLOWED, Access.SECURE_SETTINGS, NOT_ALLOWED)
        assertThat(status.canRevoke).isTrue()
        assertThat(PrivilegedStatus.UNCHECKED.checked).isFalse()
    }

    // why: "needs setup" is what sends the user to pairing; a route that one prompt can fix is not setup.
    @Test
    fun `needs setup only when no route can run the grant`() {
        fun status(vararg routes: Pair<Route, RouteReadiness>) =
            AccessDecider.statusOf(facts(), RoutePreference.ASK, routes.toMap())
        assertThat(status(SELF_ADB to blocked(RouteBlocker.NOT_PAIRED), SHIZUKU to blocked(RouteBlocker.SHIZUKU_NOT_RUNNING)).needsSetup(Access.SECURE_SETTINGS)).isTrue()
        assertThat(status(SELF_ADB to blocked(RouteBlocker.NOT_PAIRED), SHIZUKU to blocked(RouteBlocker.SHIZUKU_PERMISSION_NEEDED)).needsSetup(Access.SECURE_SETTINGS)).isFalse()
        assertThat(status(SELF_ADB to ready, SHIZUKU to blocked(RouteBlocker.SHIZUKU_NOT_RUNNING)).needsSetup(Access.SECURE_SETTINGS)).isFalse()
        val allowed = AccessDecider.statusOf(facts(held = true), RoutePreference.ASK, mapOf(SELF_ADB to blocked(RouteBlocker.NOT_PAIRED)))
        assertThat(allowed.needsSetup(Access.SECURE_SETTINGS)).isFalse()
    }

    @Test
    fun `nothing is allowed only when every access is known and none is allowed`() {
        assertThat(AccessDecider.nothingAllowed(emptyMap())).isFalse()
        assertThat(AccessDecider.nothingAllowed(mapOf(Access.SECURE_SETTINGS to NOT_ALLOWED, Access.SENSITIVE_NOTIFICATIONS to NOT_APPLICABLE))).isTrue()
        assertThat(AccessDecider.nothingAllowed(mapOf(Access.SECURE_SETTINGS to ALLOWED, Access.SENSITIVE_NOTIFICATIONS to NOT_ALLOWED))).isFalse()
    }
}
