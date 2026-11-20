package ly.com.tahaben.notification_filter_data.di

import ly.com.tahaben.notification_filter_data.mapper.toNotificationEntity
import ly.com.tahaben.notification_filter_data.mapper.toNotificationItem
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ly.com.tahaben.notification_filter_data.local.preferences.DefaultPreferences
import ly.com.tahaben.notification_filter_data.local.preferences.KnownPackageSourceStore
import ly.com.tahaben.notification_filter_data.service.ServiceUtilImpl
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationFilterDataModule {

    @Provides
    @Singleton
    fun provideNotificationCore(
        preferences: Preferences,
        dao: ly.com.tahaben.notification_filter_data.local.NotificationDao,
        @ApplicationContext context: Context,
    ): ly.com.tahaben.notification_filter_domain.delivery.NotificationCore {
        val store = object : ly.com.tahaben.notification_filter_domain.schedule.ScheduleBookStore {
            override suspend fun read() = preferences.getScheduleSnapshot()
            override suspend fun commit(snapshot: ly.com.tahaben.notification_filter_domain.schedule.ScheduleSnapshot) = preferences.commitScheduleSnapshot(snapshot)
        }
        val rows = object : ly.com.tahaben.notification_filter_domain.delivery.DeliveryRowStore {
            override suspend fun upsert(item: ly.com.tahaben.notification_filter_domain.model.NotificationItem) =
                ly.com.tahaben.notification_filter_data.service.NotificationDiagnostics.suspending("farhan.inbox.write") { dao.insertNotificationItem(item.toNotificationEntity()) }
            override suspend fun find(id: String) = dao.find(id)?.toNotificationItem()
            override suspend fun markCleared(id: String) = dao.markNotificationAsCleared(id)
            override suspend fun markDelivered(id: String) = dao.markNotificationAsDelivered(id)
            override suspend fun completeSnoozeReturn(id: String) = dao.completeSnoozeReturn(id)
            override suspend fun liveSnoozes() = dao.liveSnoozes().map { it.toNotificationItem() }
            override suspend fun pending(maxPriorityLevel: Int): List<ly.com.tahaben.notification_filter_domain.model.NotificationItem> {
                val preferencesByApp = preferences.getAppQueuePreferences()
                return dao.pendingGroups(maxPriorityLevel).mapNotNull { group ->
                    dao.pendingHead(group.packageName, group.priority, preferencesByApp[group.packageName]?.order == ly.com.tahaben.notification_filter_domain.delivery.QueueOrder.OLDEST_FIRST)?.toNotificationItem()
                }
            }
            override suspend fun invalidateBefore(session: String, order: Long) = dao.invalidateBefore(session, order)
            override suspend fun nextHead(after: ly.com.tahaben.notification_filter_domain.model.NotificationItem) =
                dao.pendingHead(after.packageName, after.priority.level,
                    preferences.getAppQueuePreferences()[after.packageName]?.order == ly.com.tahaben.notification_filter_domain.delivery.QueueOrder.OLDEST_FIRST)?.toNotificationItem()
        }
        val budget = object : ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetStore {
            override suspend fun read() = ly.com.tahaben.notification_filter_data.service.NotificationDiagnostics.trace("farhan.budget.read") { preferences.getScheduleSnapshot().budget }
            override suspend fun write(state: ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState) = preferences.commitScheduleSnapshot(preferences.getScheduleSnapshot().copy(budget = state))
            override suspend fun appendSpend(entry: ly.com.tahaben.notification_filter_domain.model.BudgetSpendEntry) = preferences.addBudgetSpendEntry(entry)
        }
        val poster = object : ly.com.tahaben.notification_filter_domain.delivery.ProxyNotificationPoster {
            override suspend fun post(item: ly.com.tahaben.notification_filter_domain.model.NotificationItem) =
                ly.com.tahaben.notification_filter_data.service.NotificationService.postProxyNotification(context, item, silent = false,
                    useSourceIcon = preferences.isExperimentEnabled(ly.com.tahaben.notification_filter_domain.delivery.Experiments.SOURCE_ICON))
            override suspend fun refresh(item: ly.com.tahaben.notification_filter_domain.model.NotificationItem) =
                ly.com.tahaben.notification_filter_data.service.NotificationService.postProxyNotification(context, item, silent = true,
                    useSourceIcon = preferences.isExperimentEnabled(ly.com.tahaben.notification_filter_domain.delivery.Experiments.SOURCE_ICON))
        }
        val environment = object : ly.com.tahaben.notification_filter_domain.delivery.DeliveryEnvironment {
            override fun schedule() = preferences.getNotificationSchedule()
            override fun scheduleAt(instant: java.time.Instant, zone: java.time.ZoneId) =
                preferences.getScheduleSnapshot().book.scheduleAt(instant, zone)
            override fun settings() = preferences.getPriorityDeliverySettings()
            override fun timeContext() = ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.current(preferences.isDeliveryTestingMode())
            override fun queuePreferences() = preferences.getAppQueuePreferences()
            override fun activeSourceKeys() = ly.com.tahaben.notification_filter_data.service.NotificationService.activeSourcePostTimes().keys
        }
        return ly.com.tahaben.notification_filter_domain.delivery.NotificationCore(store, rows, budget, poster, environment,
            ly.com.tahaben.notification_filter_domain.delivery.DeliveryClock.systemClock) { preferences.clearAllBudgetSpend() }
    }

    @Provides @Singleton
    fun provideScheduleAutomation(core: ly.com.tahaben.notification_filter_domain.delivery.NotificationCore,
        credentials: ly.com.tahaben.notification_filter_data.local.preferences.ScheduleAutomationCredentials) =
        ly.com.tahaben.notification_filter_domain.schedule.ScheduleAutomation(core.writer, credentials)

    @Provides @Singleton
    fun provideAutomationCredentialOwner(credentials: ly.com.tahaben.notification_filter_data.local.preferences.ScheduleAutomationCredentials):
        ly.com.tahaben.notification_filter_domain.schedule.AutomationCredentialOwner = credentials

    @Provides @Singleton
    fun provideAutomationReceiptStore(store: ly.com.tahaben.notification_filter_data.automation.SharedPreferencesReceiptStore):
        ly.com.tahaben.notification_filter_domain.schedule.AutomationReceiptStore = store

    @Provides @Singleton
    fun provideExperimentEventLog(): ly.com.tahaben.notification_filter_domain.delivery.ExperimentEventLog =
        ly.com.tahaben.notification_filter_domain.delivery.InMemoryExperimentEventLog()

    @Provides @Singleton
    fun provideCoordinator(core: ly.com.tahaben.notification_filter_domain.delivery.NotificationCore) = core.coordinator

    @Provides @Singleton
    fun provideScheduleWriter(core: ly.com.tahaben.notification_filter_domain.delivery.NotificationCore) = core.writer

    @Provides
    @Singleton
    fun provideKnownPackageSourceRegistry(store: KnownPackageSourceStore): KnownPackageSourceRegistry = store

    @Provides
    @Singleton
    fun provideServiceUtil(
        @ApplicationContext context: Context,
        preferences: Preferences
    ): ServiceUtil {
        return ServiceUtilImpl(context, preferences)
    }


    @Provides
    @Singleton
    fun providePreferences(sharedPreferences: SharedPreferences, dataStore: DataStore<androidx.datastore.preferences.core.Preferences>): Preferences {
        return DefaultPreferences(sharedPreferences, dataStore)
    }
}