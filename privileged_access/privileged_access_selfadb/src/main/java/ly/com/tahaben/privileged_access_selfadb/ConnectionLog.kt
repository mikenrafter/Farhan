package ly.com.tahaben.privileged_access_selfadb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What may and may not go into the connection log. The log is shown to the owner and sent on, so it holds ports,
 * hosts, step names and full exception text, and never the pairing code, a key, the adb public key or command output.
 * Callers do not put those in; this is the net under them.
 */
internal object LogScrubber {
    const val CODE_MASK = "******"
    const val WITHHELD = "<message withheld>"
    private const val LONG_TEXT_MIN = 60
    private val LONG_TEXT = Regex("[A-Za-z0-9+/=]{$LONG_TEXT_MIN,}")

    /** [text] with every [secrets] value masked and any very long base64-like run (a key) replaced. */
    fun scrub(text: String, secrets: Collection<String>): String {
        var out = text
        for (secret in secrets) if (secret.isNotEmpty()) out = out.replace(secret, CODE_MASK)
        return LONG_TEXT.replace(out, "<long text withheld>")
    }

    /** An exception message, or [WITHHELD] when it contains a secret. */
    fun message(raw: String?, secrets: Collection<String>): String? =
        raw?.let { m -> if (secrets.any { it.isNotEmpty() && m.contains(it) }) WITHHELD else m }
}

/** "Class: message" for a throwable and each cause under it, so a wrapped failure reads as one line. */
internal object Trace {
    private const val MAX_DEPTH = 6

    fun describe(failure: Throwable, secrets: Collection<String> = emptySet()): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current) && parts.size < MAX_DEPTH) {
            val name = current.javaClass.name
            val message = LogScrubber.message(current.message, secrets)
            parts += if (message.isNullOrBlank()) name else "$name: ${message.replace('\n', ' ').trim()}"
            current = current.cause
        }
        return parts.joinToString(" <- caused by ")
    }
}

/**
 * A bounded, in-memory trail of self-ADB events: the last [capacity] lines, oldest dropped first. It is never
 * written to disk and is gone when the process ends. Each line has UTC and local time, the operation (pair, connect,
 * check), the step, the outcome and a detail.
 */
internal class ConnectionLog(
    private val capacity: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private val lines = ArrayDeque<String>()
    @Volatile private var secrets: Set<String> = emptySet()

    /** The pairing code for the length of one attempt, so nothing that mentions it is kept. Pass an empty set to clear. */
    fun guard(values: Set<String>) { secrets = values }

    @Synchronized
    fun add(operation: String, step: String, outcome: String, detail: String? = null) {
        val guarded = secrets
        val text = buildString {
            append(stamp()).append(" | ").append(operation).append(" | ").append(step).append(" | ").append(outcome)
            if (!detail.isNullOrBlank()) append(" | ").append(detail.replace('\n', ' ').trim())
        }
        lines.addLast(LogScrubber.scrub(text, guarded))
        while (lines.size > capacity) lines.removeFirst()
    }

    fun addFailure(operation: String, step: String, failure: Throwable, extra: String? = null) {
        val detail = Trace.describe(failure, secrets)
        add(operation, step, "FAIL", if (extra.isNullOrBlank()) detail else "$extra | $detail")
    }

    @Synchronized fun lines(): List<String> = lines.toList()

    /** The whole log, oldest line first. Empty when nothing has happened. */
    fun text(): String = lines().joinToString("\n")

    @Synchronized fun size(): Int = lines.size

    private fun stamp(): String {
        val now = Date(clock())
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val local = SimpleDateFormat("HH:mm:ss.SSS XXX", Locale.ROOT).apply { timeZone = zone }
        return "${utc.format(now)} (local ${local.format(now)})"
    }
}
