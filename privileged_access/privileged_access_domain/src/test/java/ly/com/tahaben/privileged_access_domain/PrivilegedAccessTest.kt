package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrivilegedAccessTest {
    private val phone = FakePhone()
    private val journal = Journal()
    private val selfAdb = FakeSelfAdb(phone, journal)
    private val shizuku = FakeShizuku(phone, journal)
    private val preferences = FakePreferences()
    private var rebinds = 0
    private var redactionClears = 0
    private var grayscaleResets = 0

    private fun access(appId: String = "ly.com.tahaben.farhan_alpha", prefs: PrivilegedPreferences = preferences) = DefaultPrivilegedAccess(
        appId, phone, selfAdb, shizuku, prefs,
        rebinder = { rebinds++; journal.events += "rebind" },
        redaction = { redactionClears++ },
        grayscale = { grayscaleResets++; journal.events += "grayscale reset" },
        io = Dispatchers.Unconfined,
    )

    private val code = PairingCode.parse("123456")!!

    private suspend fun DefaultPrivilegedAccess.pairedAndLinked() {
        assertThat(pairSelfAdb(code, 40000)).isEqualTo(PairingResult.Paired)
    }

    // why: a status check must never reach into the phone; only the user's tap may.
    // catches: refresh() or a foreground re-check running a command.
    @Test
    fun `refreshing runs no command and opens no connection`() = runTest {
        val access = access()
        selfAdb.hasKey = true
        access.refresh()
        access.refresh()
        assertThat(selfAdb.ran).isEmpty()
        assertThat(shizuku.ran).isEmpty()
        assertThat(selfAdb.pairCalls).isEqualTo(0)
        assertThat(access.status.value.checked).isTrue()
    }

    // proves: the owner's rule "second access runs over the retained link with no pairing flow".
    @Test
    fun `grant A then B pairs once`() = runTest {
        val access = access()
        access.pairedAndLinked()

        val first = access.grant(Access.SENSITIVE_NOTIFICATIONS)
        val second = access.grant(Access.SECURE_SETTINGS)

        assertThat(first).isEqualTo(AccessResult.Done(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB, verified = true))
        assertThat(second).isEqualTo(AccessResult.Done(PrivilegedCommand.GRANT_SECURE_SETTINGS, Route.SELF_ADB, verified = true))
        assertThat(selfAdb.pairCalls).isEqualTo(1)
        assertThat(selfAdb.ran.map { it.args[0] }).containsExactly("appops", "pm").inOrder()
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SELF_ADB))
        assertThat(access.status.value.accesses).containsExactly(Access.SENSITIVE_NOTIFICATIONS, AccessState.ALLOWED, Access.SECURE_SETTINGS, AccessState.ALLOWED)
    }

    @Test
    fun `the pairing code and port reach the route and the code is not kept by the layer`() = runTest {
        val access = access()
        access.pairSelfAdb(code, 38123)
        assertThat(selfAdb.lastCode).isEqualTo(code)
        assertThat(selfAdb.lastPairingPort).isEqualTo(38123)
        assertThat(access.status.value.toString()).doesNotContain("123456")
    }

    @Test
    fun `an impossible pairing port is refused before the route is called`() = runTest {
        val access = access()
        assertThat(access.pairSelfAdb(code, 0)).isEqualTo(PairingResult.Failed)
        assertThat(access.pairSelfAdb(code, 70000)).isEqualTo(PairingResult.Failed)
        assertThat(selfAdb.pairCalls).isEqualTo(0)
    }

    @Test
    fun `a failed pairing leaves no link`() = runTest {
        selfAdb.pairResult = PairingResult.Rejected
        val access = access()
        assertThat(access.pairSelfAdb(code, null)).isEqualTo(PairingResult.Rejected)
        assertThat(access.status.value.link).isEqualTo(LinkState.None)
    }

    @Test
    fun `under ask with no link the grant asks which route and runs nothing`() = runTest {
        val access = access()
        access.refresh()
        val result = access.grant(Access.SECURE_SETTINGS)
        assertThat(result).isEqualTo(AccessResult.ChooseRoute(listOf(Route.SELF_ADB, Route.SHIZUKU)))
        assertThat(selfAdb.ran).isEmpty()
        assertThat(shizuku.ran).isEmpty()
    }

    @Test
    fun `an explicit route is used even with no link, and the not-paired reason comes back typed`() = runTest {
        val access = access()
        selfAdb.result = ShellResult.Blocked(RouteBlocker.NOT_PAIRED)
        val result = access.grant(Access.SECURE_SETTINGS, Route.SELF_ADB)
        assertThat(result).isEqualTo(AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.NOT_PAIRED)))
    }

    // proves: revoke is a command over the retained link, and one revoke leaves the link in place.
    @Test
    fun `revoking one access keeps the link`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SENSITIVE_NOTIFICATIONS)
        access.grant(Access.SECURE_SETTINGS)

        val result = access.revoke(Access.SENSITIVE_NOTIFICATIONS)

        assertThat(result).isEqualTo(AccessResult.Done(PrivilegedCommand.REVOKE_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB, verified = true))
        assertThat(selfAdb.forgetCalls).isEqualTo(0)
        assertThat(selfAdb.hasKey).isTrue()
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SELF_ADB))
        assertThat(access.status.value.accesses).containsExactly(Access.SENSITIVE_NOTIFICATIONS, AccessState.NOT_ALLOWED, Access.SECURE_SETTINGS, AccessState.ALLOWED)
        assertThat(preferences.linkDropPending).isFalse()
    }

    // proves: "when both are revoked, delete the retained key so the next grant needs pairing again".
    @Test
    fun `revoking both drops the retained key and the next grant needs pairing`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SENSITIVE_NOTIFICATIONS)
        access.grant(Access.SECURE_SETTINGS)
        access.revoke(Access.SENSITIVE_NOTIFICATIONS)

        access.revoke(Access.SECURE_SETTINGS)

        assertThat(selfAdb.forgetCalls).isEqualTo(1)
        assertThat(selfAdb.hasKey).isFalse()
        assertThat(preferences.linkDropPending).isFalse()
        assertThat(access.status.value.link).isEqualTo(LinkState.None)
        assertThat(access.status.value.needsSetup(Access.SECURE_SETTINGS)).isTrue()
        assertThat(access.grant(Access.SECURE_SETTINGS)).isInstanceOf(AccessResult.ChooseRoute::class.java)
    }

    // why: Android may restart Farhan when the permission is revoked, so the cleanup has to survive that.
    // catches: a revoke whose key drop only happens in the process that issued it.
    @Test
    fun `a revoke interrupted by a restart finishes dropping the key at the next status check`() = runTest {
        selfAdb.hasKey = true
        phone.held = false
        phone.op = AppOpMode.NOT_ALLOWED
        preferences.linkDropPending = true

        val restarted = access()
        restarted.refresh()

        assertThat(selfAdb.hasKey).isFalse()
        assertThat(preferences.linkDropPending).isFalse()
        assertThat(restarted.status.value.link).isEqualTo(LinkState.None)
    }

    @Test
    fun `a revoke that did not take keeps the key and clears the marker`() = runTest {
        selfAdb.hasKey = true
        phone.held = true
        preferences.linkDropPending = true

        val restarted = access()
        restarted.refresh()

        assertThat(selfAdb.hasKey).isTrue()
        assertThat(preferences.linkDropPending).isFalse()
    }

    @Test
    fun `a revoke refused by a lost link does not drop the key it could not use`() = runTest {
        val access = access()
        access.pairedAndLinked()
        phone.held = true
        selfAdb.result = ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)

        val result = access.revoke(Access.SECURE_SETTINGS)

        assertThat(result).isEqualTo(AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF)))
        assertThat(selfAdb.forgetCalls).isEqualTo(0)
        assertThat(preferences.linkDropPending).isFalse()
    }

    // proves: "key rejected drops link", and the typed failure sends the user to pairing.
    @Test
    fun `a rejected key clears the link and asks for pairing again`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SENSITIVE_NOTIFICATIONS)
        selfAdb.result = ShellResult.Blocked(RouteBlocker.KEY_REJECTED)

        val result = access.grant(Access.SECURE_SETTINGS)

        assertThat(result).isEqualTo(AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.KEY_REJECTED)))
        assertThat(selfAdb.hasKey).isFalse()
        assertThat(access.status.value.link).isEqualTo(LinkState.None)
        // Access states are read from the phone, so the grant that already took effect still shows as allowed.
        assertThat(access.status.value.accesses[Access.SENSITIVE_NOTIFICATIONS]).isEqualTo(AccessState.ALLOWED)
        assertThat(access.status.value.canRevoke).isFalse()
    }

    // proves: wireless debugging off clears the link without throwing the key away, and the link returns when it is switched back on.
    @Test
    fun `wireless debugging off clears the link but keeps the key`() = runTest {
        val access = access()
        access.pairedAndLinked()
        selfAdb.wirelessOff = true
        access.refresh()
        assertThat(access.status.value.link).isEqualTo(LinkState.None)

        selfAdb.result = ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
        val result = access.grant(Access.SECURE_SETTINGS, Route.SELF_ADB)

        assertThat(result).isEqualTo(AccessResult.Failed(AccessFailure.LinkLost(LinkLostReason.WIRELESS_DEBUGGING_OFF)))
        assertThat(selfAdb.forgetCalls).isEqualTo(0)
        assertThat(selfAdb.hasKey).isTrue()

        selfAdb.wirelessOff = false
        selfAdb.result = null
        access.refresh()
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SELF_ADB))
    }

    // why: the daemon can be missing while the setting still reads "on"; the link must show that until an attempt reaches it.
    @Test
    fun `a daemon that was not found keeps the link down until a later attempt reaches it`() = runTest {
        val access = access()
        access.pairedAndLinked()
        selfAdb.result = ShellResult.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
        access.grant(Access.SECURE_SETTINGS)
        assertThat(access.status.value.link).isEqualTo(LinkState.None)

        selfAdb.result = null
        val retry = access.grant(Access.SECURE_SETTINGS, Route.SELF_ADB)

        assertThat(retry).isInstanceOf(AccessResult.Done::class.java)
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SELF_ADB))
    }

    @Test
    fun `Shizuku not running comes back as its own failure`() = runTest {
        preferences.route = RoutePreference.SHIZUKU
        shizuku.result = ShellResult.Blocked(RouteBlocker.SHIZUKU_NOT_RUNNING)
        val access = access()
        assertThat(access.grant(Access.SECURE_SETTINGS)).isEqualTo(AccessResult.Failed(AccessFailure.ShizukuNotRunning))
    }

    @Test
    fun `Shizuku permission denied is a blocked route, not a command failure`() = runTest {
        shizuku.result = ShellResult.Blocked(RouteBlocker.SHIZUKU_PERMISSION_DENIED)
        val access = access()
        assertThat(access.grant(Access.SECURE_SETTINGS, Route.SHIZUKU))
            .isEqualTo(AccessResult.Failed(AccessFailure.RouteBlocked(Route.SHIZUKU, RouteBlocker.SHIZUKU_PERMISSION_DENIED)))
    }

    @Test
    fun `granting over Shizuku keeps the link on Shizuku and a second grant needs no question`() = runTest {
        shizuku.readiness = RouteReadiness.Blocked(RouteBlocker.SHIZUKU_PERMISSION_NEEDED)
        val access = access()
        assertThat(access.requestShizukuPermission()).isEqualTo(ShizukuPermissionResult.Granted)
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SHIZUKU))

        assertThat(access.grant(Access.SENSITIVE_NOTIFICATIONS)).isEqualTo(AccessResult.Done(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS, Route.SHIZUKU, true))
        assertThat(access.grant(Access.SECURE_SETTINGS)).isEqualTo(AccessResult.Done(PrivilegedCommand.GRANT_SECURE_SETTINGS, Route.SHIZUKU, true))
        assertThat(selfAdb.ran).isEmpty()
    }

    @Test
    fun `a command that fails carries its output`() = runTest {
        val access = access()
        access.pairedAndLinked()
        selfAdb.result = ShellResult.Ran(1, "Exception occurred while executing 'grant':\n")
        assertThat(access.grant(Access.SECURE_SETTINGS))
            .isEqualTo(AccessResult.Failed(AccessFailure.CommandFailed(Route.SELF_ADB, 1, "Exception occurred while executing 'grant':")))
        assertThat(rebinds).isEqualTo(0)
    }

    // why: a clean exit is not proof; the phone is asked.
    @Test
    fun `a clean exit that changed nothing is not effective`() = runTest {
        val access = access()
        access.pairedAndLinked()
        selfAdb.applyEffect = false
        assertThat(access.grant(Access.SECURE_SETTINGS)).isEqualTo(AccessResult.Failed(AccessFailure.NotEffective(Route.SELF_ADB)))
        assertThat(access.grant(Access.SENSITIVE_NOTIFICATIONS)).isEqualTo(AccessResult.Failed(AccessFailure.NotEffective(Route.SELF_ADB)))
        assertThat(rebinds).isEqualTo(0)
        assertThat(redactionClears).isEqualTo(0)
    }

    @Test
    fun `a secure-settings write that the phone refuses is not effective`() = runTest {
        val access = access()
        access.pairedAndLinked()
        phone.writeWorks = false
        assertThat(access.grant(Access.SECURE_SETTINGS)).isEqualTo(AccessResult.Failed(AccessFailure.NotEffective(Route.SELF_ADB)))
    }

    @Test
    fun `an unreadable app-op trusts the clean exit and says it is unverified`() = runTest {
        val access = access()
        access.pairedAndLinked()
        phone.op = AppOpMode.UNKNOWN
        selfAdb.applyEffect = false
        assertThat(access.grant(Access.SENSITIVE_NOTIFICATIONS)).isEqualTo(AccessResult.Done(PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS, Route.SELF_ADB, verified = false))
    }

    // proves: after the app-op grant the listener is rebound and the red note's flag is cleared; the secure grant does neither.
    @Test
    fun `the sensitive grant rebinds the listener and clears the redaction note`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SECURE_SETTINGS)
        assertThat(rebinds).isEqualTo(0)
        assertThat(redactionClears).isEqualTo(0)

        access.grant(Access.SENSITIVE_NOTIFICATIONS)

        assertThat(rebinds).isEqualTo(1)
        assertThat(redactionClears).isEqualTo(1)
        assertThat(journal.events.indexOf("rebind")).isGreaterThan(journal.events.indexOf("run appops set"))
    }

    @Test
    fun `revoking the app-op does not rebind the listener or touch the note`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SENSITIVE_NOTIFICATIONS)
        rebinds = 0; redactionClears = 0
        access.revoke(Access.SENSITIVE_NOTIFICATIONS)
        assertThat(rebinds).isEqualTo(0)
        assertThat(redactionClears).isEqualTo(0)
    }

    // why: revoking while Grayscale is on would leave the screen gray with nothing able to undo it.
    @Test
    fun `revoking secure settings turns grayscale off first`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SECURE_SETTINGS)
        journal.events.clear()

        access.revoke(Access.SECURE_SETTINGS)

        assertThat(grayscaleResets).isEqualTo(1)
        assertThat(journal.events).containsExactly("grayscale reset", "run pm revoke").inOrder()
    }

    @Test
    fun `granting secure settings does not reset grayscale`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SECURE_SETTINGS)
        assertThat(grayscaleResets).isEqualTo(0)
    }

    // why: one command at a time, so two taps cannot interleave two shells.
    @Test
    fun `a second command while one runs is told it is busy`() = runTest {
        val access = access()
        access.pairedAndLinked()
        val gate = CompletableDeferred<Unit>()
        selfAdb.gate = gate

        val first = async(Dispatchers.Default) { access.grant(Access.SECURE_SETTINGS) }
        selfAdb.entered.await()
        val second = access.grant(Access.SENSITIVE_NOTIFICATIONS)
        gate.complete(Unit)

        assertThat(second).isEqualTo(AccessResult.Failed(AccessFailure.Busy))
        assertThat(first.await()).isInstanceOf(AccessResult.Done::class.java)
        assertThat(selfAdb.ran).hasSize(1)
    }

    @Test
    fun `an invalid application id builds no command`() = runTest {
        val access = access(appId = "not a package; reboot")
        access.pairedAndLinked()
        assertThat(access.grant(Access.SECURE_SETTINGS)).isEqualTo(AccessResult.Failed(AccessFailure.InvalidAppId))
        assertThat(selfAdb.ran).isEmpty()
    }

    @Test
    fun `the route preference is stored and decides the link`() = runTest {
        val access = access()
        selfAdb.hasKey = true
        access.refresh()
        assertThat(access.status.value.link).isEqualTo(LinkState.Connected(Route.SELF_ADB))

        access.setRoutePreference(RoutePreference.SHIZUKU)

        assertThat(preferences.route).isEqualTo(RoutePreference.SHIZUKU)
        assertThat(access.routePreference.value).isEqualTo(RoutePreference.SHIZUKU)
        assertThat(access.status.value.link).isEqualTo(LinkState.None)
    }

    @Test
    fun `forgetting the key clears the link and leaves the grants as they are`() = runTest {
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SECURE_SETTINGS)
        access.forgetSelfAdbKey()
        assertThat(selfAdb.hasKey).isFalse()
        assertThat(access.status.value.link).isEqualTo(LinkState.None)
        assertThat(access.status.value.accesses[Access.SECURE_SETTINGS]).isEqualTo(AccessState.ALLOWED)
    }

    @Test
    fun `before Android 15 the sensitive access is not applicable and both-revoked still drops the key`() = runTest {
        phone.sdk = 34
        val access = access()
        access.pairedAndLinked()
        access.grant(Access.SECURE_SETTINGS)
        assertThat(access.status.value.accesses[Access.SENSITIVE_NOTIFICATIONS]).isEqualTo(AccessState.NOT_APPLICABLE)

        access.revoke(Access.SECURE_SETTINGS)

        assertThat(selfAdb.forgetCalls).isEqualTo(1)
    }
}
