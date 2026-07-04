package ly.com.tahaben.notification_filter_domain.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ly.com.tahaben.core.data.repository.InstalledAppsRepository
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.repository.NotificationRepository
import ly.com.tahaben.notification_filter_domain.use_cases.*
import ly.com.tahaben.notification_filter_domain.use_cases.settings.*
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object NotificationFilterDomainModule {

    @Provides
    @Singleton
    fun provideNotificationUseCases(
        notificationRepository: NotificationRepository,
        serviceUtil: ServiceUtil,
        @ApplicationContext context: Context,
        preferences: Preferences,
        installedAppsRepo: InstalledAppsRepository
    ): NotificationFilterUseCases {
        val setAppPriorityOverride = SetAppPriorityOverride(preferences)
        val registerKnownNotificationSources = RegisterKnownNotificationSources(preferences)
        val scanVisibleNotificationSources = ScanVisibleNotificationSources(
            serviceUtil,
            registerKnownNotificationSources,
        )
        val scanInstalledNotificationSources = ScanInstalledNotificationSources(
            GetInstalledAppsList(installedAppsRepo),
            serviceUtil,
            registerKnownNotificationSources,
        )
        val classifyNotification = ClassifyNotification(preferences)

        return NotificationFilterUseCases(
            CheckIfNotificationServiceIsEnabled(serviceUtil),
            CheckIfNotificationAccessIsGranted(serviceUtil),
            DeleteNotificationFromDB(notificationRepository),
            DeleteNotificationIntentFromHashmap(serviceUtil),
            DeleteAllNotifications(notificationRepository),
            EnableNotificationService(serviceUtil),
            InsertNotificationToDB(notificationRepository),
            GetNotificationsFromDB(notificationRepository),
            StartNotificationService(serviceUtil),
            OpenNotification(serviceUtil, context),
            IsPackageInNotificationException(preferences),
            GetInstalledAppsList(installedAppsRepo),
            AddPackageToNotificationException(preferences, setAppPriorityOverride),
            RemovePackageFromNotificationException(preferences),
            GetNotifyMeHour(preferences),
            GetNotifyMeMinute(preferences),
            SetNotifyMeTime(preferences),
            SetServiceState(preferences),
            ScheduleNotifyMeNotification(serviceUtil),
            CreateNotifyMeNotificationChannel(serviceUtil),
            SetNotifyMeScheduleDate(preferences),
            SaveShouldShowOnBoarding(preferences),
            LoadShouldShowOnBoarding(preferences),
            OpenSettings(serviceUtil),
            CanScheduleExactAlarms(serviceUtil),
            OpenExactAlarmsPermissionScreen(serviceUtil),
            classifyNotification,
            GetNotificationBatches(notificationRepository, preferences),
            GetNotificationDashboardSummary(notificationRepository, preferences),
            MarkNotificationRead(notificationRepository),
            MarkAppNotificationsRead(notificationRepository),
            setAppPriorityOverride,
            GetAppPriorityOverride(preferences),
            GetDashboardSettings(preferences),
            SetDashboardSettings(preferences),
            AddPackageToLowPriority(preferences),
            IsAppPriorityLocked(preferences),
            SetAppPriorityLocked(preferences),
            GetCategoryPriorityOverride(preferences),
            SetCategoryPriorityOverride(preferences),
            GetNotificationSchedule(preferences),
            SetNotificationSchedule(preferences),
            GetCurrentBudgetStatus(preferences),
            ClearBudgetSpend(preferences),
            GetClearedNotifications(notificationRepository),
            MarkNotificationAsCleared(notificationRepository),
            MarkNotificationAsDelivered(notificationRepository),
            GetUndeliveredNotifications(notificationRepository),
            GetPackageNotificationCountSince(notificationRepository),
            GetChannelPriorityOverride(preferences),
            SetChannelPriorityOverride(preferences),
            GetAppPriorityInfoList(
                preferences,
                classifyNotification,
                scanInstalledNotificationSources,
                scanVisibleNotificationSources,
            ),
            GetPriorityDeliverySettings(preferences),
            SetPriorityDeliverySetting(preferences),
            IsDeliveryTestingMode(preferences),
            SetDeliveryTestingMode(preferences),
            registerKnownNotificationSources,
            scanVisibleNotificationSources,
            scanInstalledNotificationSources,
        )
    }

}