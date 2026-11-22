package ly.com.tahaben.notification_filter_presentation.inbox

import androidx.compose.runtime.Immutable
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.components.NotificationDateLabeler

/** Where a notification stands with Farhan's delivery: already handed to Android, queued, or never going to be. */
enum class DeliveryState(val headerLabel: String, val chipLabel: String) {
    /** Queued; Farhan will post it at the next delivery window. */
    WAITING("Waiting to send", "Waiting"),

    /** Farhan could not proxy it (intake overflow); only the source app has it. */
    NOT_SENT("Not sent", "Not sent"),

    /** Farhan already posted it to the system tray. */
    SENT("Sent", "Sent");

    companion object {
        fun of(item: NotificationItem): DeliveryState = when {
            item.isDelivered -> SENT
            item.deliveryInvalidated -> NOT_SENT
            else -> WAITING
        }
    }
}

/** One notification with everything a row draws, so composition only reads fields. */
@Immutable
data class InboxMessage(
    val item: NotificationItem,
    val displayPriority: NotificationPriority,
    val delivery: DeliveryState,
    val dateTimeLabel: String,
    val compactLabel: String,
    val actions: List<String>,
    /** A verification code found in the text, only when masking is on. Never shown without a tap. */
    val otp: String? = null,
)

@Immutable
sealed interface InboxEntry {
    val key: String
    val contentType: String
}

/** A delivery section: its sticky header and the rows and threads under it. */
@Immutable
data class InboxSection(
    val delivery: DeliveryState,
    val entries: List<InboxEntry>,
    /** The raised verification-code section that sits above every delivery section. */
    val isCodes: Boolean = false,
) {
    val key: String get() = if (isCodes) "header:CODES" else "header:${delivery.name}"

    /** Messages in the section, counting every message inside a thread. */
    val messageCount: Int
        get() = entries.sumOf { if (it is InboxThread) it.messages.size else 1 }
}

@Immutable
data class InboxSingle(val message: InboxMessage) : InboxEntry {
    override val key: String get() = message.item.id
    override val contentType: String get() = "single"
}

/** Several messages from one conversation (same app and title), newest first. */
@Immutable
data class InboxThread(
    override val key: String,
    val delivery: DeliveryState,
    val messages: List<InboxMessage>,
) : InboxEntry {
    val latest: InboxMessage get() = messages.first()
    val unreadCount: Int get() = messages.count { !it.item.isRead }
    override val contentType: String get() = "thread"
}

object InboxEntryBuilder {

    /**
     * Turns one page of rows (newest first) into sections for waiting, not-sent and sent notifications,
     * with a single expandable thread wherever one conversation has two or more messages in a section.
     * Empty sections are left out. Pure, so the ViewModel can run it off the main thread.
     */
    fun build(
        notifications: List<NotificationItem>,
        displayPriority: (NotificationItem) -> NotificationPriority,
        actionTitles: (NotificationItem) -> List<String>,
        labeler: NotificationDateLabeler,
        otpCode: (NotificationItem) -> String? = { null },
    ): List<InboxSection> {
        if (notifications.isEmpty()) return emptyList()
        val messages = notifications.map { item ->
            val state = DeliveryState.of(item)
            InboxMessage(
                item = item,
                displayPriority = displayPriority(item),
                delivery = state,
                dateTimeLabel = if (item.timestamp > 0L) labeler.dateTime(item.timestamp) else item.time,
                compactLabel = if (item.timestamp > 0L) labeler.compact(item.timestamp) else item.time,
                actions = actionTitles(item),
                otp = otpCode(item),
            )
        }
        // Codes are raised out of their delivery section, newest first, one row each.
        val (codes, rest) = messages.partition { it.otp != null }
        val bySection = rest.groupBy { it.delivery }
        val sections = DeliveryState.entries.mapNotNull { state ->
            val rows = bySection[state] ?: return@mapNotNull null
            InboxSection(state, group(state, rows))
        }
        return if (codes.isEmpty()) sections
        else listOf(InboxSection(DeliveryState.SENT, codes.map(::InboxSingle), isCodes = true)) + sections
    }

    private fun threadKeyOf(message: InboxMessage): String? {
        val item = message.item
        if (item.category != NotificationCategory.COMMUNICATION) return null
        val title = item.title?.trim().orEmpty()
        if (title.isEmpty()) return null
        return "thread:${message.delivery.name}:${item.packageName}:$title"
    }

    private fun group(state: DeliveryState, messages: List<InboxMessage>): List<InboxEntry> {
        val members = LinkedHashMap<String, MutableList<InboxMessage>>()
        for (message in messages) threadKeyOf(message)?.let { members.getOrPut(it) { mutableListOf() } += message }
        val emitted = HashSet<String>()
        val out = ArrayList<InboxEntry>(messages.size)
        for (message in messages) {
            val key = threadKeyOf(message)
            val group = key?.let(members::get)
            when {
                key == null || group == null || group.size < 2 -> out += InboxSingle(message)
                emitted.add(key) -> out += InboxThread(key, state, group.toList())
            }
        }
        return out
    }
}
