package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.notification_filter_domain.delivery.PostResult
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.sound.ProxyAlert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundControlBehaviorInstrumentedTest {
    // why: the round 2 API 35 probe observed beep/buzz flags of 1/1 for proxy 63 and no event for the rival.
    // catches: posting on the standard channel or opening interruption access briefly so the competitor alerts.
    @Test
    fun onlyBreakthroughProxyAlertsWithoutFilterTransition(): Unit = runBlocking {
        val fixture = SoundControlDeviceFixture()
        val competitor = "research.sounddnd.competitor"
        val receiver = "$competitor/research.sounddnd.Probe\$Source"
        val sortId = SystemClock.elapsedRealtime().toInt() and 0x0fff_ffff
        val baselineId = sortId * 2
        val heldId = baselineId + 1
        val proxyKey = "red-$heldId"
        val logEvents = "logcat -d -b events -v raw -s notification_alert:I"
        val beepAndBuzz = Regex("""\[([^,]+),1,1,\d+,\d+]""")
        fun alertKeys() = beepAndBuzz.findAll(fixture.shell(logEvents)).map { it.groupValues[1] }.toList()

        fun postCompetitor(id: Int) {
            fixture.shell("am broadcast -f 0x20 -n '$receiver' --ei id $id --es kind normal")
        }

        // Each API 35 event is [key, buzz, beep, blink, ...]. A buzz/beep event from the same app is the control.
        postCompetitor(baselineId)
        fixture.await("The same competitor can beep and buzz before Farhan holds the rule") {
            alertKeys().any { "$competitor|$baselineId|null|" in it }
        }

        val transitions = AtomicInteger()
        val callback = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                transitions.incrementAndGet()
            }
        }
        fixture.context.registerReceiver(
            callback, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), Context.RECEIVER_EXPORTED,
        )
        try {
            fixture.hold() ?: return@runBlocking
            fixture.await("Holding the explicit rule changes the filter and trips the transition probe") {
                transitions.get() > 0 && fixture.manager.currentInterruptionFilter == 2
            }
            val priorTransitions = transitions.get()
            postCompetitor(heldId)
            val item = NotificationItem(proxyKey, "Competitor", "Elected alert", "message", "10:00", competitor)
            assertThat(fixture.poster.post(item, ProxyAlert.Breakthrough)).isEqualTo(PostResult.Posted)
            fixture.await("The held competitor exists and the proxy emitted a beep and a buzz") {
                "|$competitor|$heldId|null|" in fixture.shell("dumpsys notification") &&
                    alertKeys().any { "${fixture.context.packageName}|1|farhan:$proxyKey|" in it }
            }
            assertThat(fixture.shell(logEvents).lineSequence().filter { "|$competitor|$heldId|null|" in it }.toList())
                .isEmpty()
            assertThat(fixture.manager.currentInterruptionFilter).isEqualTo(2)
            assertThat(transitions.get()).isEqualTo(priorTransitions)
            assertThat(fixture.manager.activeNotifications.singleOrNull { it.tag == "farhan:$proxyKey" }?.notification?.channelId)
                .isEqualTo("farhan_breakthrough_notifications")
        } finally {
            fixture.context.unregisterReceiver(callback)
            fixture.manager.cancel("farhan:$proxyKey", 1)
            for (id in listOf(baselineId, heldId)) {
                fixture.shell("am broadcast -f 0x20 -n '$receiver' --ei id $id --ez cancel true")
            }
            fixture.release()
        }
    }

    // why: round 2 calls rows 5–10 show that ANY admits an unfamiliar first call and STARRED can suppress it.
    // catches: mirroring readable call restrictions or using the dialer notification as proof of audible ringing.
    @Test
    fun unfamiliarFirstCallStartsUnmutedRingtoneWhileHeld(): Unit = runBlocking {
        val fixture = SoundControlDeviceFixture()
        fun isRinging(): Boolean = fixture.shell("dumpsys audio").lineSequence().any {
            "state:started" in it && "usage=USAGE_NOTIFICATION_RINGTONE" in it && "mutedState:none" in it
        }

        try {
            // The host sends two distinct, non-contact GSM calls and disconnects each after its stage.
            assertThat(fixture.manager.currentInterruptionFilter).isEqualTo(1) // API 35 ALL.
            assertThat(isRinging()).isFalse()
            fixture.ready("baseline-call")
            fixture.await("Baseline incoming call rings with DND off", ::isRinging)
            fixture.ready("hang-up-baseline")
            fixture.await("Baseline ringtone stops before the second call") { !isRinging() }

            fixture.hold() ?: return@runBlocking
            fixture.await("Farhan holds PRIORITY before the next incoming call") {
                fixture.manager.currentInterruptionFilter == 2
            }
            fixture.ready("held-call")
            fixture.await("Fresh incoming call has an unmuted ringtone while PRIORITY remains held", ::isRinging)
            assertThat(fixture.manager.currentInterruptionFilter).isEqualTo(2)
        } finally {
            fixture.ready("hang-up-held")
            fixture.release()
        }
    }

    // why: v2.3 says app-start must match and remove the Farhan condition even when its journal and flag are gone.
    // catches: skipping flagged-off startup cleanup or looking only for the former stored rule ID.
    @Test
    fun realClearDataThenLaunchRemovesPreviouslyHeldRule() {
        val fixture = SoundControlDeviceFixture()
        val arguments = InstrumentationRegistry.getArguments()
        assertThat(fixture.shell("getprop ro.kernel.qemu").trim()).isEqualTo("1")
        assertThat(arguments.getString("clearDisposableFarhanData")).isEqualTo("true")
        val heldRule = arguments.getString("heldRuleId").orEmpty()
        val validId = Regex("[a-fA-F0-9-]{16,64}").matches(heldRule)
        assertThat(validId).isTrue()
        if (!validId) return
        val packageName = "ly.com.tahaben.farhan_alpha"
        fun rulePresent() = fixture.shell("dumpsys notification --noredact").contains(heldRule)

        assertThat(fixture.context.packageName).isNotEqualTo(packageName)
        assertThat(rulePresent()).isTrue()
        assertThat(fixture.manager.currentInterruptionFilter).isEqualTo(2)
        assertThat(fixture.shell("pm clear $packageName").trim()).isEqualTo("Success")
        assertWithMessage("The emulator must leave an orphan to test app-start cleanup").that(rulePresent()).isTrue()
        assertThat(fixture.shell("am start -W -n $packageName/ly.com.tahaben.farhan.MainActivity"))
            .contains("Status: ok")
        fixture.await("The previously held Farhan rule is gone after app start") {
            !rulePresent() && fixture.manager.currentInterruptionFilter == 1
        }
    }
}
