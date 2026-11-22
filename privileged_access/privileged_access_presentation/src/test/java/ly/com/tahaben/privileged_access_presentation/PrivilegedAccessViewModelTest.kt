package ly.com.tahaben.privileged_access_presentation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ly.com.tahaben.privileged_access_domain.Access
import ly.com.tahaben.privileged_access_domain.AccessResult
import ly.com.tahaben.privileged_access_domain.PairingCode
import ly.com.tahaben.privileged_access_domain.PairingResult
import ly.com.tahaben.privileged_access_domain.PrivilegedAccess
import ly.com.tahaben.privileged_access_domain.PrivilegedCommand
import ly.com.tahaben.privileged_access_domain.PrivilegedStatus
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RoutePreference
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegedAccessViewModelTest {
    private class FakeAccess : PrivilegedAccess {
        override val status = MutableStateFlow(PrivilegedStatus.UNCHECKED)
        override val routePreference: StateFlow<RoutePreference> = MutableStateFlow(RoutePreference.DEFAULT)
        val calls = mutableListOf<String>()
        var pairResult: PairingResult = PairingResult.Paired
        var accessResult: AccessResult = AccessResult.ChooseRoute(Route.entries)
        override suspend fun refresh(): PrivilegedStatus { calls += "refresh"; return status.value }
        override suspend fun grant(access: Access, route: Route?): AccessResult { calls += "grant $access $route"; return accessResult }
        override suspend fun revoke(access: Access, route: Route?): AccessResult { calls += "revoke $access $route"; return accessResult }
        override suspend fun pairSelfAdb(code: PairingCode, pairingPort: Int?): PairingResult { calls += "pair ${pairingPort}"; return pairResult }
        override suspend fun requestShizukuPermission(): ShizukuPermissionResult { calls += "shizuku"; return ShizukuPermissionResult.Granted }
        override suspend fun setRoutePreference(preference: RoutePreference) { calls += "preference $preference" }
        override suspend fun forgetSelfAdbKey() { calls += "forget" }
    }

    private val access = FakeAccess()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `opening the screen reads the status and runs no command`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        assertThat(access.calls).containsExactly("refresh")
        assertThat(viewModel.state.value.status.checked).isFalse()
    }

    @Test
    fun `returning to the foreground re-checks`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.onForeground()
        assertThat(access.calls).containsExactly("refresh", "refresh").inOrder()
    }

    @Test
    fun `each grant and revoke call goes to the layer with the named route`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.grantSensitiveNotifications()
        viewModel.grantSecureSettings(Route.SHIZUKU)
        viewModel.revokeSensitiveNotifications(Route.SELF_ADB)
        viewModel.revokeSecureSettings()
        assertThat(access.calls.drop(1)).containsExactly(
            "grant SENSITIVE_NOTIFICATIONS null", "grant SECURE_SETTINGS SHIZUKU",
            "revoke SENSITIVE_NOTIFICATIONS SELF_ADB", "revoke SECURE_SETTINGS null",
        ).inOrder()
    }

    @Test
    fun `the result is kept until dismissed and the busy flag drops after`() = runTest {
        val done = AccessResult.Done(PrivilegedCommand.GRANT_SECURE_SETTINGS, Route.SELF_ADB, verified = true)
        access.accessResult = done
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.grantSecureSettings()
        assertThat(viewModel.state.value.lastResult).isEqualTo(done)
        assertThat(viewModel.state.value.working).isFalse()
        viewModel.dismissResult()
        assertThat(viewModel.state.value.lastResult).isNull()
    }

    // why: the code is typed text; a malformed one must not reach the layer.
    @Test
    fun `a malformed code or port never reaches pairing`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.pairSelfAdb("12345")
        viewModel.pairSelfAdb("123456", "99999")
        viewModel.pairSelfAdb("123456", "abc")
        assertThat(access.calls.none { it.startsWith("pair") }).isTrue()
        assertThat(viewModel.state.value.lastPairing).isEqualTo(PairingResult.Rejected)
    }

    @Test
    fun `a blank port means find it`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.pairSelfAdb("123456", " ")
        viewModel.pairSelfAdb("654321", "40123")
        assertThat(access.calls.filter { it.startsWith("pair") }).containsExactly("pair null", "pair 40123").inOrder()
        assertThat(viewModel.state.value.lastPairing).isEqualTo(PairingResult.Paired)
    }

    @Test
    fun `preference and key changes are passed through`() = runTest {
        val viewModel = PrivilegedAccessViewModel(access)
        viewModel.setRoutePreference(RoutePreference.SHIZUKU)
        viewModel.forgetSelfAdbKey()
        viewModel.requestShizukuPermission()
        assertThat(access.calls.drop(1)).containsExactly("preference SHIZUKU", "forget", "shizuku").inOrder()
        assertThat(viewModel.state.value.lastShizukuPermission).isEqualTo(ShizukuPermissionResult.Granted)
    }
}
