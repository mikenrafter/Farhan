package ly.com.tahaben.notification_filter_domain.schedule

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.testsupport.window
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Storage bounds and failure mapping on the shared write path used by Tasker. proves: CLAIM(dated-bounded) */
class ScheduleDatedBoundTest {
    private val yesterday = LocalDate.of(2026, 11, 17)
    private val tomorrow = LocalDate.of(2026, 11, 19)
    private val other = listOf(window(0, 1440, 3))

    // why: Tasker writing one date a day must not grow storage forever. Past dates can never apply again.
    // catches: unbounded dated map; dropping a future override; dropping the date just written (a lost accepted write).
    // TEN_AM_DENVER is 2026-11-18.
    @Test
    fun `a write drops past dates, keeps future ones and keeps the date it wrote`() = runTest {
        val book = ScheduleBook(repeating = threeWindows(8), dated = mapOf(yesterday to other, tomorrow to other))
        val store = FakeScheduleBookStore(ScheduleSnapshot(book, SIX_SPENT_IN_NINE_AM_WINDOW))

        ScheduleWriter(store, TEN_AM_DENVER).write(ScheduleWrite(threeWindows(9)))
        assertThat(store.snapshot.book.dated.keys).containsExactly(tomorrow)

        ScheduleWriter(store, TEN_AM_DENVER).write(ScheduleWrite(other, ScheduleMode.DATED, yesterday))
        assertThat(store.snapshot.book.dated.keys).containsExactly(yesterday, tomorrow)
    }

    private class FailingStore(private val failure: Exception, private val onRead: Boolean) : ScheduleBookStore {
        var commits = 0
        override suspend fun read(): ScheduleSnapshot {
            if (onRead) throw failure
            return ScheduleSnapshot(ScheduleBook(repeating = threeWindows(8)), SIX_SPENT_IN_NINE_AM_WINDOW)
        }
        override suspend fun commit(snapshot: ScheduleSnapshot) { commits++; throw failure }
    }

    private fun intent() = ScheduleIntent(1, null, null,
        listOf(IntentWindow(0, 540, 2), IntentWindow(540, 720, 8), IntentWindow(720, 1440, 0)), "s3cret")

    // why: a storage failure or a repair-required store must be a rejection the receiver can acknowledge, never a
    //      crash and never an "accepted".
    // catches: an exception escaping handle(); a success ack after a failed commit.
    @Test
    fun `storage failures become STORAGE_FAILED rejections`() = runTest {
        val failingCommit = FailingStore(IOException("disk"), onRead = false)
        val unreadable = FailingStore(ScheduleReadException(IllegalStateException("corrupt")), onRead = true)

        val commitAck = ScheduleAutomation(ScheduleWriter(failingCommit, TEN_AM_DENVER), FakeCredentials()).handle(intent())
        val readAck = ScheduleAutomation(ScheduleWriter(unreadable, TEN_AM_DENVER), FakeCredentials()).handle(intent())

        assertThat(commitAck).isEqualTo(AutomationAck.Rejected(AutomationError.STORAGE_FAILED))
        assertThat(readAck).isEqualTo(AutomationAck.Rejected(AutomationError.STORAGE_FAILED))
    }

    // why: the receiver authenticates before it parses; a disabled receiver says so even with a wrong credential.
    // catches: bad-credential reported for a disabled receiver (reveals nothing, but hides the real cause).
    @Test
    fun `authenticate reports disabled before a bad credential`() {
        val credentials = FakeCredentials(enabled = false)
        val automation = ScheduleAutomation(ScheduleWriter(FakeScheduleBookStore(
            ScheduleSnapshot(ScheduleBook(), SIX_SPENT_IN_NINE_AM_WINDOW)), TEN_AM_DENVER), credentials)

        assertThat(automation.authenticate("wrong")).isEqualTo(AutomationError.DISABLED)
        credentials.enabled = true
        assertThat(automation.authenticate("wrong")).isEqualTo(AutomationError.BAD_CREDENTIAL)
        assertThat(automation.authenticate("s3cret")).isNull()
    }
}
