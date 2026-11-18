package ly.com.tahaben.notification_filter_data.automation

import kotlinx.coroutines.sync.Semaphore

/**
 * Caps how many automation requests are handled at once. The receiver is exported, so any app can send to it;
 * without a cap every broadcast would start a coroutine and hold a PendingResult while storage or the shared
 * delivery gate is busy.
 *
 * CLAIM(admission-bounded): at most [permits] requests are in flight; the next is refused at once, never queued
 * [AutomationAdmissionTest]. Callers pair every successful [tryEnter] with exactly one [leave].
 */
class AutomationAdmission(permits: Int) {
    private val semaphore = Semaphore(permits)

    /** True when a slot was free and is now held. Never blocks. */
    fun tryEnter(): Boolean = semaphore.tryAcquire()

    fun leave() = semaphore.release()
}
