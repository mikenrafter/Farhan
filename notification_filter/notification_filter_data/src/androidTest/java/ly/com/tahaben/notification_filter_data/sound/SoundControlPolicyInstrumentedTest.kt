package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.notification_filter_domain.delivery.PostResult
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannel
import ly.com.tahaben.notification_filter_domain.sound.ProxyAlert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundControlPolicyInstrumentedTest {
    // why: v2.1–2 must survive the Android adapter and platform round trip, including visual and device effects.
    // catches: an omitted ZenPolicy field remaining UNSET (0), or passing a device-effects object.
    @Test
    fun readsCompleteRuleBackFromAndroid(): Unit = runBlocking {
        val fixture = SoundControlDeviceFixture()
        try {
            val id = fixture.hold() ?: return@runBlocking
            val rule = fixture.manager.automaticZenRules[id]
            assertThat(rule).isNotNull()
            assertThat(rule?.conditionId.toString()).isEqualTo("condition://${fixture.context.packageName}/sound-control")
            assertThat(rule?.interruptionFilter).isEqualTo(2) // API 35 INTERRUPTION_FILTER_PRIORITY.
            assertThat(rule?.deviceEffects).isNull()
            val policy = rule?.zenPolicy
            assertThat(policy).isNotNull()
            if (policy == null) return@runBlocking

            // API 35 ZenPolicy. 1 = ALLOW/ANYONE, 2 = DISALLOW, 4 = no people, 3 = no conversations.
            assertThat(linkedMapOf(
                "calls" to policy.priorityCategoryCalls, "callSenders" to policy.priorityCallSenders,
                "repeatCallers" to policy.priorityCategoryRepeatCallers, "alarms" to policy.priorityCategoryAlarms,
                "media" to policy.priorityCategoryMedia, "system" to policy.priorityCategorySystem,
                "priorityChannels" to policy.priorityChannelsAllowed, "messages" to policy.priorityCategoryMessages,
                "messageSenders" to policy.priorityMessageSenders, "conversations" to policy.priorityCategoryConversations,
                "conversationSenders" to policy.priorityConversationSenders, "reminders" to policy.priorityCategoryReminders,
                "events" to policy.priorityCategoryEvents, "fullscreen" to policy.visualEffectFullScreenIntent,
                "lights" to policy.visualEffectLights, "peek" to policy.visualEffectPeek,
                "statusBar" to policy.visualEffectStatusBar, "badge" to policy.visualEffectBadge,
                "ambient" to policy.visualEffectAmbient, "list" to policy.visualEffectNotificationList,
            )).containsExactly(
                "calls", 1, "callSenders", 1, "repeatCallers", 1, "alarms", 1,
                "media", 1, "system", 1, "priorityChannels", 1, "messages", 2, "messageSenders", 4,
                "conversations", 2, "conversationSenders", 3, "reminders", 2, "events", 2,
                "fullscreen", 1, "lights", 1, "peek", 1, "statusBar", 1, "badge", 1, "ambient", 1, "list", 1,
            )
        } finally {
            fixture.release()
        }
    }

    // why: v2.5–6 verify what Android stored. The requested bypass bit is insufficient without policy access.
    // catches: treating a pre-grant channel as sound-capable or posting on a fallback channel.
    @Test
    fun preGrantChannelReadsBackWithoutBypassAndPostingFails() = runBlocking {
        val fixture = SoundControlDeviceFixture()
        val id = "farhan_breakthrough_notifications"
        val manager = fixture.manager
        assertThat(manager.isNotificationPolicyAccessGranted).isFalse()
        assertThat(manager.getNotificationChannel(id)).isNull()
        manager.createNotificationChannel(
            NotificationChannel(id, "Test breakthrough", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
            },
        )
        try {
            // Independent witness. API 35 kept HIGH (4) but stripped bypass from the fixture.
            assertThat(manager.getNotificationChannel(id)?.importance).isEqualTo(4)
            assertThat(manager.getNotificationChannel(id)?.canBypassDnd()).isFalse()
            val item = NotificationItem("pre-grant", null, "Test alert", null, "10:00", "source.app")
            assertThat(fixture.poster.post(item, ProxyAlert.Breakthrough))
                .isEqualTo(PostResult.Failed("breakthrough_bypass_lost"))
            assertThat(fixture.breakthrough.read()).isEqualTo(BreakthroughChannel(false, 4))
            assertThat(manager.activeNotifications.map { it.tag }).doesNotContain("farhan:pre-grant")
        } finally {
            manager.deleteNotificationChannel(id)
        }
    }
}
