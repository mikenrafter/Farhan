package ly.com.tahaben.notification_filter_domain.schedule

/** One window as carried by the Android intent: minute-of-day start (inclusive), end (exclusive, up to 1440). */
data class IntentWindow(val startMinute: Int?, val endMinute: Int?, val budget: Int?)

/** The versioned Tasker contract after extras are read. Content of notifications never appears here. */
data class ScheduleIntent(
    val version: Int?,
    val mode: String?,
    val localDate: String?,
    val windows: List<IntentWindow>?,
    val credential: String?,
)

enum class AutomationError {
    DISABLED,
    BAD_CREDENTIAL,
    UNSUPPORTED_VERSION,
    UNKNOWN_MODE,
    DATE_REQUIRED,
    MALFORMED_DATE,
    MALFORMED_WINDOW,
    INVALID_SCHEDULE,
    DATE_NOT_ALLOWED,
    PAYLOAD_TOO_LARGE,
    /** The payload was not the documented JSON: wrong shape, a non-integer number, an unknown field. */
    MALFORMED_PAYLOAD,
    /** The schedule was valid but could not be saved. Nothing changed. */
    STORAGE_FAILED,
    /** Too many requests were already being handled. Nothing was read or changed; send it again in a few seconds. */
    BUSY,
}

sealed interface AutomationAck {
    data object Accepted : AutomationAck
    data class Rejected(val error: AutomationError) : AutomationAck
}

interface AutomationCredentials {
    fun isEnabled(): Boolean
    fun matches(presented: String?): Boolean
}

/**
 * The owner's side of the credential: show/copy, rotate, revoke, switch on/off. Mated part of the receiving side
 * ([AutomationCredentials]); one class implements both so a rotate is visible to the next intent immediately.
 */
interface AutomationCredentialOwner : AutomationCredentials {
    fun credential(): String?
    fun rotate(): String
    fun setEnabled(enabled: Boolean)
    fun revoke()
}

/**
 * The last automation outcome, for the settings screen. Only fields that are safe to show: no credential,
 * no notification content. [error] is null when the schedule was accepted.
 */
data class AutomationReceipt(val atEpochMillis: Long, val mode: ScheduleMode?, val error: AutomationError?)

interface AutomationReceiptStore {
    fun last(): AutomationReceipt?
    fun record(receipt: AutomationReceipt)
}

/** Auth, parse, then the common [ScheduleWriter]. Supported version: 1. */
class ScheduleAutomation(
    private val writer: ScheduleWriter,
    private val credentials: AutomationCredentials,
) {
    companion object {
        /** The contract's window limit. A 1440-minute day with one-hour windows could hold 24. */
        const val MAX_WINDOWS = 24
    }

    /**
     * The error for a request that is not allowed in, or null when it may proceed. Callers run this before parsing
     * a payload so an unauthenticated sender costs nothing.
     */
    fun authenticate(presentedCredential: String?): AutomationError? = when {
        !credentials.isEnabled() -> AutomationError.DISABLED
        !credentials.matches(presentedCredential) -> AutomationError.BAD_CREDENTIAL
        else -> null
    }

    suspend fun handle(intent: ScheduleIntent): AutomationAck {
        fun reject(error: AutomationError) = AutomationAck.Rejected(error)
        authenticate(intent.credential)?.let { return reject(it) }
        if (intent.version != 1) return reject(AutomationError.UNSUPPORTED_VERSION)
        val mode = when (intent.mode) {
            null, "repeating" -> ScheduleMode.REPEATING
            "dated" -> ScheduleMode.DATED
            else -> return reject(AutomationError.UNKNOWN_MODE)
        }
        if (mode == ScheduleMode.REPEATING && intent.localDate != null) return reject(AutomationError.DATE_NOT_ALLOWED)
        if (mode == ScheduleMode.DATED && intent.localDate == null) return reject(AutomationError.DATE_REQUIRED)
        val date = try { intent.localDate?.let(java.time.LocalDate::parse) }
            catch (_: java.time.DateTimeException) { return reject(AutomationError.MALFORMED_DATE) }
        val windows = intent.windows ?: return reject(AutomationError.MALFORMED_WINDOW)
        if (windows.size > MAX_WINDOWS) return reject(AutomationError.PAYLOAD_TOO_LARGE)
        val schedule = windows.map {
            val start = it.startMinute ?: return reject(AutomationError.MALFORMED_WINDOW)
            val end = it.endMinute ?: return reject(AutomationError.MALFORMED_WINDOW)
            val budget = it.budget ?: return reject(AutomationError.MALFORMED_WINDOW)
            if (start !in 0..1439 || end !in 1..1440) return reject(AutomationError.INVALID_SCHEDULE)
            ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod(start / 60, start % 60, end / 60, end % 60, budget)
        }
        val result = try { writer.write(ScheduleWrite(schedule, mode, date)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { return reject(AutomationError.STORAGE_FAILED) }
        return when (result) {
            ScheduleWriteResult.Accepted -> AutomationAck.Accepted
            is ScheduleWriteResult.Rejected ->
                reject(if (result.error == ScheduleWriteError.STORAGE_REPAIR_REQUIRED) AutomationError.STORAGE_FAILED else AutomationError.INVALID_SCHEDULE)
        }
    }
}
