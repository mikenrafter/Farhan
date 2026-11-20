package ly.com.tahaben.notification_filter_domain.delivery

data class SnoozeReturn(val key: String, val postTime: Long)

/** The flag lives on an owned snooze entry. A failed mailbox offer leaves it set. */
interface SnoozeReturnFlags {
    fun flagIfOwned(key: String, postTime: Long): Boolean
    fun flagged(): List<SnoozeReturn>
    fun clear(returned: SnoozeReturn)
}

class SnoozeReturnIntake(private val flags: SnoozeReturnFlags) {
    // CLAIM(snooze-intake): mailbox pressure cannot drop an owned return [Snooze pilot spec V5].
    fun onPosted(key: String, postTime: Long, offer: (SnoozeReturn) -> Boolean): Boolean {
        if (!flags.flagIfOwned(key, postTime)) return false
        offer(SnoozeReturn(key, postTime))
        return true
    }

    fun routePosted(
        key: String,
        postTime: Long,
        offer: (SnoozeReturn) -> Boolean,
        onFresh: () -> Unit,
    ) {
        if (!onPosted(key, postTime, offer)) onFresh()
    }

    suspend fun drain(settle: suspend (SnoozeReturn) -> Unit) {
        for (returned in flags.flagged()) {
            settle(returned)
            flags.clear(returned)
        }
    }
}
