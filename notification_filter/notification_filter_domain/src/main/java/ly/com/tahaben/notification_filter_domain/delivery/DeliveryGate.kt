package ly.com.tahaben.notification_filter_domain.delivery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One injected owner for delivery, schedule writes and explicit budget resets. Never nest [run]. */
class DeliveryGate {
    private val mutex = Mutex()
    internal var recover: (suspend () -> Unit)? = null
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { recover?.invoke(); block() }
}
