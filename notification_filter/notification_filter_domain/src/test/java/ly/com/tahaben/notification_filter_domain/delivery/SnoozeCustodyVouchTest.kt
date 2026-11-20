package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod.Companion.DEFAULT_SCHEDULE
import org.junit.Test

/**
 * The flag route carries no post fields, so it is only safe while the coordinator still stands behind the
 * custody: the key has an event and no removal is newer than the last accepted order.
 *
 * proves: CLAIM(snooze-custody-vouched) [S5]
 */
class SnoozeCustodyVouchTest {
    private val coordinator = DeliveryHarness(DEFAULT_SCHEDULE).coordinator

    // why: a key admitted to a snooze and never cleared must keep the cheap flag route for its return.
    // catches: a predicate that is false for every key (all returns lose the flag route and its mailbox-overflow guarantee).
    @Test
    fun `an admitted key with no removal is vouched`() {
        val order = coordinator.captureEvent("K")
        coordinator.acceptSnoozeEvent("K", order)

        assertThat(coordinator.vouchesForSnoozeCustody("K")).isTrue()
    }

    // why: after an inbox row clear the coordinator holds a removal newer than the admission order (removalApplied
    //      does not move `accepted`), so the return needs its fields and must leave the flag route.
    // catches: the old rule (every owned key is flagged). Reading appliedRemoval instead of lastRemoval passes here, because
    //          clearInbox applies its removal; the native-removal test below catches that.
    @Test
    fun `a key cleared from the inbox after admission is not vouched`() = runTest {
        val order = coordinator.captureEvent("K")
        coordinator.acceptSnoozeEvent("K", order)
        coordinator.clearInbox("K") { }

        assertThat(coordinator.vouchesForSnoozeCustody("K")).isFalse()
    }

    // why: Clear all wipes every event, and a key without an event has nothing for the coordinator to vouch for.
    // catches: a predicate that treats a missing event as "no removal", sending a deleted row's return down the flag route.
    @Test
    fun `a key with no event after clear all is not vouched`() = runTest {
        val order = coordinator.captureEvent("K")
        coordinator.acceptSnoozeEvent("K", order)
        coordinator.clearAll { }

        assertThat(coordinator.vouchesForSnoozeCustody("K")).isFalse()
        assertThat(coordinator.vouchesForSnoozeCustody("never-seen")).isFalse()
    }

    // why: a removal callback captured after the admission order leaves accepted behind lastRemoval until a newer
    //      post is accepted, and only that newer acceptance restores trust in the custody.
    // catches: comparing the removal with the latest captured order instead of the accepted order, or never recovering.
    @Test
    fun `a removal newer than the accepted order withdraws the vouch until a newer post is accepted`() {
        val admitted = coordinator.captureEvent("K")
        coordinator.acceptSnoozeEvent("K", admitted)
        coordinator.captureEvent("K", removed = true)
        assertThat(coordinator.vouchesForSnoozeCustody("K")).isFalse()

        val newer = coordinator.captureEvent("K")
        assertThat(coordinator.vouchesForSnoozeCustody("K")).isFalse()
        coordinator.acceptSnoozeEvent("K", newer)
        assertThat(coordinator.vouchesForSnoozeCustody("K")).isTrue()
    }
}
