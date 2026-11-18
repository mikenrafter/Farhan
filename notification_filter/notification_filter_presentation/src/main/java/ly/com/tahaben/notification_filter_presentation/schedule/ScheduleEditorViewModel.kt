package ly.com.tahaben.notification_filter_presentation.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock
import ly.com.tahaben.notification_filter_domain.delivery.NotificationCore
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.*
import java.time.LocalDate
import javax.inject.Inject

/** Which stored schedule the editor shows and writes. The header always names it; Apply writes exactly this. */
sealed interface ScheduleTarget {
    data object Repeating : ScheduleTarget
    data class Dated(val date: LocalDate) : ScheduleTarget
}

data class ScheduleScreenState(
    val isLoading: Boolean = true,
    /** Saved data could not be read. The editor stays closed and nothing is overwritten. */
    val loadError: String? = null,
    val target: ScheduleTarget = ScheduleTarget.Repeating,
    /** Today has a one-date schedule, so a repeating Apply removes it. */
    val todayHasOverride: Boolean = false,
    /** A one-date target with nothing stored yet: the draft starts from the every-day schedule. */
    val targetIsNew: Boolean = false,
    val isApplying: Boolean = false,
    val applyError: String? = null,
    /** Storage changed under an open draft. One confirmation, then a normal write (last change wins). */
    val confirmReplace: Boolean = false,
    val applied: Boolean = false,
)

/**
 * Loads the book, owns one [ScheduleEditor] for the chosen target and writes through [NotificationCore.writer].
 * CLAIM(apply-common-writer): Apply is the only storage write here and it uses the injected writer, so spend,
 * validation and last-change-wins are the core's [ScheduleWriteTest].
 * Errors: an unreadable book shows [ScheduleScreenState.loadError] and never writes; a rejected or failed write
 * keeps the draft and shows [ScheduleScreenState.applyError].
 */
@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(private val core: NotificationCore) : ViewModel() {
    var screen by mutableStateOf(ScheduleScreenState())
        private set
    var editorState by mutableStateOf<EditorState?>(null)
        private set

    private var book = ScheduleBook()
    private var baseSchedule: List<NotificationSchedulePeriod> = emptyList()
    val editor: ScheduleEditor = ScheduleEditor(ScheduleBook().repeating, onState = { editorState = it })

    private fun today(): LocalDate = LocalDate.now(DeliveryClock.systemClock)

    init { viewModelScope.launch { load(initialTarget = null) } }

    private suspend fun load(initialTarget: ScheduleTarget?) {
        val snapshot = try { withContext(Dispatchers.IO) { core.snapshot() } } catch (_: ScheduleReadException) {
            screen = screen.copy(isLoading = false, loadError = "Saved schedule needs repair. Your saved data was kept and nothing was changed.")
            return
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            screen = screen.copy(isLoading = false, loadError = "The saved schedule could not be read. Nothing was changed.")
            return
        }
        book = snapshot.book
        val target = initialTarget ?: if (today() in book.dated) ScheduleTarget.Dated(today()) else ScheduleTarget.Repeating
        showTarget(target)
        screen = screen.copy(isLoading = false, loadError = null)
    }

    private fun showTarget(target: ScheduleTarget) {
        val stored = when (target) {
            ScheduleTarget.Repeating -> book.repeating
            is ScheduleTarget.Dated -> book.dated[target.date]
        }
        baseSchedule = stored ?: book.repeating
        editor.reload(baseSchedule)
        editor.clearSelection(); editor.resetZoom()
        screen = screen.copy(target = target, targetIsNew = stored == null, todayHasOverride = today() in book.dated,
            applyError = null, confirmReplace = false)
    }

    /** Ignored while the draft has changes: Apply or Cancel first, so the shown schedule is the one that gets written. */
    fun chooseTarget(target: ScheduleTarget) {
        if (editorState?.isDirty == true || editorState?.isDragging == true || screen.target == target) return
        showTarget(target)
    }

    fun cancel() { editor.cancel() }

    /** Apply. Ignored mid-drag. Asks once when the stored schedule changed since this draft began. */
    fun apply() {
        if (screen.isApplying || editorState?.isDragging == true || editorState?.isDirty != true) return
        viewModelScope.launch {
            screen = screen.copy(isApplying = true, applyError = null)
            val stale = try { withContext(Dispatchers.IO) { storedFor(screen.target) } != baseSchedule } catch (_: Exception) { false }
            if (stale) screen = screen.copy(isApplying = false, confirmReplace = true) else write()
        }
    }

    fun confirmReplace() {
        if (screen.isApplying) return
        viewModelScope.launch { screen = screen.copy(isApplying = true, confirmReplace = false); write() }
    }

    fun reloadFromStorage() { viewModelScope.launch { screen = screen.copy(confirmReplace = false); load(screen.target) } }

    fun dismissConfirmReplace() { screen = screen.copy(confirmReplace = false) }

    private suspend fun storedFor(target: ScheduleTarget): List<NotificationSchedulePeriod>? {
        val fresh = core.snapshot().book
        return when (target) {
            ScheduleTarget.Repeating -> fresh.repeating
            is ScheduleTarget.Dated -> fresh.dated[target.date] ?: fresh.repeating
        }
    }

    private suspend fun write() {
        val target = screen.target
        val write = when (target) {
            ScheduleTarget.Repeating -> ScheduleWrite(editor.current)
            is ScheduleTarget.Dated -> ScheduleWrite(editor.current, ScheduleMode.DATED, target.date)
        }
        val result = try { withContext(Dispatchers.IO) { core.writer.write(write) } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        when {
            result == ScheduleWriteResult.Accepted -> { editor.markApplied(); screen = screen.copy(isApplying = false, applied = true) }
            result is ScheduleWriteResult.Rejected && result.error == ScheduleWriteError.STORAGE_REPAIR_REQUIRED ->
                screen = screen.copy(isApplying = false, applyError = "Saved schedule needs repair. Nothing was changed.")
            result is ScheduleWriteResult.Rejected ->
                screen = screen.copy(isApplying = false, applyError = "That schedule is not valid, so nothing was saved.")
            else -> screen = screen.copy(isApplying = false, applyError = "Saving failed. Nothing was changed. Try again.")
        }
    }

    fun dismissApplyError() { screen = screen.copy(applyError = null) }
}
