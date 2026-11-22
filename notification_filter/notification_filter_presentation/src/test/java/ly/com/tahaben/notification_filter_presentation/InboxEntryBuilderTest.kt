package ly.com.tahaben.notification_filter_presentation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_presentation.components.NotificationDateLabeler
import ly.com.tahaben.notification_filter_presentation.inbox.DeliveryState
import ly.com.tahaben.notification_filter_presentation.inbox.InboxEntryBuilder
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSection
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSingle
import ly.com.tahaben.notification_filter_presentation.inbox.InboxThread
import org.junit.Test
import java.util.Locale

/**
 * WHY: the Inbox must show what Farhan has already sent apart from what it is still holding, and fold a chat
 * into one card. A wrong split would show a queued message as sent, or hide messages inside the wrong thread.
 */
class InboxEntryBuilderTest {
    private val now = 1_763_725_320_000L // Fri 2025-11-21 10:42 UTC; only relative labels are asserted
    private val labeler = NotificationDateLabeler(now, Locale.US)

    private fun item(
        id: String,
        ageMinutes: Long,
        pkg: String = "chat.app",
        title: String? = "Weekend hike",
        category: NotificationCategory = NotificationCategory.COMMUNICATION,
        delivered: Boolean = false,
        invalidated: Boolean = false,
    ) = NotificationItem(
        id = id, appName = pkg, title = title, text = "text $id", time = "", packageName = pkg,
        timestamp = now - ageMinutes * 60_000L, priority = NotificationPriority.HIGH,
        category = category, isDelivered = delivered, deliveryInvalidated = invalidated,
    )

    private fun build(vararg items: NotificationItem, actions: Map<String, List<String>> = emptyMap()): List<InboxSection> =
        InboxEntryBuilder.build(
            notifications = items.toList(),
            displayPriority = { it.priority },
            actionTitles = { actions[it.id].orEmpty() },
            labeler = labeler,
        )

    @Test
    fun sectionsAreOrderedWaitingThenNotSentThenSent_andEmptyOnesAreOmitted() {
        val sections = build(
            item("sent", 1, delivered = true),
            item("waiting", 2),
            item("lost", 3, invalidated = true),
        )
        assertThat(sections.map { it.delivery })
            .containsExactly(DeliveryState.WAITING, DeliveryState.NOT_SENT, DeliveryState.SENT).inOrder()
        assertThat(build(item("only-sent", 1, delivered = true)).map { it.delivery })
            .containsExactly(DeliveryState.SENT)
    }

    @Test
    fun rowsWithACodeAreRaisedIntoTheirOwnLeadingSection() {
        val sections = InboxEntryBuilder.build(
            notifications = listOf(
                item("plain", 1, title = "Shop", category = NotificationCategory.FINANCE),
                item("code", 2, title = "Bank", category = NotificationCategory.FINANCE, delivered = true),
            ),
            displayPriority = { it.priority },
            actionTitles = { emptyList() },
            labeler = labeler,
            otpCode = { if (it.id == "code") "482913" else null },
        )
        assertThat(sections.map { it.isCodes }).containsExactly(true, false).inOrder()
        val codeMessage = (sections.first().entries.single() as InboxSingle).message
        assertThat(codeMessage.otp).isEqualTo("482913")
        // The raised row keeps its real delivery state, and the delivery section no longer lists it.
        assertThat(codeMessage.delivery).isEqualTo(DeliveryState.SENT)
        assertThat(sections.last().delivery).isEqualTo(DeliveryState.WAITING)
        assertThat(sections.last().messageCount).isEqualTo(1)
    }

    @Test
    fun noCodeLookupMeansNoCodesSection() {
        assertThat(build(item("a", 1), item("b", 2)).none { it.isCodes }).isTrue()
    }

    @Test
    fun deliveredWinsOverInvalidated_soASentRowIsNeverCalledUnsent() {
        val sections = build(item("both", 1, delivered = true, invalidated = true))
        assertThat(sections.single().delivery).isEqualTo(DeliveryState.SENT)
    }

    @Test
    fun twoMessagesFromOneChatBecomeOneThreadAtTheNewestMessagesPosition() {
        val sections = build(
            item("a1", 1),
            item("bank", 2, pkg = "bank.app", title = "Card activity", category = NotificationCategory.FINANCE),
            item("a2", 3),
            item("a3", 4),
        )
        val entries = sections.single().entries
        assertThat(entries).hasSize(2)
        val thread = entries[0] as InboxThread
        assertThat(thread.messages.map { it.item.id }).containsExactly("a1", "a2", "a3").inOrder()
        assertThat(thread.latest.item.id).isEqualTo("a1")
        assertThat((entries[1] as InboxSingle).message.item.id).isEqualTo("bank")
        assertThat(sections.single().messageCount).isEqualTo(4)
    }

    @Test
    fun aSentMessageAndAWaitingMessageFromTheSameChatStayInTheirOwnSections() {
        val sections = build(
            item("sent1", 1, delivered = true),
            item("sent2", 2, delivered = true),
            item("wait1", 3),
            item("wait2", 4),
        )
        val threadKeys = sections.map { (it.entries.single() as InboxThread).key }
        assertThat(threadKeys.toSet()).hasSize(2)
        assertThat(sections.first().delivery).isEqualTo(DeliveryState.WAITING)
    }

    @Test
    fun onlyCommunicationWithATitleIsGrouped() {
        val sections = build(
            item("f1", 1, pkg = "bank.app", title = "Card activity", category = NotificationCategory.FINANCE),
            item("f2", 2, pkg = "bank.app", title = "Card activity", category = NotificationCategory.FINANCE),
            item("c1", 3, title = null),
            item("c2", 4, title = " "),
            item("lone", 5, title = "Solo chat"),
        )
        assertThat(sections.single().entries.all { it is InboxSingle }).isTrue()
    }

    @Test
    fun sameTitleInDifferentAppsIsNotOneConversation() {
        val sections = build(item("s", 1, pkg = "signal"), item("w", 2, pkg = "whatsapp"))
        assertThat(sections.single().entries.all { it is InboxSingle }).isTrue()
    }

    @Test
    fun actionTitlesAreCarriedOntoTheMessage() {
        val sections = build(item("r", 1, title = "Solo"), actions = mapOf("r" to listOf("Reply", "Mark as read")))
        assertThat((sections.single().entries.single() as InboxSingle).message.actions)
            .containsExactly("Reply", "Mark as read").inOrder()
    }

    @Test
    fun labelsMatchTheDayRelativeFormat() {
        assertThat(labeler.date(now - 60_000L)).isEqualTo("Today")
        assertThat(labeler.date(now - 36L * 3_600_000L)).isNotEqualTo("Today")
        assertThat(labeler.dateTime(now - 60_000L)).startsWith("Today • ")
        assertThat(labeler.compact(now - 60_000L)).doesNotContain("Today")
        assertThat(labeler.dateTime(0L)).isEmpty()
    }

    @Test
    fun emptyPageHasNoSections() {
        assertThat(build()).isEmpty()
    }
}
