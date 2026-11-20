package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SnoozeReturnIntakeTest {
    // why: every owned return must remain settleable when 200 callbacks hit the real 128-slot mailbox.
    // proves: CLAIM(snooze-intake)
    // catches: setting the ledger flag only after an offer succeeds, dropping overflow, or double settlement.
    @Test
    fun `all two hundred returns settle after seventy two offers overflow`() = runTest {
        val keys = (1..200).map { "key-$it" }
        val expected = keys.map { SnoozeReturn(it, 1_000L) }
        val flags = FakeFlags(keys)
        val intake = SnoozeReturnIntake(flags)
        val mailbox = ListenerIntake<SnoozeReturn>(128)
        val offers = mutableListOf<Boolean>()
        val settled = mutableListOf<SnoozeReturn>()

        for (returned in expected) {
            assertThat(intake.onPosted(returned.key, returned.postTime) { mailbox.offer(it).also(offers::add) }).isTrue()
        }
        assertThat(offers.count { it }).isEqualTo(128)
        assertThat(offers.count { !it }).isEqualTo(72)
        mailbox.close()
        mailbox.consume { intake.drain(settle = { settled += it }) }
        intake.drain(settle = { settled += it })

        assertThat(settled).containsExactlyElementsIn(expected)
        assertThat(flags.flagged()).isEmpty()
    }

    // why: worker wakeups may drop, so a later batch tick must read the retained return flag.
    // catches: clearing the flag on a failed offer or settling it more than once.
    @Test
    fun `a later drain settles an unqueued return once`() = runTest {
        val flags = FakeFlags(listOf("owned"))
        val intake = SnoozeReturnIntake(flags)
        val settled = mutableListOf<SnoozeReturn>()

        assertThat(intake.onPosted("owned", 200L) { false }).isTrue()
        assertThat(flags.flagged()).containsExactly(SnoozeReturn("owned", 200L))
        intake.drain { settled += it }
        intake.drain { settled += it }
        assertThat(settled).containsExactly(SnoozeReturn("owned", 200L))
    }

    // why: a tracked Android return must branch around new-arrival classification and flood accounting.
    // proves: CLAIM(snooze-intake)
    // catches: reporting an owned key as an ordinary arrival when its mailbox offer fails.
    @Test
    fun `owned return bypasses the new arrival rate window even on overflow`() {
        val flags = FakeFlags(listOf("owned"))
        val intake = SnoozeReturnIntake(flags)
        val rate = NotificationRateWindow(now = { 120_000L })
        val pkg = "com.whatsapp"
        fun callback(key: String) {
            intake.routePosted(key, 1_000L, offer = { false }) { rate.record(pkg) }
        }

        callback("ordinary") // Positive control: a new arrival reaches the rate path.
        callback("owned")

        assertThat(rate.record(pkg)).isEqualTo(NotificationRateWindow.Counts(2, 2))
        assertThat(flags.flagged()).containsExactly(SnoozeReturn("owned", 1_000L))
    }

    private class FakeFlags(keys: List<String>) : SnoozeReturnFlags {
        private val entries = keys.associateWith<String, Long?> { null }.toMutableMap()

        override fun flagIfOwned(key: String, postTime: Long): Boolean {
            if (key !in entries) return false
            entries[key] = postTime
            return true
        }

        override fun flagged() = entries.mapNotNull { (key, postTime) -> postTime?.let { SnoozeReturn(key, it) } }

        override fun clear(returned: SnoozeReturn) {
            if (entries[returned.key] == returned.postTime) entries[returned.key] = null
        }
    }
}
