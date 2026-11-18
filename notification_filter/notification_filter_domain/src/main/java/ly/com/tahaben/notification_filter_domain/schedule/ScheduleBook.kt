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
    fun scheduleFor(date: LocalDate): List<NotificationSchedulePeriod> = dated[date] ?: repeating

    /** The schedule in force at [instant] on the phone's wall clock in [zone]. */
    fun scheduleAt(instant: Instant, zone: ZoneId): List<NotificationSchedulePeriod> = scheduleFor(instant.atZone(zone).toLocalDate())
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

enum class ScheduleWriteError { INVALID_SCHEDULE, DATE_REQUIRED, DATE_NOT_ALLOWED, STORAGE_REPAIR_REQUIRED }

sealed interface ScheduleWriteResult {
    data object Accepted : ScheduleWriteResult
    data class Rejected(
        val error: ScheduleWriteError,
        val violations: List<ScheduleViolation> = emptyList(),
    ) : ScheduleWriteResult
}

/** CLAIM(edit-preserves-spend): rebase current spend at acceptance, not on drag frames [ScheduleWriteTest]. */
class ScheduleWriter(
    private val store: ScheduleBookStore,
    private val clock: Clock,
    private val gate: ly.com.tahaben.notification_filter_domain.delivery.DeliveryGate = ly.com.tahaben.notification_filter_domain.delivery.DeliveryGate(),
    private val minuteNow: (() -> Int)? = null,
) {
    suspend fun write(write: ScheduleWrite): ScheduleWriteResult = gate.run {
        val violations = DaySchedule.validate(write.schedule)
        if (violations.isNotEmpty()) return@run ScheduleWriteResult.Rejected(ScheduleWriteError.INVALID_SCHEDULE, violations)
        if (write.mode == ScheduleMode.DATED && write.date == null) return@run ScheduleWriteResult.Rejected(ScheduleWriteError.DATE_REQUIRED)
        if (write.mode == ScheduleMode.REPEATING && write.date != null) return@run ScheduleWriteResult.Rejected(ScheduleWriteError.DATE_NOT_ALLOWED)
        val now = clock.instant().atZone(clock.zone)
        val today = now.toLocalDate()
        val minute = minuteNow?.invoke() ?: (now.hour * 60 + now.minute)
        val previous = try { store.read() } catch (_: ScheduleReadException) {
            return@run ScheduleWriteResult.Rejected(ScheduleWriteError.STORAGE_REPAIR_REQUIRED)
        }
        val policy = ly.com.tahaben.notification_filter_domain.delivery.NotificationDeliveryPolicy
        val activeBudget = policy.normalizeBudget(previous.budget, previous.book.scheduleFor(today), minute, today.toString())
        val book = when (write.mode) {
            ScheduleMode.REPEATING -> previous.book.copy(repeating = write.schedule.toList(), dated = previous.book.dated - today)
            ScheduleMode.DATED -> previous.book.copy(dated = previous.book.dated + (write.date!! to write.schedule.toList()))
        }
        val newActive = policy.findActivePeriod(book.scheduleFor(today), minute)
        store.commit(ScheduleSnapshot(book, activeBudget.copy(periodStartMinute = newActive?.startMinuteOfDay ?: -1)))
        ScheduleWriteResult.Accepted
    }
}
