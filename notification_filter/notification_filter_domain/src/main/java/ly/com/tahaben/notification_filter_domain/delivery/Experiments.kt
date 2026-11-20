package ly.com.tahaben.notification_filter_domain.delivery

/**
 * Independent, default-off experiments. Neither changes the budgeted proxy path for real apps.
 * Toggles live in [ly.com.tahaben.notification_filter_domain.preferences.Preferences.isExperimentEnabled].
 */
object Experiments {
    /** Delivered proxies show the source app's small icon when it is a usable resource icon; identity stays Farhan. */
    const val SOURCE_ICON = "source_icon"

    const val SNOOZE = "snooze"

    /** Android's timed snooze, for Farhan's own demo apps only. Outside the budget, with no unsnooze. */
    const val SNOOZE_DEMO = "snooze_demo"

    const val SNOOZE_MILLIS = 60_000L

    /**
     * CLAIM(snooze-allowlist): the exact demo packages and nothing else; a real app is never snoozed
     * [ExperimentsTest]. Membership, not a prefix, so `ly.com.tahaben.test.messenger.evil` does not qualify.
     */
    val SNOOZE_DEMO_PACKAGES: Set<String> = setOf(
        "ly.com.tahaben.test.messenger",
        "ly.com.tahaben.test.storm",
        "ly.com.tahaben.test.social",
        "ly.com.tahaben.test.calendar",
    )

    fun maySnoozeInDemo(packageName: String): Boolean = packageName in SNOOZE_DEMO_PACKAGES
}

enum class ExperimentEventKind { SNOOZED, RETURNED, RESNOOZED, REMOVED, FAILED, LOST }

/** One platform callback seen during the snooze demo. [removalReason] is Android's code. */
data class ExperimentEvent(
    val atEpochMillis: Long,
    val kind: ExperimentEventKind,
    val removalReason: Int? = null,
    val packageName: String = "",
    val skipReason: SnoozeSkipReason? = null,
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
