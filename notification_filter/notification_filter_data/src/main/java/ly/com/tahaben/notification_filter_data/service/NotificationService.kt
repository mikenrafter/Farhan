package ly.com.tahaben.notification_filter_data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.core.R
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.use_cases.NotificationFilterUseCases
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class NotificationService : NotificationListenerService() {

    @Inject
    lateinit var notificationFilterUseCases: NotificationFilterUseCases

    @Inject
    lateinit var preferences: Preferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deliveryJob: Job? = null

    companion object {
        private val intentHashmap: HashMap<String, PendingIntent> = HashMap()
        private val proxyToOriginalId: HashMap<Int, String> = HashMap()

        const val PROXY_CHANNEL_ID = "farhan_proxy_notifications"
        const val CHATTER_HOUR_THRESHOLD = 10
        const val MAX_HOURLY_PASSTHROUGH = 10

        fun getIntentForNotification(key: String): PendingIntent? = intentHashmap[key]
        fun removeIntentFromHashmap(key: String) { intentHashmap.remove(key) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        createProxyNotificationChannel()
        startDeliveryScheduler()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val isEnabled = runBlocking { notificationFilterUseCases.checkIfNotificationServiceIsEnabled() }
        if (!isEnabled) return

        val notification = sbn?.notification ?: return
        val appPackageName = sbn.packageName ?: ""

        if (notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_CALL ||
            notification.category == Notification.CATEGORY_ALARM ||
            notification.category == Notification.CATEGORY_MISSED_CALL ||
            notification.category == Notification.CATEGORY_SYSTEM
        ) {
            super.onNotificationPosted(sbn)
            return
        }

        if (notificationFilterUseCases.isPackageInNotificationException(appPackageName) ||
            appPackageName == this.packageName
        ) {
            super.onNotificationPosted(sbn)
            return
        }

        cancelNotification(sbn.key)

        val pm: PackageManager = this.packageManager
        val ai: ApplicationInfo? = try {
            pm.getApplicationInfo(appPackageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val applicationName = (if (ai != null) pm.getApplicationLabel(ai) else appPackageName) as String?
        val channelId = notification.channelId

        runBlocking(Dispatchers.IO) {
            val (basePriority, category) = notificationFilterUseCases
                .classifyNotification(notification.category, appPackageName, channelId)

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

            // Auto-lock CRITICAL apps so they stay critical
            if (effectivePriority == NotificationPriority.CRITICAL &&
                preferences.getAppPriorityOverride(appPackageName) == NotificationPriority.CRITICAL
            ) {
                preferences.setAppPriorityLocked(appPackageName, true)
            }

            val item = sbn.toNotificationItem(applicationName, effectivePriority, category, channelId, priorityReason)
            // Bug 2 fix: store intent keyed by item.id (not sbn.key) so postProxyNotification can find it
            notification.contentIntent?.let { intentHashmap[item.id] = it }
            notificationFilterUseCases.insertNotificationToDB(item)

            when {
                effectivePriority == NotificationPriority.CRITICAL -> {
                    postProxyNotification(item.id, item.appName, item.title, item.text)
                    notificationFilterUseCases.markNotificationAsDelivered(item.id)
                }
                effectivePriority == NotificationPriority.HIGH || effectivePriority == NotificationPriority.MEDIUM -> {
                    if (canDeliverNow(effectivePriority)) {
                        postProxyNotification(item.id, item.appName, item.title, item.text)
                        notificationFilterUseCases.markNotificationAsDelivered(item.id)
                        recordPassthrough(effectivePriority)
                    }
                }
                // LOW: queued for bi-hourly delivery only
            }
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

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        val pkg = sbn?.packageName ?: ""
        val key = sbn?.key ?: ""

        if (pkg == this.packageName) {
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
        serviceScope.cancel()
        intentHashmap.clear()
        proxyToOriginalId.clear()
        super.onDestroy()
    }

    private suspend fun applyDynamicAdjustments(
        base: NotificationPriority,
        packageName: String
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

    private fun canDeliverNow(priority: NotificationPriority): Boolean {
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = currentHour * 60 + cal.get(Calendar.MINUTE)

        val windowHour = preferences.getPassthroughWindowHour()
        val hourlyCount = if (windowHour == currentHour) preferences.getHourlyPassthroughCount()
        else {
            preferences.setPassthroughWindowHour(currentHour)
            preferences.setHourlyPassthroughCount(0)
            0
        }
        if (hourlyCount >= MAX_HOURLY_PASSTHROUGH) return false

        val schedule = preferences.getNotificationSchedule()
        val period = schedule.firstOrNull { p ->
            val startMin = p.startHour * 60 + p.startMinute
            val endMin = p.endHour * 60 + p.endMinute
            minuteOfDay in startMin until endMin
        } ?: return false

        if (period.budgetPoints == 0) return false

        val periodStartMinute = period.startHour * 60 + period.startMinute
        val savedStart = preferences.getCurrentBudgetPeriodStartMinute()
        val spentPoints = if (savedStart == periodStartMinute) preferences.getBudgetSpentCurrentPeriod()
        else {
            preferences.setCurrentBudgetPeriodStartMinute(periodStartMinute)
            preferences.setBudgetSpentCurrentPeriod(0)
            0
        }

        return spentPoints + priority.pointCost <= period.budgetPoints
    }

    private fun recordPassthrough(priority: NotificationPriority) {
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = currentHour * 60 + cal.get(Calendar.MINUTE)

        preferences.setHourlyPassthroughCount(preferences.getHourlyPassthroughCount() + 1)

        val schedule = preferences.getNotificationSchedule()
        val period = schedule.firstOrNull { p ->
            val startMin = p.startHour * 60 + p.startMinute
            val endMin = p.endHour * 60 + p.endMinute
            minuteOfDay in startMin until endMin
        }
        if (period != null) {
            preferences.setBudgetSpentCurrentPeriod(
                preferences.getBudgetSpentCurrentPeriod() + priority.pointCost
            )
        }
    }

    private fun postProxyNotification(
        originalId: String,
        appName: String?,
        title: String?,
        text: String?
    ) {
        val proxyId = originalId.hashCode()
        val builder = NotificationCompat.Builder(this, PROXY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_farhan_transparent)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(appName)
            .setAutoCancel(true)
        intentHashmap[originalId]?.let { builder.setContentIntent(it) }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(proxyId, builder.build())
        proxyToOriginalId[proxyId] = originalId
        Timber.d("Proxy posted: $appName")
    }

    private fun startDeliveryScheduler() {
        deliveryJob?.cancel()
        deliveryJob = serviceScope.launch {
            while (true) {
                val cal = Calendar.getInstance()
                val minutesToNextHour = 60 - cal.get(Calendar.MINUTE)
                val msToNextHour = (minutesToNextHour * 60 - cal.get(Calendar.SECOND)) * 1000L
                delay(msToNextHour.coerceAtLeast(60_000L))
                deliverQueuedNotifications()
            }
        }
    }

    private suspend fun deliverQueuedNotifications() {
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = currentHour * 60 + cal.get(Calendar.MINUTE)

        val schedule = preferences.getNotificationSchedule()
        val period = schedule.firstOrNull { p ->
            val startMin = p.startHour * 60 + p.startMinute
            val endMin = p.endHour * 60 + p.endMinute
            minuteOfDay in startMin until endMin
        } ?: return

        if (period.budgetPoints == 0) return

        val periodStartMinute = period.startHour * 60 + period.startMinute
        val savedStart = preferences.getCurrentBudgetPeriodStartMinute()
        var spentPoints = if (savedStart == periodStartMinute) preferences.getBudgetSpentCurrentPeriod()
        else {
            preferences.setCurrentBudgetPeriodStartMinute(periodStartMinute)
            preferences.setBudgetSpentCurrentPeriod(0)
            0
        }

        // Reset hourly rate limit at the top of each delivery tick
        preferences.setPassthroughWindowHour(currentHour)
        preferences.setHourlyPassthroughCount(0)
        var hourlyCount = 0

        val highMediumItems = notificationFilterUseCases.getUndeliveredNotifications(3)
        for (item in highMediumItems) {
            if (hourlyCount >= MAX_HOURLY_PASSTHROUGH) break
            val cost = item.priority.pointCost
            if (spentPoints + cost > period.budgetPoints) continue
            postProxyNotification(item.id, item.appName, item.title, item.text)
            notificationFilterUseCases.markNotificationAsDelivered(item.id)
            spentPoints += cost
            hourlyCount++
        }

        if (currentHour % 2 == 0) {
            val lowItems = notificationFilterUseCases.getUndeliveredNotifications(4)
                .filter { it.priority == NotificationPriority.LOW }
            for (item in lowItems) {
                if (hourlyCount >= MAX_HOURLY_PASSTHROUGH) break
                val cost = item.priority.pointCost
                if (spentPoints + cost > period.budgetPoints) continue
                postProxyNotification(item.id, item.appName, item.title, item.text)
                notificationFilterUseCases.markNotificationAsDelivered(item.id)
                spentPoints += cost
                hourlyCount++
            }
        }

        preferences.setBudgetSpentCurrentPeriod(spentPoints)
        preferences.setHourlyPassthroughCount(hourlyCount)
    }

    private fun createProxyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PROXY_CHANNEL_ID,
                "Farhan — Scheduled Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications delivered by Farhan according to your schedule"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
