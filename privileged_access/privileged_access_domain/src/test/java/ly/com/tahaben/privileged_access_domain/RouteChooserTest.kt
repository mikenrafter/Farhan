package ly.com.tahaben.privileged_access_domain

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.Route.SELF_ADB
import ly.com.tahaben.privileged_access_domain.Route.SHIZUKU
import org.junit.Test

class RouteChooserTest {
    private val none = LinkState.None

    @Test
    fun `the default preference is to ask each time`() {
        assertThat(RoutePreference.DEFAULT).isEqualTo(RoutePreference.ASK)
    }

    // why: a backup edited by hand or from a newer build must not break the screen.
    @Test
    fun `an unknown stored value falls back to the default`() {
        assertThat(RoutePreference.fromStored(null)).isEqualTo(RoutePreference.ASK)
        assertThat(RoutePreference.fromStored("")).isEqualTo(RoutePreference.ASK)
        assertThat(RoutePreference.fromStored("ROOT")).isEqualTo(RoutePreference.ASK)
        assertThat(RoutePreference.fromStored("SHIZUKU")).isEqualTo(RoutePreference.SHIZUKU)
        assertThat(RoutePreference.fromStored("SELF_ADB")).isEqualTo(RoutePreference.SELF_ADB)
    }

    @Test
    fun `a route named for this command wins over everything`() {
        assertThat(RouteChooser.choose(SHIZUKU, RoutePreference.SELF_ADB, LinkState.Connected(SELF_ADB))).isEqualTo(RouteChoice.Use(SHIZUKU))
    }

    @Test
    fun `a fixed preference is used even when the link is the other route`() {
        assertThat(RouteChooser.choose(null, RoutePreference.SELF_ADB, none)).isEqualTo(RouteChoice.Use(SELF_ADB))
        assertThat(RouteChooser.choose(null, RoutePreference.SHIZUKU, LinkState.Connected(SELF_ADB))).isEqualTo(RouteChoice.Use(SHIZUKU))
    }

    // why: the second access must run over the first one's link with no question and no pairing.
    @Test
    fun `under ask a retained link is used without asking`() {
        assertThat(RouteChooser.choose(null, RoutePreference.ASK, LinkState.Connected(SHIZUKU))).isEqualTo(RouteChoice.Use(SHIZUKU))
    }

    @Test
    fun `under ask with no link the user is asked and offered both routes`() {
        assertThat(RouteChooser.choose(null, RoutePreference.ASK, none)).isEqualTo(RouteChoice.AskUser(listOf(SELF_ADB, SHIZUKU)))
    }
}
