package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.notification_filter_data.service.NotificationService
import ly.com.tahaben.notification_filter_domain.sound.GrantsPort
import ly.com.tahaben.notification_filter_domain.sound.SoundClock
import ly.com.tahaben.notification_filter_domain.sound.SoundControlState
import ly.com.tahaben.notification_filter_domain.sound.SoundGrants
import ly.com.tahaben.notification_filter_domain.sound.SoundIntent
import ly.com.tahaben.notification_filter_domain.sound.SoundStateStore
import ly.com.tahaben.notification_filter_domain.sound.SoundStatusPort
import ly.com.tahaben.notification_filter_domain.sound.SoundTime

internal class SoundControlDeviceFixture {
    init {
        assertThat(Build.VERSION.SDK_INT).isEqualTo(35)
    }

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val breakthrough = AndroidBreakthroughChannel(context)
    val poster = SoundProxyNotificationPoster(NotificationService.PROXY_CHANNEL_ID, breakthrough) { item, channel, silent ->
        NotificationService.postProxyNotification(context, item, silent, channelId = channel)
    }

    private val store = object : SoundStateStore {
        private var intent = SoundIntent()
        override fun read() = intent
        override fun commit(intent: SoundIntent): Boolean {
            this.intent = intent
            return true
        }
    }

    private val controller = SoundControlController(
        context.packageName, AndroidZenRulePort(context), breakthrough,
        GrantsPort { SoundGrants(true, manager.isNotificationPolicyAccessGranted, manager.areNotificationsEnabled()) },
        store, SoundClock { SoundTime(SystemClock.elapsedRealtime(), "api35-test") },
        object : SoundStatusPort {
            override fun show(modesRecovery: Boolean) = Unit
            override fun clear() = Unit
        },
        { SoundSwitches(flagOn = true, filterOn = true, appOn = true) },
    )

    suspend fun hold(): String? {
        assertThat(manager.isNotificationPolicyAccessGranted).isTrue()
        assertThat(manager.areNotificationsEnabled()).isTrue()
        assertThat(manager.currentInterruptionFilter).isEqualTo(NotificationManager.INTERRUPTION_FILTER_ALL)
        val state = controller.enable()
        assertThat(state).isInstanceOf(SoundControlState.Held::class.java)
        val held = state as? SoundControlState.Held ?: return null
        return held.ruleId
    }

    suspend fun release() {
        controller.off()
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    fun ready(stage: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("sound_control_stage", stage) })
    }

    fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(100)
            satisfied = condition()
        }
        assertWithMessage(description).that(satisfied).isTrue()
    }
}
