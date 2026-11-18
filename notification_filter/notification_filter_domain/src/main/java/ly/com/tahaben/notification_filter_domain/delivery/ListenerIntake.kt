package ly.com.tahaben.notification_filter_domain.delivery

import kotlinx.coroutines.channels.Channel

/** A bounded mailbox for copied callback fields. False means leave the source notification in its tray. */
class ListenerIntake<T>(capacity: Int = 128, private val onDepth: (Int) -> Unit = {}) {
    private val channel = Channel<T>(capacity)
    private val depth = java.util.concurrent.atomic.AtomicInteger()
    fun offer(event: T): Boolean {
        depth.incrementAndGet()
        val accepted = channel.trySend(event).isSuccess
        if (!accepted) depth.decrementAndGet()
        onDepth(depth.get())
        return accepted
    }
    suspend fun consume(process: suspend (T) -> Unit) { for (event in channel) { onDepth(depth.decrementAndGet()); process(event) } }
    fun close() = channel.close()
}
