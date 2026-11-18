package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ScheduleMode { REPEATING, DATED }

/** The repeating schedule plus single-date overrides. Other dates use [repeating]. */
data class ScheduleBook(
    val repeating: List<NotificationSchedulePeriod> = NotificationSchedulePeriod.DEFAULT_SCHEDULE,
    val dated: Map<LocalDate, List<NotificationSchedulePeriod>> = emptyMap(),
) {
    fun scheduleFor(date: LocalDate): List<NotificationSchedulePeriod> = repeating

    /** The schedule in force at [instant] on the phone's wall clock in [zone]. */
    fun scheduleAt(instant: Instant, zone: ZoneId): List<NotificationSchedulePeriod> = repeating
}

/** Schedule and spend are committed together so an edit can never expose zero spend in between. */
data class ScheduleSnapshot(val book: ScheduleBook, val budget: DeliveryBudgetState)

interface ScheduleBookStore {
    suspend fun read(): ScheduleSnapshot

    /** All-or-nothing. */
    suspend fun commit(snapshot: ScheduleSnapshot)
}

data class ScheduleWrite(
    val schedule: List<NotificationSchedulePeriod>,
    val mode: ScheduleMode = ScheduleMode.REPEATING,
    val date: LocalDate? = null,
)

enum class ScheduleWriteError { INVALID_SCHEDULE, DATE_REQUIRED }

sealed interface ScheduleWriteResult {
    data object Accepted : ScheduleWriteResult
    data class Rejected(
        val error: ScheduleWriteError,
        val violations: List<ScheduleViolation> = emptyList(),
    ) : ScheduleWriteResult
}

/**
 * RED-PHASE STUB. The one write path for manual Apply and automation: validate, resolve
 * scope, rebase spend onto the new windows, commit once. Last accepted write wins.
 */
class ScheduleWriter(private val store: ScheduleBookStore, private val clock: Clock) {
    suspend fun write(write: ScheduleWrite): ScheduleWriteResult = ScheduleWriteResult.Accepted
}
