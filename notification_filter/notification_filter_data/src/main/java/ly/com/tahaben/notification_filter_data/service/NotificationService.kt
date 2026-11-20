package ly.com.tahaben.notification_filter_data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput as PlatformRemoteInput
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as AndroidXRemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import ly.com.tahaben.notification_filter_data.local.NotificationDao
import ly.com.tahaben.notification_filter_data.mapper.toNotificationEntity
import ly.com.tahaben.notification_filter_domain.model.PriorityDeliverySetting
import ly.com.tahaben.core.R
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.delivery.*
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NotificationService : NotificationListenerService() {

    @Inject
    lateinit var notificationFilterUseCases: NotificationFilterUseCases

    @Inject
    lateinit var preferences: Preferences

    @Inject
    lateinit var deliveryEngine: NotificationDeliveryEngine

    @Inject
    lateinit var rateTracker: PackageNotificationRateTracker

    @Inject
    lateinit var knownPackageSourceRegistry: KnownPackageSourceRegistry

    @Inject lateinit var coordinator: NotificationDeliveryCoordinator

    @Inject lateinit var experimentLog: ExperimentEventLog

    @Inject lateinit var notificationDao: NotificationDao

    private val snoozeWork = Mutex()
    private val summaryCancellations = SnoozeSummaryCancellations(SystemClock::elapsedRealtime)
    private val snoozeHandler by lazy {
        SnoozeHandler(
            platform = object : SnoozePlatform {
                override fun snooze(key: String, millis: Long) {
                    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    snoozeNotification(key, millis)
                }
                override fun snoozedKeys(): Set<String> =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        snoozedNotifications.orEmpty().mapTo(hashSetOf()) { it.key }
                    } else emptySet()
                override fun activePostTimes() = activeSourcePostTimes()
                override fun cancelVisible(key: String) = cancelNotification(key)
            },
            events = experimentLog,
            budget = SnoozeReturnBudget(coordinator::settleSnoozeReturn),
            ledger = object : SnoozeLedger {
                override suspend fun find(key: String) = notificationDao.find(key)?.toNotificationItem()
                override suspend fun liveRows() = notificationDao.liveSnoozes().map { it.toNotificationItem() }
                override suspend fun clearedRows() = notificationDao.clearedSnoozes().map { it.toNotificationItem() }
                override suspend fun closeClearedSnooze(key: String, deadline: Long) =
                    notificationDao.closeClearedSnooze(key, deadline)
                override suspend fun clearSourceRemoval(key: String, postTime: Long) =
                    notificationDao.clearSnoozeSourceRemoval(key, postTime)
                override suspend fun upsert(item: NotificationItem) = notificationDao.insertNotificationItem(item.toNotificationEntity())
            },
            bootCount = { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) },
            proxyArrival = { row -> coordinator.onArrival(row, row.priority) },
            refreshProxy = { row -> coordinator.refreshSnoozeProxy(row) },
            inputAt = ::returnInput,
        )
    }
    private val snoozeIntake by lazy { SnoozeReturnIntake(snoozeHandler) }
    private val snoozeRouting by lazy {
        SnoozeServiceRouting(coordinator, snoozeHandler, { notificationDao.find(it)?.toNotificationItem() }, ::returnInput)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class PostedFields(
        val item: NotificationItem, val androidCategory: String?, val exclusionFlags: SnoozeExclusionFlags,
        val groupKey: String?, val androidGroupKey: String,
        val contentIntent: PendingIntent?, val actions: List<CapturedAction>, val order: Long,
        /** Only captured while the source-icon experiment is on. */
        val smallIcon: android.graphics.drawable.Icon? = null,
    )
    private sealed interface Work {
        data class Posted(val fields: PostedFields) : Work
        data object Return : Work
        data class CustodyCheck(val key: String, val postTime: Long) : Work
        data class NativeRemoval(
            val key: String,
            val reason: Int,
            val at: Long,
            val farhanCancelledSummary: Boolean,
            val postTime: Long,
        ) : Work
        data class Removed(val id: String, val reason: NotificationRemovalReason, val order: Long) : Work
        data class ChannelChanged(val pkg: String, val channel: String) : Work
    }
    private val intake = ListenerIntake<Work>(128) { NotificationDiagnostics.counter("farhan.intake.depth", it) }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            intake.consume { work ->
                try {
                    snoozeWork.withLock {
                        if (work is Work.NativeRemoval && work.reason == REASON_GROUP_SUMMARY_CANCELED &&
                            !work.farhanCancelledSummary
                        ) {
                            processNativeRemoval(work)
                            drainSnoozeReturns()
                            return@withLock
                        }
                        drainSnoozeReturns()
                        when (work) {
                            Work.Return -> Unit
                            is Work.CustodyCheck -> snoozeRouting.handleStoredPost(work.key, work.postTime, Instant.now())
                            is Work.NativeRemoval -> processNativeRemoval(work)
                            is Work.Posted -> processNotificationPosted(work.fields)
                            is Work.Removed -> coordinator.onRemoved(work.id, work.reason, System.currentTimeMillis(), work.order)
                            is Work.ChannelChanged -> notificationFilterUseCases.registerKnownNotificationSources(listOf(
                                VisibleNotificationSource(work.pkg, resolveApplicationName(work.pkg), work.channel, null)))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { NotificationDiagnostics.increment("farhan.intake.failure") }
            }
        }
    }

    companion object {
        private data class Actions(val tap: PendingIntent?, val replies: List<CapturedAction>, val smallIcon: android.graphics.drawable.Icon? = null)
        private val actionStore = NotificationActionStore<Actions>()
        private const val PROXY_TAG = "farhan:"

        @Volatile
        private var connectedInstance: NotificationService? = null

        const val PROXY_CHANNEL_ID = "farhan_proxy_notifications"
        const val CHATTER_HOUR_THRESHOLD = 10
        const val MAX_LIVE_PROXIES = 1024

        fun getIntentForNotification(key: String): PendingIntent? = actionStore.get(key)?.tap
        fun removeIntentFromHashmap(key: String) { actionStore.remove(key) }
        fun getActionsForNotification(id: String): List<CapturedAction> = actionStore.get(id)?.replies.orEmpty()
        fun removeActionsForNotification(id: String) { actionStore.remove(id) }
        fun hasNativeSnoozes(): Boolean = connectedInstance?.snoozeHandler?.needsReconciliation() == true

        suspend fun reconcileNativeSnoozes() {
            connectedInstance?.reconcileSnoozes()
        }

        fun activeSourcePostTimes(): Map<String, Long> = connectedInstance?.activeNotifications.orEmpty()
            .filter { it.packageName != connectedInstance?.packageName }.associate { it.key to it.postTime }

        fun cancelProxy(context: Context, id: String) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PROXY_TAG + id, 1)
            actionStore.remove(id)
        }
        fun cancelAllProxies(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.activeNotifications.filter { it.tag?.startsWith(PROXY_TAG) == true }.forEach { manager.cancel(it.tag, it.id) }
            clearActions()
        }
        fun clearActions() { actionStore.clear(); NotificationDiagnostics.counter("farhan.actions.retained", 0) }

        fun postProxyNotification(context: Context, item: NotificationItem, silent: Boolean, useSourceIcon: Boolean = false): PostResult = NotificationDiagnostics.trace("farhan.delivery.post") {
            val capabilities = actionStore.get(item.id)
            val builder = NotificationCompat.Builder(context, PROXY_CHANNEL_ID)
                .setContentTitle("Farhan · " + (item.title?.takeIf { it.isNotBlank() } ?: item.appName ?: "Notification"))
                .setContentText(item.text).setSubText(item.appName).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setOnlyAlertOnce(true).setSilent(silent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The source icon is an opt-in experiment; Farhan's icon is the fallback. Identity and channel stay Farhan's.
            val sourceIcon = if (useSourceIcon) usableSourceIcon(context, capabilities?.smallIcon) else null
            if (sourceIcon != null) builder.setSmallIcon(sourceIcon) else builder.setSmallIcon(R.drawable.ic_farhan_transparent)
            if (!silent) builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            val tap = capabilities?.tap ?: context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                PendingIntent.getActivity(context, item.id.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            tap?.let { builder.setContentIntent(it) }
            capabilities?.replies?.forEach { captured ->
                val action = NotificationCompat.Action.Builder(0, captured.title, captured.actionIntent)
                captured.remoteInputs?.forEach { input -> action.addRemoteInput(AndroidXRemoteInput.Builder(input.resultKey)
                    .setLabel(input.label).setAllowFreeFormInput(input.allowFreeFormInput).build()) }
                builder.addAction(action.build())
            }
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                PostResult.Failed("notification_permission_denied")
            } else {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) PostResult.Failed("notifications_disabled")
                else if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(PROXY_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)
                    PostResult.Failed("notification_channel_disabled")
                else {
                    val proxies = manager.activeNotifications.filter { it.tag?.startsWith(PROXY_TAG) == true }
                    if (proxies.size >= MAX_LIVE_PROXIES && proxies.none { it.tag == PROXY_TAG + item.id }) PostResult.Failed("proxy_capacity_reached")
                    else { manager.notify(PROXY_TAG + item.id, 1, builder.build()); PostResult.Posted }
                }
            }
        }

        /** A resource-type icon that loads, or null. Bitmap and URI icons are not trusted; the caller falls back to Farhan's. */
        private fun usableSourceIcon(context: Context, icon: android.graphics.drawable.Icon?): androidx.core.graphics.drawable.IconCompat? {
            if (icon == null || Build.VERSION.SDK_INT < 28) return null
            return try {
                if (icon.type == android.graphics.drawable.Icon.TYPE_RESOURCE && icon.loadDrawable(context) != null)
                    androidx.core.graphics.drawable.IconCompat.createFromIcon(context, icon) else null
            } catch (_: Exception) { null }
        }

        fun getVisibleNotificationSources(): List<VisibleNotificationSource> {
            val service = connectedInstance ?: return emptyList()
            return service.activeNotifications?.mapNotNull { sbn ->
                val notification = sbn.notification ?: return@mapNotNull null
                val pm = service.packageManager
                val appName = try {
                    val ai = pm.getApplicationInfo(sbn.packageName, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    sbn.packageName
                }
                VisibleNotificationSource(
                    packageName = sbn.packageName,
                    appName = appName,
                    channelId = notification.channelId,
                    androidCategory = notification.category,
                )
            }?.distinctBy { "${it.packageName}:${it.channelId}:${it.androidCategory}" }.orEmpty()
        }

        fun getNotificationChannelsForPackage(packageName: String): List<String> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
            val service = connectedInstance ?: return emptyList()
            return try {
                service.getNotificationChannels(packageName, Process.myUserHandle())
                    .orEmpty()
                    .mapNotNull { channel -> channel.id.takeIf { it.isNotBlank() } }
                    .distinct()
            } catch (e: SecurityException) {
                Timber.w("Cannot read notification channels for $packageName: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Timber.w(e, "Failed to read notification channels for $packageName")
                emptyList()
            }
        }

        data class CapturedAction(
            val title: CharSequence,
            val actionIntent: PendingIntent,
            val remoteInputs: Array<PlatformRemoteInput>? = null,
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedInstance = this
        createProxyNotificationChannel()
        serviceScope.launch {
            deliveryEngine.maintainDeliverySchedule(this@NotificationService)
        }
    }

    override fun onNotificationChannelModified(
        pkg: String,
        user: UserHandle,
        channel: NotificationChannel,
        modificationType: Int,
    ) {
        if (modificationType == NOTIFICATION_CHANNEL_OR_GROUP_DELETED) return
        intake.offer(Work.ChannelChanged(pkg, channel.id))
        super.onNotificationChannelModified(pkg, user, channel, modificationType)
    }

    override fun onListenerDisconnected() {
        connectedInstance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        snoozeIntake.routePosted(sbn.key, sbn.postTime, offer = { intake.offer(Work.Return) }) {
            if (preferences.isNotificationServiceStatsEnabled()) offerFreshPost(sbn)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intake.offer(Work.CustodyCheck(sbn.key, sbn.postTime))
            }
        }
    }

    private fun offerFreshPost(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val groupKey = notification.group
        val flags = SnoozeExclusionFlags.fromNotification(notification.flags, notification.category, groupKey)
        val order = coordinator.captureEvent(sbn.key)
        // Copy only relevant fields. The bounded mailbox never retains StatusBarNotification/Notification.
        val item = NotificationItem(sbn.key, null,
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(1024),
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(16384),
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sbn.postTime)),
            sbn.packageName, timestamp = sbn.postTime, channelId = notification.channelId)
        val actions = notification.actions.orEmpty().take(8).mapNotNull { action ->
            val intent = action.actionIntent ?: return@mapNotNull null
            CapturedAction(action.title?.toString()?.take(128).orEmpty(), intent, action.remoteInputs?.take(8)?.toTypedArray())
        }
        val smallIcon = if (preferences.isExperimentEnabled(Experiments.SOURCE_ICON)) notification.smallIcon else null
        val fields = PostedFields(item, notification.category, flags, groupKey, sbn.groupKey,
            notification.contentIntent, actions, order, smallIcon)
        if (!intake.offer(Work.Posted(fields))) {
            // Invalidate older queued updates to this key; this new notification remains in the source tray.
            coordinator.captureEvent(item.id, bypassed = true)
            actionStore.remove(item.id)
            NotificationDiagnostics.increment("farhan.intake.overflow")
        }
    }

    private suspend fun returnInput(now: Instant) = SnoozeReturnInput(
        snoozeEnabled = preferences.isExperimentEnabled(Experiments.SNOOZE),
        filterEnabled = snoozeReturnFilterEnabled(preferences),
        now = now,
        zone = DeliveryClock.systemClock.zone,
        testingMode = preferences.isDeliveryTestingMode(),
    )

    private suspend fun drainSnoozeReturns() {
        try {
            snoozeIntake.drain { snoozeRouting.settle(it, Instant.now()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationDiagnostics.increment("farhan.snooze.return.failure")
        }
    }

    private suspend fun reconcileSnoozes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        snoozeWork.withLock {
            val now = Instant.now()
            val input = returnInput(now)
            val previous = previousSnoozeTick(now, input.zone, input.testingMode)
            drainSnoozeReturns()
            snoozeHandler.onBatchTick(now, previous)
        }
    }

    private suspend fun processNotificationPosted(fields: PostedFields) {
        // A callback can arrive before the scan restores the binder-side cache.
        if (snoozeRouting.handleStoredPost(fields.item, fields.order, Instant.now())) return
        if (!notificationFilterUseCases.checkIfNotificationServiceIsEnabled()) return
        val pkg = fields.item.packageName
        val name = knownPackageSourceRegistry.get(pkg)?.appName ?: resolveApplicationName(pkg)
        notificationFilterUseCases.registerKnownNotificationSources(listOf(VisibleNotificationSource(pkg, name, fields.item.channelId, fields.androidCategory)))
        val source = knownPackageSourceRegistry.get(pkg)
        val (base, category) = NotificationDiagnostics.trace("farhan.classification") {
            notificationFilterUseCases.classifyNotification(fields.androidCategory, pkg, fields.item.channelId,
                source?.appCategoryCode, source?.androidCategories.orEmpty())
        }
        val channelOverride = fields.item.channelId?.let { preferences.getChannelPriorityOffset(pkg, it) != 0 } == true
        val locked = preferences.isAppPriorityLocked(pkg)
        val overridden = preferences.getAppPriorityOverride(pkg) != null
        val (priority, reason) = when {
            channelOverride || overridden -> base to "Set by user"
            locked -> base to "Locked by user"
            else -> applyDynamicAdjustments(base, pkg)
        }
        val excepted = preferences.isPackageInNotificationExceptions(pkg)
        val passThrough = fields.exclusionFlags.isSystemExemptCategory || excepted || priority == NotificationPriority.CRITICAL
        val item = fields.item.copy(appName = name, priority = priority, category = category, priorityReason = reason)
        val captureActions = {
            actionStore.put(item.id, Actions(fields.contentIntent, fields.actions, fields.smallIcon))
            NotificationDiagnostics.counter("farhan.actions.retained", actionStore.size())
        }
        val snoozeEnabled = preferences.isExperimentEnabled(Experiments.SNOOZE)
        val handled = routeSnoozeAdmission(snoozeEnabled, fields.exclusionFlags, admit = {
            if (activeSourcePostTimes()[item.id] != item.timestamp) return@routeSnoozeAdmission false
            val setting = preferences.getPriorityDeliverySettings()[priority] ?: PriorityDeliverySetting.defaultFor(priority)
            val filterEnabled = snoozeReturnFilterEnabled(preferences)
            val testingMode = preferences.isDeliveryTestingMode()
            coordinator.withSnoozeAdmission(item.id, fields.order) { liveRows, budget ->
                if (activeSourcePostTimes()[item.id] != item.timestamp) return@withSnoozeAdmission false
                val previous = notificationDao.find(item.id)
                val context = SnoozeInput(
                    packageName = pkg, ownPackageName = packageName, snoozeEnabled = snoozeEnabled,
                    filterEnabled = filterEnabled, platformSupportsSnooze = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                    setting = setting, now = Instant.now(), zone = DeliveryClock.systemClock.zone, testingMode = testingMode,
                    priority = priority, exclusionFlags = fields.exclusionFlags, isPassthrough = passThrough,
                    isExceptedPackage = excepted, alreadyDelivered = previous?.isDelivered == true && !previous.isCleared,
                    scheduleAt = { at, zone -> preferences.getScheduleSnapshot().book.scheduleAt(at, zone) },
                    liveSnoozes = liveRows.size, budget = budget, ownedRows = liveRows,
                    // CLAIM(snooze-group-device): grouped children return with their summary [F1-F3, F5 hidden-child].
                    groupSnoozeVerified = true,
                )
                snoozeHandler.onPosted(item.copy(snoozeGroupKey = fields.groupKey), context)
            }
        }, onAdmitted = captureActions)
        if (handled) return
        val outcome = coordinator.onArrival(item, priority, fields.order, passThrough, captureActions,
            sourceStillCurrent = { activeSourcePostTimes()[item.id] == item.timestamp })
        if (!passThrough && outcome !is DeliveryOutcome.Cleared && activeSourcePostTimes()[item.id] == item.timestamp) {
            if (fields.exclusionFlags.isGroupSummary) {
                summaryCancellations.requested(fields.androidGroupKey, item.id, item.timestamp)
            }
            cancelNotification(item.id)
        }
        if (outcome is DeliveryOutcome.Cleared) actionStore.remove(item.id)
        if (!passThrough && notificationFilterUseCases.getNotifyMeHour() != -1) {
            notificationFilterUseCases.createNotifyMeNotificationChannel()
            notificationFilterUseCases.scheduleNotifyMeNotification(notificationFilterUseCases.getNotifyMeHour(), notificationFilterUseCases.getNotifyMeMinute())
        }
    }

    private fun resolveApplicationName(appPackageName: String): String? {
        val pm = packageManager
        val ai: ApplicationInfo? = try {
            pm.getApplicationInfo(appPackageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return (if (ai != null) pm.getApplicationLabel(ai) else appPackageName) as String?
    }

    private suspend fun processNativeRemoval(removal: Work.NativeRemoval) {
        snoozeRouting.handleRemoval(removal.key, removal.reason, removal.at,
            removal.farhanCancelledSummary, removal.postTime)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn != null && sbn.packageName != packageName) {
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
                summaryCancellations.summaryRemoved(sbn.groupKey, sbn.key, sbn.postTime, reason == REASON_LISTENER_CANCEL)
            }
            if (reason == REASON_SNOOZED && snoozeHandler.owns(sbn.key) || reason == REASON_GROUP_SUMMARY_CANCELED) {
                val collateral = reason == REASON_GROUP_SUMMARY_CANCELED &&
                    summaryCancellations.causedCascade(sbn.groupKey)
                if (reason == REASON_GROUP_SUMMARY_CANCELED && !collateral) {
                    coordinator.captureEvent(sbn.key, removed = true)
                    actionStore.remove(sbn.key)
                }
                val removal = Work.NativeRemoval(sbn.key, reason, System.currentTimeMillis(),
                    collateral, sbn.postTime)
                if (!intake.offer(removal)) {
                    serviceScope.launch {
                        snoozeWork.withLock { processNativeRemoval(removal) }
                    }
                }
            }
        }
        if (sbn != null) {
            val ownProxy = sbn.packageName == packageName && sbn.tag?.startsWith(PROXY_TAG) == true
            val originalId = if (ownProxy) sbn.tag!!.removePrefix(PROXY_TAG) else sbn.key
            val removal = NotificationRemovalMapper.fromReason(reason)
            if (removal != null && (ownProxy || sbn.packageName != packageName)) {
                // A removal remains visible to batch authorization even when the bounded mailbox is full.
                val order = coordinator.captureEvent(originalId, removed = true)
                actionStore.remove(originalId)
                intake.offer(Work.Removed(originalId, removal, order))
                NotificationDiagnostics.counter("farhan.actions.retained", actionStore.size())
            }
        }
        super.onNotificationRemoved(sbn, rankingMap, reason)
    }

    override fun onDestroy() {
        connectedInstance = null
        serviceScope.cancel()
        intake.close()
        clearActions()
        super.onDestroy()
    }

    private fun applyDynamicAdjustments(
        base: NotificationPriority,
        packageName: String,
    ): Pair<NotificationPriority, String> {
        val counts = rateTracker.record(packageName)
        val floodThreshold = notificationFilterUseCases.getDashboardSettings.getFloodThreshold()

        if (counts.lastTenMinutes >= floodThreshold) {
            return Pair(base.degraded(2), "Flood – ${counts.lastTenMinutes} in 10 min")
        }

        if (counts.lastHour >= CHATTER_HOUR_THRESHOLD) {
            return Pair(base.degraded(1), "Chatter – ${counts.lastHour}/hr")
        }

        return Pair(base, "Category default")
    }

    private fun createProxyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PROXY_CHANNEL_ID,
                "Farhan — Delivered Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications delivered by Farhan according to your schedule"
                enableVibration(true)
                enableLights(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
