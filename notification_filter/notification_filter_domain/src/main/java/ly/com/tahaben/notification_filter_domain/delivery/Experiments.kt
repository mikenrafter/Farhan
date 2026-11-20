package ly.com.tahaben.notification_filter_domain.delivery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Independent, default-off notification experiments.
 * Toggles live in [ly.com.tahaben.notification_filter_domain.preferences.Preferences.isExperimentEnabled].
 */
object Experiments {
    /** Delivered proxies show the source app's small icon when it is a usable resource icon; identity stays Farhan. */
    const val SOURCE_ICON = "source_icon"

    const val SNOOZE = "snooze"
}

enum class ExperimentEventKind { SNOOZED, RETURNED, RESNOOZED, REMOVED, FAILED, LOST }

/** One platform callback seen during the snooze pilot. [removalReason] is Android's code. */
data class ExperimentEvent(
    val atEpochMillis: Long,
    val kind: ExperimentEventKind,
    val removalReason: Int? = null,
    val packageName: String = "",
    val skipReason: SnoozeSkipReason? = null,
    val fallbackReason: SnoozeFallbackReason? = null,
)

/** Bounded, newest-first record of the snooze pilot's callbacks, for the settings screen. */
data class ExperimentHistory(
    val recent: List<ExperimentEvent> = emptyList(),
    val counts: Map<ExperimentEventKind, Long> = emptyMap(),
)

interface ExperimentEventLog {
    val history: StateFlow<ExperimentHistory>
    fun record(event: ExperimentEvent)
    fun recent(): List<ExperimentEvent>
}

/** In memory only: a restart clears it. Holds at most [capacity] events. */
class InMemoryExperimentEventLog(private val capacity: Int = 20) : ExperimentEventLog {
    private val events = ArrayDeque<ExperimentEvent>()
    private val updates = MutableStateFlow(ExperimentHistory())
    override val history = updates.asStateFlow()

    @Synchronized override fun record(event: ExperimentEvent) {
        events.addFirst(event)
        while (events.size > capacity) events.removeLast()
        val counts = updates.value.counts.toMutableMap()
        counts[event.kind] = counts.getOrDefault(event.kind, 0) + 1
        updates.value = ExperimentHistory(events.toList(), counts)
    }

    @Synchronized override fun recent(): List<ExperimentEvent> = events.toList()
}
