package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import ly.com.tahaben.core.data.repository.InstalledAppsRepository
import ly.com.tahaben.core.model.AppItem
import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.testsupport.recordingProxy
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import org.junit.Test

/**
 * Category browsing through the production use case GetAppPriorityInfoList with its real collaborators
 * (classifier and both scanners are real). Only the registry, device services and preferences are fakes, and
 * they count calls so "did not rescan / did not copy the catalog" is observable.
 *
 * NOT covered (missing seam, see report): AppPriorityViewModel's refresh-vs-edit race. It needs a
 * dispatcher and a narrower dependency than the 60-member NotificationFilterUseCases.
 *
 * proves: CLAIM(catalog-first), CLAIM(single-row-edit)
 */
class GetAppPriorityInfoListTest {

    private class CountingRegistry(private val sources: Map<String, KnownPackageSource>) : KnownPackageSourceRegistry {
        var getAllCalls = 0
        val getCalls = mutableListOf<String>()
        override fun getAll(): Map<String, KnownPackageSource> {
            getAllCalls++
            return sources
        }

        override fun get(packageName: String): KnownPackageSource? {
            getCalls += packageName
            return sources[packageName]
        }

        override fun register(sources: List<VisibleNotificationSource>) = Unit
    }

    private class Rig(overrides: Map<String, NotificationPriority> = emptyMap()) {
        val registry = CountingRegistry(
            mapOf(
                "com.b" to KnownPackageSource("com.b", "Beta", channelIds = setOf("c1")),
                "com.a" to KnownPackageSource("com.a", "Alpha"),
            )
        )
        val serviceCalls = mutableListOf<String>()
        var installedAppsReads = 0
        private val serviceUtil = recordingProxy(
            ServiceUtil::class.java,
            serviceCalls,
            mapOf(
                "areAppNotificationsEnabled" to { _: Array<Any?> -> true },
                "getOwnPackageName" to { _: Array<Any?> -> "com.farhan" },
            ),
        )
        private val installedApps = object : InstalledAppsRepository {
            override suspend fun getInstalledApps(): List<AppItem> {
                installedAppsReads++
                return listOf(AppItem(name = "Gamma", packageName = "com.g"))
            }
        }
        private val preferences = recordingProxy(
            Preferences::class.java,
            answers = mapOf("getAppPriorityOverride" to { args: Array<Any?> -> overrides[args[0] as String] }),
        )
        private val register = RegisterKnownNotificationSources(registry)
        val useCase = GetAppPriorityInfoList(
            preferences = preferences,
            knownPackageSourceRegistry = registry,
            classifyNotification = ClassifyNotification(preferences),
            scanInstalledNotificationSources = ScanInstalledNotificationSources(
                GetInstalledAppsList(installedApps), serviceUtil, register,
            ),
            scanVisibleNotificationSources = ScanVisibleNotificationSources(serviceUtil, register),
            serviceUtil = serviceUtil,
        )

        fun scanCalls() = installedAppsReads +
            serviceCalls.count { it == "getNotificationChannelsForPackage" || it == "getVisibleNotificationSources" }
    }

    // why: owner decision: category browsing renders the cached catalog first; discovery scans (installed apps,
    //      per-app channel binder calls, visible notifications) must not sit in front of the first rows.
    // catches: cached view that awaits or triggers a scan. The control proves the probe: a normal load does scan.
    // Order: enabled apps first, then name: Alpha, Beta.
    @Test
    fun `the cached catalog lists known apps without any discovery scan`() {
        val rig = Rig()

        val rows = rig.useCase.cached()

        assertThat(rows.map { it.appName }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(rig.scanCalls()).isEqualTo(0)

        runBlocking { rig.useCase(forceRescan = true) }
        assertThat(rig.scanCalls()).isGreaterThan(0)
    }

    // why: a single-row edit must read that one source; today forPackage copies the whole registry (getAll) each time.
    // catches: forPackage via getAll()[pkg]; a per-edit rescan. The edit's result still reflects the new override.
    @Test
    fun `a single-row edit reads one source without copying the catalog or rescanning`() {
        val rig = Rig(overrides = mapOf("com.b" to NotificationPriority.LOW))

        val row = rig.useCase.forPackage("com.b")

        assertThat(row?.currentPriority).isEqualTo(NotificationPriority.LOW)
        assertThat(rig.registry.getCalls).containsExactly("com.b")
        assertThat(rig.registry.getAllCalls).isEqualTo(0)
        assertThat(rig.scanCalls()).isEqualTo(0)
    }
}
