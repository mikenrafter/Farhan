package ly.com.tahaben.usage_overview_presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ly.com.tahaben.usage_overview_domain.model.UsageDataItem
import ly.com.tahaben.usage_overview_domain.preferences.Preferences
import ly.com.tahaben.usage_overview_domain.repository.UsageRepository
import ly.com.tahaben.usage_overview_domain.repository.WorkerRepository
import ly.com.tahaben.usage_overview_domain.use_case.CacheUsageDataForDate
import ly.com.tahaben.usage_overview_domain.use_case.CalculateUsageDuration
import ly.com.tahaben.usage_overview_domain.use_case.DeleteCacheForDay
import ly.com.tahaben.usage_overview_domain.use_case.FilterDuration
import ly.com.tahaben.usage_overview_domain.use_case.FilterUsageEvents
import ly.com.tahaben.usage_overview_domain.use_case.GetDurationFromMilliseconds
import ly.com.tahaben.usage_overview_domain.use_case.GetEnabledUsageReports
import ly.com.tahaben.usage_overview_domain.use_case.GetOwnPackageName
import ly.com.tahaben.usage_overview_domain.use_case.GetUpdatedDays
import ly.com.tahaben.usage_overview_domain.use_case.GetUsageDataForDate
import ly.com.tahaben.usage_overview_domain.use_case.GetUsageEventsFromDb
import ly.com.tahaben.usage_overview_domain.use_case.IsAutoCachingEnabled
import ly.com.tahaben.usage_overview_domain.use_case.IsCachingEnabled
import ly.com.tahaben.usage_overview_domain.use_case.IsDateToday
import ly.com.tahaben.usage_overview_domain.use_case.IsDayDataFullyUpdated
import ly.com.tahaben.usage_overview_domain.use_case.IsDayOver
import ly.com.tahaben.usage_overview_domain.use_case.IsUsagePermissionGranted
import ly.com.tahaben.usage_overview_domain.use_case.MergeDaysUsageDuration
import ly.com.tahaben.usage_overview_domain.use_case.OpenAppSettings
import ly.com.tahaben.usage_overview_domain.use_case.SetAutoCachingEnabled
import ly.com.tahaben.usage_overview_domain.use_case.SetCachingEnabled
import ly.com.tahaben.usage_overview_domain.use_case.SetUsageReportsEnabled
import ly.com.tahaben.usage_overview_domain.use_case.UsageOverviewUseCases
import ly.com.tahaben.usage_overview_domain.use_case.UsageSettingsUseCases
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class UsageOverviewIgnoreFarhanTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // why: the "ignore Farhan's own usage" setting compared against the Kotlin namespace
    //   ("ly.com.tahaben.farhan"), never the installed application id, so it never hid anything.
    // catches: a hardcoded "ly.com.tahaben.farhan"; a comparison with the namespace or any other constant
    //   instead of the repository's own package name.
    @Test
    fun ignoreFarhanOn_hidesOnlyTheOwnPackage() {
        val apps = trackedPackagesYesterday(ignoreFarhan = true)

        assertEquals(setOf(OTHER_PACKAGE), apps)
    }

    // why: positive control for the test above. With the setting off both apps must show, so the test above
    //   cannot pass merely because the fixture hides or drops an app for another reason.
    // catches: dropping the `filterFarhan &&` guard (own package always hidden); a fixture whose own-package
    //   events never produce a row.
    @Test
    fun ignoreFarhanOff_showsBothPackages() {
        val apps = trackedPackagesYesterday(ignoreFarhan = false)

        assertEquals(setOf(OWN_PACKAGE, OTHER_PACKAGE), apps)
    }

    private fun trackedPackagesYesterday(ignoreFarhan: Boolean): Set<String> {
        val repository = FakeUsageRepository(
            ownPackage = OWN_PACKAGE,
            events = listOf(OWN_PACKAGE, OTHER_PACKAGE).flatMap(::tenMinuteSession),
        )
        val preferences = FakePreferences(ignoreFarhan = ignoreFarhan, ignoreLauncher = false)
        val viewModel = UsageOverviewViewModel(
            usageOverviewUseCases = usageOverviewUseCases(repository),
            usageSettingsUseCases = usageSettingsUseCases(preferences),
            preferences = preferences,
        )
        viewModel.iniFilters()

        // Yesterday: the today-only `dropLast(1)` would otherwise drop the last event of the fixture.
        viewModel.onEvent(UsageOverviewEvent.OnPreviousDayClick)
        dispatcher.scheduler.runCurrent()

        return viewModel.state.trackedApps.map { it.packageName }.toSet()
    }

    // Usage events carry a negative start and a positive stop timestamp; their sum is the session length.
    private fun tenMinuteSession(packageName: String) = listOf(
        UsageDataItem(packageName, packageName, -1_000_000L, UsageDataItem.EventType.Start, UsageDataItem.Category.OTHER),
        UsageDataItem(packageName, packageName, 1_600_000L, UsageDataItem.EventType.Stop, UsageDataItem.Category.OTHER),
    ).map { it.copy(appName = packageName) }

    private fun usageOverviewUseCases(repository: UsageRepository) = UsageOverviewUseCases(
        calculateUsageDuration = CalculateUsageDuration(),
        filterUsageEvents = FilterUsageEvents(),
        getDurationFromMilliseconds = GetDurationFromMilliseconds(),
        cacheUsageDataForDate = CacheUsageDataForDate(repository),
        getUsageDataForDate = GetUsageDataForDate(repository),
        isDateToDay = IsDateToday(),
        isUsagePermissionGranted = IsUsagePermissionGranted(repository),
        filterDuration = FilterDuration(),
        getUsageEventsFromDb = GetUsageEventsFromDb(repository),
        isDayDataFullyUpdated = IsDayDataFullyUpdated(repository),
        mergeDaysUsageDuration = MergeDaysUsageDuration(),
        getUpdatedDays = GetUpdatedDays(repository),
        deleteCacheForDay = DeleteCacheForDay(repository),
        isDayOver = IsDayOver(),
        getOwnPackageName = GetOwnPackageName(repository),
    )

    private fun usageSettingsUseCases(preferences: Preferences): UsageSettingsUseCases {
        val worker = UnusedWorkerRepository()
        return UsageSettingsUseCases(
            setAutoCachingEnabled = SetAutoCachingEnabled(worker),
            setUsageReportsEnabled = SetUsageReportsEnabled(worker),
            getEnabledUsageReports = GetEnabledUsageReports(preferences),
            isAutoCachingEnabled = IsAutoCachingEnabled(preferences),
            isCachingEnabled = IsCachingEnabled(preferences),
            setCachingEnabled = SetCachingEnabled(preferences),
            openAppSettings = OpenAppSettings(worker),
        )
    }

    private companion object {
        const val OWN_PACKAGE = "own.pkg"
        const val OTHER_PACKAGE = "other.pkg"
    }
}

private class FakeUsageRepository(
    private val ownPackage: String,
    private val events: List<UsageDataItem>,
) : UsageRepository {
    override suspend fun cacheUsageEvents(date: LocalDate) = Unit
    override suspend fun getUsageEvents(date: LocalDate) = events
    override suspend fun getUsageEventsFromDb(date: LocalDate) = events
    override suspend fun isDayDataFullyUpdated(date: LocalDate) = false
    override fun checkUsagePermission() = true
    override fun getOwnPackageName() = ownPackage
    override suspend fun getCachedDays() = emptyList<LocalDate>()
    override suspend fun deleteCacheForDay(date: LocalDate) = Unit
}

private class FakePreferences(
    private val ignoreFarhan: Boolean,
    private val ignoreLauncher: Boolean,
) : Preferences {
    override fun isIgnoreFarhan() = ignoreFarhan
    override fun isIgnoreLauncher() = ignoreLauncher
    override fun isCacheEnabled() = false
    override fun isAutoCacheEnabled() = false
    override fun getEnabledUsageReports() = emptyMap<String, Boolean>()
    override fun setAutoCacheEnabled(isEnabled: Boolean) = Unit
    override fun setCacheEnabled(isEnabled: Boolean) = Unit
    override fun setUsageReportsEnabled(usageReports: Map<String, Boolean>) = Unit
    override fun getTodayUsage() = 0L
    override fun setTodayUsage(usage: Long) = Unit
    override fun getYesterdayUsage() = 0L
    override fun setYesterdayUsage(usage: Long) = Unit
    override fun setIgnoreLauncher(isEnabled: Boolean) = Unit
    override fun setIgnoreFarhan(isEnabled: Boolean) = Unit
}

private class UnusedWorkerRepository : WorkerRepository {
    override fun scheduleWork() = error("unused")
    override fun switchAutoCacheEnabled(isEnabled: Boolean) = error("unused")
    override fun openAppSettings() = error("unused")
    override fun switchReportsEnabled(usageReports: Map<String, Boolean>) = error("unused")
    override fun scheduleWeeklyReport() = error("unused")
    override fun scheduleMonthlyReport() = error("unused")
    override fun scheduleYearlyReport() = error("unused")
}
