package ly.com.tahaben.farhan_rescue.bridge

/**
 * The UI side's record of the private bridge process. It is the only place that decides whether a kill is allowed.
 *
 * CLAIM(no-stale-kill): [killIfLive] signals a pid only while ALL of these hold: the service is bound, the pid came
 * from the Hello of the current connection generation, and [binderAlive] (the live binder's `pingBinder()`) is true
 * right now. After unbind, death, an earlier kill, or for the UI's own pid it never signals [BridgeSupervisionTest].
 * Why: the service can die before `onServiceDisconnected` is delivered, and its pid may then belong to an unrelated
 * process; a dead binder means the cached pid is not trusted.
 */
class BridgeSession(
    private val ownPid: Int,
    private val killProcess: (Int) -> Unit,
    private val binderAlive: () -> Boolean,
) {
    private var bound = false
    private var generation = 0L
    var pid: Int? = null
        private set

    val isLive: Boolean get() = bound && pid != null

    /** Starts a new connection generation and returns it. A Hello must carry the same generation to count. */
    fun onBound(): Long { bound = true; pid = null; return ++generation }

    /** Accepts the pid only for the current generation of a bound connection and never for this process itself. */
    fun onHello(helloPid: Int, connectionGeneration: Long): Boolean {
        if (!bound || connectionGeneration != generation || helloPid <= 0 || helloPid == ownPid) return false
        pid = helloPid
        return true
    }

    fun onUnbound() { bound = false; pid = null }

    fun killIfLive(): Boolean {
        val target = pid
        if (!bound || target == null) return false
        pid = null
        if (!binderAlive()) return false
        killProcess(target)
        return true
    }
}

fun interface Cancellable { fun cancel() }

interface Scheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Cancellable
}

/**
 * One inflight operation, with an idle timeout that progress re-arms and an absolute cap.
 *
 * CLAIM(one-inflight): [begin] returns null while another operation runs or the bridge pid is unknown.
 * CLAIM(real-cancel): [cancel] and the timeout kill the bridge process through [BridgeSession], because a blocked
 * pairing socket read cannot be interrupted from inside the library [research/settings-recovery-feasibility.md].
 * CLAIM(stale-timer): a timer from a finished operation never fires into a later one (ids differ) [BridgeSupervisionTest].
 */
class OperationTracker(
    private val session: BridgeSession,
    private val scheduler: Scheduler,
    private val nowMillis: () -> Long,
    private val idleTimeoutMillis: Long = IDLE_TIMEOUT_MILLIS,
    private val absoluteTimeoutMillis: Long = ABSOLUTE_TIMEOUT_MILLIS,
    private val onTimedOut: (Inflight) -> Unit,
) {
    data class Inflight(val id: Long, val kind: OperationKind)

    var inflight: Inflight? = null
        private set
    private var nextId = 1L
    private var deadlineMillis = 0L
    private var timer: Cancellable? = null

    fun begin(kind: OperationKind): Inflight? {
        if (inflight != null || !session.isLive) return null
        val operation = Inflight(nextId++, kind)
        inflight = operation
        deadlineMillis = nowMillis() + absoluteTimeoutMillis
        arm(operation)
        return operation
    }

    /** The bridge reported progress for [id]: the idle clock restarts, the absolute cap does not. */
    fun progress(id: Long) {
        val operation = inflight?.takeIf { it.id == id } ?: return
        arm(operation)
    }

    /** True when [id] was the inflight operation. A late event for an older id returns false and changes nothing. */
    fun complete(id: Long): Boolean {
        if (inflight?.id != id) return false
        timer?.cancel(); timer = null
        inflight = null
        return true
    }

    /** Stops the running operation, if any, by killing the bridge. Returns whether something was running. */
    fun cancel(): Boolean {
        val running = inflight ?: return false
        timer?.cancel(); timer = null
        inflight = null
        session.killIfLive()
        return running.id > 0
    }

    private fun arm(operation: Inflight) {
        timer?.cancel()
        val remaining = deadlineMillis - nowMillis()
        timer = scheduler.schedule(minOf(idleTimeoutMillis, remaining).coerceAtLeast(0)) { fire(operation) }
    }

    private fun fire(operation: Inflight) {
        if (inflight?.id != operation.id) return
        timer = null
        inflight = null
        session.killIfLive()
        onTimedOut(operation)
    }

    companion object {
        const val IDLE_TIMEOUT_MILLIS = 120_000L
        const val ABSOLUTE_TIMEOUT_MILLIS = 600_000L
    }
}

/**
 * Decides whether a bridge event may change UI state.
 *
 * CLAIM(stale-reply-ignored): an event is accepted only when it came through the CURRENT connection generation and,
 * unless it is a Hello, carries the id of the operation now inflight and is a result kind that operation can
 * produce. So a late Captured or Paired from before a cancel, timeout or rebind can neither finish a newer operation
 * nor start a conversion with another job's facts [BridgeSupervisionTest].
 */
object EventRouting {
    fun accept(
        event: BridgeEvent,
        eventGeneration: Long,
        currentGeneration: Long,
        eventOperation: Long?,
        inflight: OperationTracker.Inflight?,
    ): Boolean {
        if (eventGeneration != currentGeneration) return false
        if (event is BridgeEvent.Hello) return true
        if (inflight == null || eventOperation != inflight.id) return false
        return when (event) {
            is BridgeEvent.Paired -> inflight.kind == OperationKind.PAIR
            is BridgeEvent.Checked -> inflight.kind == OperationKind.CHECK
            is BridgeEvent.Captured -> inflight.kind == OperationKind.EXPORT
            is BridgeEvent.ProgressUpdate, is BridgeEvent.Failed -> true
            is BridgeEvent.Hello -> true
        }
    }
}
