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
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as AndroidXRemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.core.R
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val intentHashmap: HashMap<String, PendingIntent> = HashMap()
        private val proxyToOriginalId: HashMap<Int, String> = HashMap()
        private val actionsHashmap: HashMap<String, List<CapturedAction>> = HashMap()

        @Volatile
        private var connectedInstance: NotificationService? = null

        const val PROXY_CHANNEL_ID = "farhan_proxy_notifications"
        const val CHATTER_HOUR_THRESHOLD = 10

        fun getIntentForNotification(key: String): PendingIntent? = intentHashmap[key]

        fun removeIntentFromHashmap(key: String) {
            intentHashmap.remove(key)
        }

        fun getActionsForNotification(id: String): List<CapturedAction> = actionsHashmap[id] ?: emptyList()

        fun removeActionsForNotification(id: String) {
            actionsHashmap.remove(id)
        }

        fun postProxyNotification(
            context: Context,
            originalId: String,
            appName: String?,
            title: String?,
            text: String?,
        ) {
            val proxyId = originalId.hashCode()
            val displayTitle = buildString {
                append("Farhan · ")
                if (!title.isNullOrBlank()) append(title) else append(appName ?: "Notification")
            }
            val builder = NotificationCompat.Builder(context, PROXY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_farhan_transparent)
                .setContentTitle(displayTitle)
                .setContentText(text)
                .setSubText(appName)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            intentHashmap[originalId]?.let { builder.setContentIntent(it) }

            actionsHashmap[originalId]?.forEach { captured ->
                val actionBuilder = NotificationCompat.Action.Builder(
                    0, captured.title, captured.actionIntent
                )
                captured.remoteInputs?.forEach { ri ->
                    actionBuilder.addRemoteInput(
                        AndroidXRemoteInput.Builder(ri.resultKey)
                            .setLabel(ri.label)
                            .setAllowFreeFormInput(ri.allowFreeFormInput)
                            .build()
                    )
                }
                builder.addAction(actionBuilder.build())
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(proxyId, builder.build())
            proxyToOriginalId[proxyId] = originalId
            Timber.i("Farhan delivered proxy notification for $appName: $displayTitle")
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
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        notificationFilterUseCases.registerKnownNotificationSources(
            listOf(
                VisibleNotificationSource(
                    packageName = pkg,
                    appName = appName,
                    channelId = channel.id,
                    androidCategory = null,
                )
            )
        )
        super.onNotificationChannelModified(pkg, user, channel, modificationType)
    }

    override fun onListenerDisconnected() {
        connectedInstance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val isEnabled = runBlocking { notificationFilterUseCases.checkIfNotificationServiceIsEnabled() }
        if (!isEnabled) return

        val notification = sbn?.notification ?: return
        val appPackageName = sbn.packageName ?: ""

        if (isSystemExemptCategory(notification.category)) {
            super.onNotificationPosted(sbn)
            return
        }

        if (appPackageName == packageName) {
            super.onNotificationPosted(sbn)
            return
        }

        val pm: PackageManager = packageManager
        val ai: ApplicationInfo? = try {
            pm.getApplicationInfo(appPackageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val applicationName = (if (ai != null) pm.getApplicationLabel(ai) else appPackageName) as String?
        val channelId = notification.channelId

        val passThrough = runBlocking(Dispatchers.IO) {
            notificationFilterUseCases.registerKnownNotificationSources(
                listOf(
                    VisibleNotificationSource(
                        packageName = appPackageName,
                        appName = applicationName,
                        channelId = channelId,
                        androidCategory = notification.category,
                    )
                )
            )

            val (basePriority, category) = notificationFilterUseCases
                .classifyNotification(notification.category, appPackageName, channelId)

            val isException = notificationFilterUseCases.isPackageInNotificationException(appPackageName)
            val isLocked = notificationFilterUseCases.isAppPriorityLocked(appPackageName)
            val hasChannelOverride = channelId != null &&
                preferences.getChannelPriorityOverride(appPackageName, channelId) != null
            val hasAppOverride = preferences.getAppPriorityOverride(appPackageName) != null

            val (effectivePriority, priorityReason) = when {
                hasChannelOverride -> Pair(basePriority, "Set by user")
                isLocked -> Pair(basePriority, "Locked by user")
                hasAppOverride -> Pair(basePriority, "Set by user")
                else -> applyDynamicAdjustments(basePriority, appPackageName)
            }

            val shouldPassThrough = isException || effectivePriority == NotificationPriority.CRITICAL
            val item = captureNotificationToInbox(
                sbn = sbn,
                applicationName = applicationName,
                effectivePriority = effectivePriority,
                category = category,
                channelId = channelId,
                priorityReason = priorityReason,
                deliveredToSystemTray = shouldPassThrough,
            )

            if (shouldPassThrough) {
                true
            } else {
                cancelNotification(sbn.key)
                deliveryEngine.tryImmediateDelivery(this@NotificationService, item, effectivePriority)
                false
            }
        }

        if (passThrough) {
            Timber.d("${sbn.packageName} passed through to system tray and recorded in inbox")
            super.onNotificationPosted(sbn)
            return
        }

        if (notificationFilterUseCases.getNotifyMeHour() != -1) {
            notificationFilterUseCases.createNotifyMeNotificationChannel()
            notificationFilterUseCases.scheduleNotifyMeNotification(
                notificationFilterUseCases.getNotifyMeHour(),
                notificationFilterUseCases.getNotifyMeMinute()
            )
        }

        Timber.d("${sbn.packageName} intercepted")
    }

    private suspend fun captureNotificationToInbox(
        sbn: StatusBarNotification,
        applicationName: String?,
        effectivePriority: NotificationPriority,
        category: NotificationCategory,
        channelId: String?,
        priorityReason: String,
        deliveredToSystemTray: Boolean,
    ): NotificationItem {
        val notification = sbn.notification
            ?: return sbn.toNotificationItem(
                applicationName,
                effectivePriority,
                category,
                channelId,
                priorityReason,
                deliveredToSystemTray,
            )

        val item = sbn.toNotificationItem(
            applicationName,
            effectivePriority,
            category,
            channelId,
            priorityReason,
            deliveredToSystemTray,
        )
        notification.contentIntent?.let { intentHashmap[item.id] = it }
        notification.actions?.let { actions ->
            val captured = actions.mapNotNull { action ->
                val intent = action.actionIntent ?: return@mapNotNull null
                CapturedAction(
                    title = action.title ?: "",
                    actionIntent = intent,
                    remoteInputs = action.getRemoteInputs()?.takeIf { it.isNotEmpty() }
                )
            }
            if (captured.isNotEmpty()) actionsHashmap[item.id] = captured
        }
        notificationFilterUseCases.insertNotificationToDB(item)
        return item
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val pkg = sbn?.packageName ?: ""
        val key = sbn?.key ?: ""

        if (pkg == packageName) {
            val proxyId = sbn?.id ?: 0
            val originalId = proxyToOriginalId.remove(proxyId)
            if (originalId != null && (reason == REASON_CANCEL || reason == REASON_CLICK)) {
                serviceScope.launch {
                    notificationFilterUseCases.markNotificationAsCleared(originalId)
                }
            }
        } else if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            serviceScope.launch {
                notificationFilterUseCases.markNotificationAsCleared(key)
            }
        }

        super.onNotificationRemoved(sbn, rankingMap, reason)
    }

    override fun onDestroy() {
        connectedInstance = null
        serviceScope.cancel()
        intentHashmap.clear()
        actionsHashmap.clear()
        proxyToOriginalId.clear()
        super.onDestroy()
    }

    private suspend fun applyDynamicAdjustments(
        base: NotificationPriority,
        packageName: String,
    ): Pair<NotificationPriority, String> {
        val now = System.currentTimeMillis()
        val floodThreshold = notificationFilterUseCases.getDashboardSettings.getFloodThreshold()
        val recentCount = notificationFilterUseCases.getPackageNotificationCountSince(packageName, now - 600_000L)

        if (recentCount >= floodThreshold) {
            return Pair(base.degraded(2), "Flood – $recentCount in 10 min")
        }

        val hourlyCount = notificationFilterUseCases.getPackageNotificationCountSince(packageName, now - 3_600_000L)
        if (hourlyCount >= CHATTER_HOUR_THRESHOLD) {
            return Pair(base.degraded(1), "Chatter – ${hourlyCount}/hr")
        }

        return Pair(base, "Category default")
    }

    private fun isSystemExemptCategory(category: String?): Boolean =
        category == Notification.CATEGORY_SERVICE ||
            category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_MISSED_CALL ||
            category == Notification.CATEGORY_SYSTEM

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
