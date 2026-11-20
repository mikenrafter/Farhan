package ly.com.tahaben.notification_filter_data.sound

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StorePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.core.util.GlobalKey
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.sound.ElectionInput
import ly.com.tahaben.notification_filter_domain.sound.MutePolicy
import ly.com.tahaben.notification_filter_domain.sound.SoundChoices
import ly.com.tahaben.notification_filter_domain.sound.SoundControl
import ly.com.tahaben.notification_filter_domain.sound.SoundControlState
import ly.com.tahaben.notification_filter_domain.sound.SoundElectionSettings
import ly.com.tahaben.notification_filter_domain.sound.SuspensionReason

@Singleton
class SoundControlRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val shared: SharedPreferences,
    dataStore: DataStore<StorePreferences>,
) : SoundControl {
    private val privatePreferences = context.getSharedPreferences(SOUND_PREFS, Context.MODE_PRIVATE)
    private val clock = AndroidSoundClock(context)
    private val store = AndroidSoundStateStore(context, clock)
    private val rulePort = AndroidZenRulePort(context)
    private val grantPort = AndroidSoundGrants(context)
    private val appMainSwitchKey = booleanPreferencesKey(GlobalKey.Pref_KEY_APP_MAIN_SWITCH)
    private val appMainSwitch = dataStore.data.map { it[appMainSwitchKey] ?: true }
        .distinctUntilChanged()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileRequests = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean()
    private val controller = SoundControlController(
        context.packageName, rulePort, AndroidBreakthroughChannel(context), grantPort, store, clock,
        AndroidSoundStatus(context),
        switches = { SoundSwitches(isFlagOn(), preferences.isNotificationServiceStatsEnabled(), appMainSwitch.first()) },
        mutePolicy = { MutePolicy.forPilot(systemSounds = shared.getBoolean(SoundElectionSettings.SYSTEM_SOUNDS, true)) },
        setPilotFlag = { enabled -> privatePreferences.edit().putBoolean(ENABLE, enabled).commit() },
    )
    override val state = controller.state

    private val personalHandle = Process.myUserHandle()
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val onFilterSwitch = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == Preferences.KEY_NOTIFICATION_SERVICE_STATS) requestReconcile()
    }

    /** Process entry point after app stores and WorkManager have initialized. */
    fun start() {
        if (Build.VERSION.SDK_INT < 35 || !started.compareAndSet(false, true)) return
        shared.registerOnSharedPreferenceChangeListener(onFilterSwitch)
        val actions = IntentFilter().apply {
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = requestReconcile()
        }, actions, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { appMainSwitch.collect { requestReconcile() } }
        scope.launch { for (request in reconcileRequests) refresh() }
        requestReconcile()
    }

    fun requestReconcile() {
        reconcileRequests.trySend(Unit)
    }

    suspend fun onListenerConnected() = withContext(Dispatchers.IO) {
        controller.onListenerConnected()
        scheduleFallback()
    }

    suspend fun onWorker(generation: Long) {
        controller.onWorker(generation)
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        controller.onAppStart()
        scheduleFallback()
    }

    override suspend fun enable() = withContext(Dispatchers.IO + NonCancellable) {
        controller.enable()
        scheduleFallback()
    }

    override suspend fun off() = withContext(Dispatchers.IO + NonCancellable) {
        controller.off()
        scheduleFallback()
    }

    override fun grants() = grantPort.read()
    override fun exceptionPackages() = preferences.getNotificationFilterExceptionsList()
    override fun effectiveFilter() = rulePort.currentFilter()

    override fun choices(): SoundChoices {
        val values = shared.all
        val apps = values.asSequence()
            .filter { (key, value) -> key.startsWith(SoundElectionSettings.APP_PREFIX) && value == true }
            .map { it.key.removePrefix(SoundElectionSettings.APP_PREFIX) }
            .filter(SoundElectionSettings::isPackageName)
            .toSet()
        val categories = NotificationCategory.entries.filterTo(linkedSetOf()) {
            values[SoundElectionSettings.CATEGORY_PREFIX + it.name] == true
        }
        return SoundChoices(apps, categories, values[SoundElectionSettings.SYSTEM_SOUNDS] != false)
    }

    override suspend fun setAppElected(packageName: String, elected: Boolean) {
        require(SoundElectionSettings.isPackageName(packageName))
        putChoice(SoundElectionSettings.APP_PREFIX + packageName, elected)
    }

    override suspend fun setCategoryElected(category: NotificationCategory, elected: Boolean) =
        putChoice(SoundElectionSettings.CATEGORY_PREFIX + category.name, elected)

    override suspend fun setSystemSoundsAllowed(allowed: Boolean) =
        putChoice(SoundElectionSettings.SYSTEM_SOUNDS, allowed)

    private suspend fun putChoice(key: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        shared.edit().putBoolean(key, enabled).apply()
    }

    fun electionFor(item: NotificationItem): ElectionInput? {
        if (!isFlagOn() || state.value !is SoundControlState.Held) return null
        val configured = shared.getBoolean(SoundElectionSettings.APP_PREFIX + item.packageName, false) ||
            shared.getBoolean(SoundElectionSettings.CATEGORY_PREFIX + item.category.name, false)
        // CLAIM(personal-alert-source): AOSP StatusBarNotification.key() starts with user ID and package.
        // UserHandle.hashCode() returns that ID on AOSP 35 [frameworks/base, android-15.0.0_r1].
        // Require both fields and treat unknown or managed-profile sources as ineligible.
        val personal = !userManager.isManagedProfile &&
            item.id.startsWith("${personalHandle.hashCode()}|${item.packageName}|")
        return ElectionInput(configured, preferences.isPackageInNotificationExceptions(item.packageName), !personal)
    }

    private fun isFlagOn() = Build.VERSION.SDK_INT >= 35 && privatePreferences.getBoolean(ENABLE, false)

    private fun scheduleFallback() {
        if (!started.get()) return
        val intent = store.read()
        val needsRecovery = state.value == SoundControlState.Suspended(SuspensionReason.REMOVAL_FAILED) ||
            state.value == SoundControlState.Suspended(SuspensionReason.STORAGE_FAILED)
        val work = WorkManager.getInstance(context)
        if (intent.desiredOn || intent.ruleId != null || needsRecovery) {
            work.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SoundControlWorker>(15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(WORK_GENERATION to intent.generation))
                    .build(),
            )
        } else {
            work.cancelUniqueWork(WORK_NAME)
        }
    }

    private companion object {
        const val ENABLE = "notification_sound_control_enabled"
        const val WORK_NAME = "farhan-sound-control-reconcile"
    }
}

internal const val SOUND_PREFS = "sound_control"
internal const val WORK_GENERATION = "generation"
