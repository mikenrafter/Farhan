package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.bridge.*
import ly.com.tahaben.farhan_rescue.capture.CaptureFacts
import ly.com.tahaben.farhan_rescue.capture.FailureCode
import ly.com.tahaben.farhan_rescue.capture.Integrity
import org.junit.Test

class BridgeSupervisionTest {
    private val ownPid = 100
    private val kills = mutableListOf<Int>()
    private var binderAlive = true
    private fun session() = BridgeSession(ownPid, { kills += it }, { binderAlive })

    private fun liveSession(pid: Int = 555): BridgeSession = session().also {
        val generation = it.onBound()
        assertThat(it.onHello(pid, generation)).isTrue()
    }

    // why: CLAIM(no-stale-kill) in every state the brief names. catches: signalling a cached pid after unbind, twice,
    // for the UI's own pid, for a Hello from an older connection, or while the binder is already dead.
    @Test fun killIsOnlyAllowedWhileBoundHelloedAndBinderAlive() {
        val neverBound = session()
        assertThat(neverBound.killIfLive()).isFalse()

        val live = liveSession()
        assertThat(live.killIfLive()).isTrue()
        assertThat(live.killIfLive()).isFalse()
        assertThat(kills).containsExactly(555)

        val unbound = liveSession(600).also { it.onUnbound() }
        assertThat(unbound.killIfLive()).isFalse()

        val own = session().also { assertThat(it.onHello(ownPid, it.onBound())).isFalse() }
        assertThat(own.killIfLive()).isFalse()

        val staleHello = session().also { val first = it.onBound(); it.onBound(); assertThat(it.onHello(700, first)).isFalse() }
        assertThat(staleHello.killIfLive()).isFalse()

        binderAlive = false
        assertThat(liveSession(800).killIfLive()).isFalse()
        assertThat(kills).containsExactly(555)
    }

    private class FakeScheduler {
        class Task(val at: Long, val action: () -> Unit) { var cancelled = false }
        var now = 0L
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): Cancellable {
            val task = Task(now + delay, action).also { tasks += it }
            return Cancellable { task.cancelled = true }
        }
        fun advance(to: Long) { now = to; tasks.filter { !it.cancelled && it.at <= now }.forEach { it.cancelled = true; it.action() } }
        /** Runs a task even though it was cancelled: models a timer callback already queued on the main looper. */
        fun runAnyway(task: Task) = task.action()
    }

    private val timedOut = mutableListOf<OperationTracker.Inflight>()
    private fun tracker(scheduler: FakeScheduler, session: BridgeSession = liveSession()) = OperationTracker(
        session, object : Scheduler { override fun schedule(delayMillis: Long, action: () -> Unit) = scheduler.schedule(delayMillis, action) },
        { scheduler.now }, idleTimeoutMillis = 100, absoluteTimeoutMillis = 250, onTimedOut = { timedOut += it },
    )

    // why: CLAIM(one-inflight) and "UI receives bridge pid before jobs". catches: starting while busy or before Hello.
    @Test fun oneOperationAtATimeAndOnlyOnceTheBridgeIsLive() {
        val scheduler = FakeScheduler()
        assertThat(tracker(scheduler, session()).begin(OperationKind.PAIR)).isNull()
        val tracker = tracker(scheduler)
        val first = tracker.begin(OperationKind.CHECK)!!
        assertThat(tracker.begin(OperationKind.EXPORT)).isNull()
        assertThat(tracker.complete(first.id)).isTrue()
        assertThat(tracker.begin(OperationKind.EXPORT)).isNotNull()
    }

    // why: the idle timeout kills the bridge; progress re-arms it; the absolute cap still wins. catches: a timeout that
    // never kills (a blocked pairing socket), progress that extends forever, a missing callback.
    @Test fun idleTimeoutKillsAndProgressReArmsUpToTheAbsoluteCap() {
        val scheduler = FakeScheduler()
        val tracker = tracker(scheduler)
        val operation = tracker.begin(OperationKind.PAIR)!!
        scheduler.advance(90); tracker.progress(operation.id)
        scheduler.advance(180); tracker.progress(operation.id)
        assertThat(timedOut).isEmpty()
        assertThat(kills).isEmpty()
        scheduler.advance(250)
        assertThat(timedOut).containsExactly(operation)
        assertThat(kills).containsExactly(555)
        assertThat(tracker.inflight).isNull()
    }

    @Test fun idleTimeoutWithoutProgressKills() {
        val scheduler = FakeScheduler()
        val tracker = tracker(scheduler)
        val operation = tracker.begin(OperationKind.CHECK)!!
        scheduler.advance(100)
        assertThat(timedOut).containsExactly(operation)
        assertThat(kills).containsExactly(555)
    }

    // why: CLAIM(stale-timer). catches: an old timer ending a later operation.
    @Test fun aFinishedOperationsTimerCannotKillTheNextOne() {
        val scheduler = FakeScheduler()
        val tracker = tracker(scheduler)
        val first = tracker.begin(OperationKind.CHECK)!!
        val oldTask = scheduler.tasks.single()
        tracker.complete(first.id)
        val second = tracker.begin(OperationKind.PAIR)!!
        scheduler.runAnyway(oldTask)
        assertThat(tracker.inflight).isEqualTo(second)
        assertThat(kills).isEmpty()
        assertThat(timedOut).isEmpty()
    }

    // why: cancel really stops the process once, and does nothing when idle. catches: cancel only clearing UI state.
    @Test fun cancelKillsTheBridgeOnlyWhileSomethingRuns() {
        val scheduler = FakeScheduler()
        val tracker = tracker(scheduler)
        assertThat(tracker.cancel()).isFalse()
        assertThat(kills).isEmpty()
        tracker.begin(OperationKind.EXPORT)
        assertThat(tracker.cancel()).isTrue()
        assertThat(kills).containsExactly(555)
        assertThat(tracker.inflight).isNull()
        assertThat(tracker.cancel()).isFalse()
        assertThat(kills).hasSize(1)
    }

    // why: CLAIM(stale-reply-ignored) through the production routing function. catches: a late Captured/Paired from
    // before a cancel or rebind finishing a newer operation, or another job's facts being converted.
    @Test fun staleOrMismatchedEventsAreIgnored() {
        val inflight = OperationTracker.Inflight(7, OperationKind.EXPORT)
        val captured = BridgeEvent.Captured(CaptureFacts(1, null, listOf("a"), false, Integrity.SHA256))
        assertThat(EventRouting.accept(captured, 3, 3, 7, inflight)).isTrue()
        assertThat(EventRouting.accept(captured, 3, 3, 6, inflight)).isFalse()        // older operation id
        assertThat(EventRouting.accept(captured, 2, 3, 7, inflight)).isFalse()        // earlier connection
        assertThat(EventRouting.accept(captured, 3, 3, null, inflight)).isFalse()     // no id
        assertThat(EventRouting.accept(captured, 3, 3, 7, null)).isFalse()            // nothing running
        assertThat(EventRouting.accept(BridgeEvent.Paired, 3, 3, 7, inflight)).isFalse() // wrong result for the kind
        val pairing = OperationTracker.Inflight(8, OperationKind.PAIR)
        assertThat(EventRouting.accept(captured, 3, 3, 8, pairing)).isFalse()
        assertThat(EventRouting.accept(BridgeEvent.Paired, 3, 3, 8, pairing)).isTrue()
        assertThat(EventRouting.accept(BridgeEvent.Failed(FailureCode.PAIR_FAILED), 3, 3, 8, pairing)).isTrue()
        assertThat(EventRouting.accept(BridgeEvent.Hello(9), 3, 3, null, null)).isTrue()
        assertThat(EventRouting.accept(BridgeEvent.Hello(9), 2, 3, null, null)).isFalse()
    }
}
