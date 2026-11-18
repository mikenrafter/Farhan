package ly.com.tahaben.notification_filter_data.automation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The receiver's logic through the real parser, the real [ScheduleAutomation] and the real [ScheduleWriter]; only
 * storage and credentials are in memory. The BroadcastReceiver itself (goAsync, manifest) is device-verified.
 * proves: CLAIM(auth-before-parse), CLAIM(receipt-sanitized), CLAIM(automation-validation)
 */
class ScheduleAutomationEntryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-18T17:00:00Z"), ZoneId.of("America/Denver"))
    private val secret = "a".repeat(64)
    private val initial = ScheduleSnapshot(ScheduleBook(repeating = three(8)), DeliveryBudgetState(spentPoints = 6, periodStartMinute = 540))

    private fun three(middle: Int) = listOf(period(0, 540, 2), period(540, 720, middle), period(720, 1440, 0))
    private fun period(start: Int, end: Int, budget: Int) = NotificationSchedulePeriod(start / 60, start % 60, end / 60, end % 60, budget)

    private class Store(var snapshot: ScheduleSnapshot) : ScheduleBookStore {
        var commits = 0
        override suspend fun read() = snapshot
        override suspend fun commit(snapshot: ScheduleSnapshot) { this.snapshot = snapshot; commits++ }
    }
    private class Credentials(var enabled: Boolean, private val secret: String) : AutomationCredentials {
        override fun isEnabled() = enabled
        override fun matches(presented: String?) = presented == secret
    }
    private class Receipts : AutomationReceiptStore {
        val recorded = mutableListOf<AutomationReceipt>()
        override fun last() = recorded.lastOrNull()
        override fun record(receipt: AutomationReceipt) { recorded += receipt }
    }

    private class Harness(val store: Store, val credentials: Credentials, val receipts: Receipts, val entry: ScheduleAutomationEntry)

    private fun harness(enabled: Boolean = true): Harness {
        val store = Store(initial)
        val credentials = Credentials(enabled, secret)
        val receipts = Receipts()
        return Harness(store, credentials, receipts,
            ScheduleAutomationEntry(ScheduleAutomation(ScheduleWriter(store, clock), credentials), receipts))
    }

    private fun payload(windows: String = """[{"start":0,"end":540,"budget":2},{"start":540,"end":720,"budget":10},{"start":720,"end":1440,"budget":0}]""",
        version: String = "1", extra: String = "") = """{"version":$version$extra,"windows":$windows}"""

    // why: every refused request leaves the stored schedule and spend byte-for-byte alone, with zero commits.
    // catches: a request that mutates before validating; auth checked after parsing; a crash on bad input.
    @Test
    fun `refused requests change nothing`() = runTest {
        val cases = listOf(
            "disabled" to Triple(false, secret, payload()),
            "wrong credential" to Triple(true, "b".repeat(64), payload()),
            "null credential" to Triple(true, null, payload()),
            "unknown version" to Triple(true, secret, payload(version = "2")),
            "malformed" to Triple(true, secret, "{"),
            "oversized" to Triple(true, secret, "x".repeat(10_000)),
            "repeat plus date" to Triple(true, secret, payload(extra = ""","mode":"repeating","date":"2026-11-20"""")),
            "dated without date" to Triple(true, secret, payload(extra = ""","mode":"dated"""")),
            "bad date" to Triple(true, secret, payload(extra = ""","mode":"dated","date":"2026-02-30"""")),
            "gap" to Triple(true, secret, payload(windows = """[{"start":0,"end":500,"budget":1},{"start":540,"end":1440,"budget":1}]""")),
            "short window" to Triple(true, secret, payload(windows = """[{"start":0,"end":30,"budget":1},{"start":30,"end":1440,"budget":1}]""")),
        )
        for ((name, case) in cases) {
            val h = harness(enabled = case.first)
            val ack = h.entry.handle(case.second, case.third, nowMillis = 1_000)
            assertThat(ack).isInstanceOf(AutomationAck.Rejected::class.java)
            assertThat(h.store.commits).isEqualTo(0)
            assertThat(h.store.snapshot).isEqualTo(initial)
        }
    }

    // why: owner example. Omitted mode is repeating; the write goes through the common writer; spend of 6 survives;
    //      replaying the same payload does not refill it.
    // catches: a separate write path; replay resetting spend. Hand math: budget 10 - spent 6 = 4 remaining.
    @Test
    fun `an accepted repeating payload keeps spend and a replay does not refill it`() = runTest {
        val h = harness()

        val first = h.entry.handle(secret, payload(), 1_000)
        val replay = h.entry.handle(secret, payload(), 2_000)

        assertThat(first).isEqualTo(AutomationAck.Accepted)
        assertThat(replay).isEqualTo(AutomationAck.Accepted)
        assertThat(h.store.snapshot.book.repeating).isEqualTo(three(10))
        assertThat(h.store.snapshot.budget.spentPoints).isEqualTo(6)
    }

    // why: dated mode targets exactly its date and leaves the repeating schedule alone.
    @Test
    fun `a dated payload is stored only under its date`() = runTest {
        val h = harness()

        h.entry.handle(secret, payload(extra = ""","mode":"dated","date":"2026-11-20""""), 1_000)

        assertThat(h.store.snapshot.book.dated.keys).containsExactly(LocalDate.of(2026, 11, 20))
        assertThat(h.store.snapshot.book.repeating).isEqualTo(three(8))
    }

    // why: a disabled receiver and an authenticated outcome differ in what they may write. A disabled receiver
    //      records nothing; a wrong credential is recorded at most once per interval; accepted and rejected
    //      authenticated requests are recorded with only a time, a mode and an error name.
    // catches: an unauthenticated flood writing storage; a credential or payload leaking into the receipt.
    @Test
    fun `receipts are sanitized and unauthenticated senders cannot flood them`() = runTest {
        val disabled = harness(enabled = false)
        disabled.entry.handle(secret, payload(), 1_000)
        assertThat(disabled.receipts.recorded).isEmpty()

        val h = harness()
        repeat(5) { h.entry.handle("b".repeat(64), payload(), 1_000L + it) }
        assertThat(h.receipts.recorded).hasSize(1)
        h.entry.handle(secret, payload(extra = ""","mode":"dated","date":"2026-11-20""""), 20_000)
        h.entry.handle(secret, payload(version = "2"), 20_001)

        assertThat(h.receipts.recorded.map { it.error }).containsExactly(AutomationError.BAD_CREDENTIAL, null, AutomationError.UNSUPPORTED_VERSION).inOrder()
        assertThat(h.receipts.recorded[1].mode).isEqualTo(ScheduleMode.DATED)
        assertThat(h.receipts.recorded.toString()).doesNotContain(secret)
    }
}
