package ly.com.tahaben.notification_filter_data.service

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_domain.delivery.NotificationActionStore
import org.junit.Test

class VerificationCodeNotifierTest {
    // why: proxy cleanup and removal mapping key off the "farhan:" prefix; a code notification must never match it.
    // catches: a code tag that starts with the proxy tag, so cancelAllProxies or scoped clears would remove it.
    @Test fun `the code tag is not a proxy tag`() {
        assertThat(codeTag("0|source.app|1|null|10001")).doesNotMatch("^" + Regex.escape(PROXY_TAG) + ".*")
        assertThat(codeTag("x").startsWith(PROXY_TAG)).isFalse()
        assertThat(PROXY_TAG + "x").isNotEqualTo(codeTag("x"))
    }

    // why: the scoped proxy clear is the real cleanup path; it must leave a live code notification alone even
    //      for the same source id.
    // catches: clearing a source's proxy also cancelling its code notification.
    @Test fun `clearing a source's proxy leaves its code notification`() {
        val actions = NotificationActionStore<String>(now = { 0 })
        val cancelled = mutableListOf<Pair<String, Int>>()
        clearScopedProxies(
            listOf("K"), actions,
            activeNotifications = { listOf(PostedNotification(PROXY_TAG + "K", 1), PostedNotification(codeTag("K"), 2)) },
            cancel = { tag, id -> cancelled += tag to id },
        )
        assertThat(cancelled).containsExactly(PROXY_TAG + "K" to 1)
    }

    // why: an update that repeats the same code must not raise it again; a new code must.
    // catches: re-raising a dismissed code on every source update, or suppressing a fresh code for the same source.
    @Test fun `the same code is raised once per source`() {
        val raised = RaisedCodes()
        assertThat(raised.contains("K", "4821")).isFalse()
        raised.remember("K", "4821")
        assertThat(raised.contains("K", "4821")).isTrue()
        assertThat(raised.contains("K", "9999")).isFalse()
        assertThat(raised.contains("other", "4821")).isFalse()
    }

    @Test fun `the memory of raised codes is bounded`() {
        val raised = RaisedCodes(capacity = 2)
        raised.remember("a", "1111")
        raised.remember("b", "2222")
        raised.remember("c", "3333")
        assertThat(raised.contains("a", "1111")).isFalse()
        assertThat(raised.contains("c", "3333")).isTrue()
    }
}
