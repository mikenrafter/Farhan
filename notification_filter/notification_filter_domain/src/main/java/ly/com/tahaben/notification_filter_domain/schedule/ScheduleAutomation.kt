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
}

sealed interface AutomationAck {
    data object Accepted : AutomationAck
    data class Rejected(val error: AutomationError) : AutomationAck
}

interface AutomationCredentials {
    fun isEnabled(): Boolean
    fun matches(presented: String?): Boolean
}

/** Auth, parse, then the common [ScheduleWriter]. Supported version: 1. */
class ScheduleAutomation(
    private val writer: ScheduleWriter,
    private val credentials: AutomationCredentials,
) {
    suspend fun handle(intent: ScheduleIntent): AutomationAck {
        fun reject(error: AutomationError) = AutomationAck.Rejected(error)
        if (!credentials.isEnabled()) return reject(AutomationError.DISABLED)
        if (!credentials.matches(intent.credential)) return reject(AutomationError.BAD_CREDENTIAL)
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
        if (windows.size > 24) return reject(AutomationError.PAYLOAD_TOO_LARGE)
        val schedule = windows.map {
            val start = it.startMinute ?: return reject(AutomationError.MALFORMED_WINDOW)
            val end = it.endMinute ?: return reject(AutomationError.MALFORMED_WINDOW)
            val budget = it.budget ?: return reject(AutomationError.MALFORMED_WINDOW)
            if (start !in 0..1439 || end !in 1..1440) return reject(AutomationError.INVALID_SCHEDULE)
            ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod(start / 60, start % 60, end / 60, end % 60, budget)
        }
        return when (writer.write(ScheduleWrite(schedule, mode, date))) {
            ScheduleWriteResult.Accepted -> AutomationAck.Accepted
            is ScheduleWriteResult.Rejected -> reject(AutomationError.INVALID_SCHEDULE)
        }
    }
}
