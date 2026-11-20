package ly.com.tahaben.notification_filter_domain.delivery

/**
 * Independent, default-off experiments. Neither changes the budgeted proxy path for real apps.
 * Toggles live in [ly.com.tahaben.notification_filter_domain.preferences.Preferences.isExperimentEnabled].
 */
object Experiments {
    /** Delivered proxies show the source app's small icon when it is a usable resource icon; identity stays Farhan. */
    const val SOURCE_ICON = "source_icon"

    const val SNOOZE = "snooze"

    const val SNOOZE_DEMO = SNOOZE
}

enum class ExperimentEventKind { SNOOZED, RETURNED, RESNOOZED, REMOVED, FAILED, LOST }

/** One platform callback seen during the snooze demo. [removalReason] is Android's code. */
data class ExperimentEvent(
    val atEpochMillis: Long,
    val kind: ExperimentEventKind,
    val removalReason: Int? = null,
    val packageName: String = "",
    val skipReason: SnoozeSkipReason? = null,
    val fallbackReason: SnoozeFallbackReason? = null,
)

/** Bounded, newest-first record of the snooze demo's callbacks, for the settings screen. */
interface ExperimentEventLog {
    fun record(event: ExperimentEvent)
    fun recent(): List<ExperimentEvent>
}

/** In memory only: a restart clears it. Holds at most [capacity] events. */
class InMemoryExperimentEventLog(private val capacity: Int = 20) : ExperimentEventLog {
    private val events = ArrayDeque<ExperimentEvent>()

    @Synchronized override fun record(event: ExperimentEvent) {
        events.addFirst(event)
        while (events.size > capacity) events.removeLast()
    }

    @Synchronized override fun recent(): List<ExperimentEvent> = events.toList()
}
