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
}

sealed interface AutomationAck {
    data object Accepted : AutomationAck
    data class Rejected(val error: AutomationError) : AutomationAck
}

interface AutomationCredentials {
    fun isEnabled(): Boolean
    fun matches(presented: String?): Boolean
}

/** RED-PHASE STUB. Auth, parse, then the common [ScheduleWriter]. Supported version: 1. */
class ScheduleAutomation(
    private val writer: ScheduleWriter,
    private val credentials: AutomationCredentials,
) {
    suspend fun handle(intent: ScheduleIntent): AutomationAck = AutomationAck.Accepted
}
