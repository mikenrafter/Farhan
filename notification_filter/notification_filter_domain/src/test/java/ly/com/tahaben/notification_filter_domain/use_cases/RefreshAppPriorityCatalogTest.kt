package ly.com.tahaben.notification_filter_domain.use_cases

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ly.com.tahaben.core.data.repository.InstalledAppsRepository
import ly.com.tahaben.core.model.AppItem
import ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference
import ly.com.tahaben.notification_filter_domain.delivery.QueueOrder
import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.KnownPackageSource
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.VisibleNotificationSource
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.notification_filter_domain.testsupport.recordingProxy
import ly.com.tahaben.notification_filter_domain.util.KnownPackageSourceRegistry
import ly.com.tahaben.notification_filter_domain.util.ServiceUtil
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshAppPriorityCatalogTest {
    // Distinct dispatcher identities on one scheduler, including inside synchronous loader/publisher calls.
    private class MarkedDispatcher(
        scheduler: TestCoroutineScheduler,
        private val name: String,
        private val active: ThreadLocal<String>,
    ) : CoroutineDispatcher() {
        private val delegate = StandardTestDispatcher(scheduler, name)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                val previous = active.get()
                active.set(name)
                try { block.run() } finally { active.set(previous) }
            }
        }
    }

    private class Rig {
        val active = ThreadLocal<String>()
        val bulkContexts = mutableListOf<String>()
        val packageReads = mutableListOf<String>()
        val packageContexts = mutableListOf<String>()
        val sources = mutableMapOf("com.a" to KnownPackageSource("com.a", "Alpha", channelIds = setOf("old")))
        val overrides = mutableMapOf<String, NotificationPriority>()
        val locks = mutableSetOf<String>()
        val offsets = mutableMapOf<String, Int>()
        val queues = mutableMapOf<String, AppQueuePreference>()
        val preferences = recordingProxy(Preferences::class.java, answers = mapOf(
            "getAppPriorityOverride" to { args: Array<Any?> -> overrides[args[0] as String] },
            "isAppPriorityLocked" to { args: Array<Any?> -> args[0] as String in locks },
            "getChannelPriorityOffset" to { args: Array<Any?> -> offsets[args[1] as String] ?: 0 },
            "getAppQueuePreferences" to { _: Array<Any?> -> queues.toMap() },
        ))
        private val registry = object : KnownPackageSourceRegistry {
            override fun getAll(): Map<String, KnownPackageSource> {
                bulkContexts += active.get()
                return sources.toMap()
            }
            override fun get(packageName: String): KnownPackageSource? {
                packageReads += packageName
                packageContexts += active.get()
                return sources[packageName]
            }
            override fun register(sources: List<VisibleNotificationSource>) = Unit
        }
        private val service = recordingProxy(ServiceUtil::class.java)
        private val register = RegisterKnownNotificationSources(registry)
        private val installed = object : InstalledAppsRepository {
            override suspend fun getInstalledApps(): List<AppItem> = error("Edits must not discover apps")
        }
        val loader = GetAppPriorityInfoList(
            preferences, registry, ClassifyNotification(preferences),
            ScanInstalledNotificationSources(GetInstalledAppsList(installed), service, register),
            ScanVisibleNotificationSources(service, register), service,
        )

        fun dispatcher(scheduler: TestCoroutineScheduler, name: String): CoroutineDispatcher =
            MarkedDispatcher(scheduler, name, active)
    }

    // WHY: CLAIM(catalog-refresh-context) and CLAIM(catalog-first) require cached rows before scanning.
    // catches: bulk load on caller/UI, publish inside withContext, discovery first, or overlapping refreshes.
    @Test
    fun `cached rows precede discovery with background assembly and caller publication`() = runTest {
        val rig = Rig()
        val ui = rig.dispatcher(testScheduler, "ui")
        val scanGate = CompletableDeferred<Unit>()
        val publications = mutableListOf<List<AppPriorityInfo>>()
        var scans = 0
        val refresh = RefreshAppPriorityCatalog(
            loadCached = { rig.loader.cached() },
            discover = {
                assertThat(rig.active.get()).isEqualTo("discovery")
                assertThat(publications.single().map { it.packageName }).containsExactly("com.a")
                scans++
                scanGate.await()
                rig.sources["com.b"] = KnownPackageSource("com.b", "Beta")
            },
            loadPackage = rig.loader::forPackage,
            assemblyDispatcher = rig.dispatcher(testScheduler, "assembly"),
            discoveryDispatcher = rig.dispatcher(testScheduler, "discovery"),
        )
        val publish: (AppPriorityCatalog) -> Unit = {
            assertThat(rig.active.get()).isEqualTo("ui")
            val expectedIndices = if (publications.isEmpty()) mapOf("com.a" to 0) else mapOf("com.a" to 0, "com.b" to 1)
            assertThat(it.indices).isEqualTo(expectedIndices)
            publications += it.apps
        }
        val job = launch(ui) { refresh.refresh(publish) }
        runCurrent()
        launch(ui) { refresh.refresh(publish) }
        runCurrent()
        assertThat(scans).isEqualTo(1)
        assertThat(publications).hasSize(1)
        assertThat(rig.bulkContexts).containsExactly("assembly")
        scanGate.complete(Unit)
        runCurrent()
        job.join()
        assertThat(publications).hasSize(2)
        assertThat(publications.last().map { it.packageName }).containsExactly("com.a", "com.b").inOrder()
        assertThat(rig.bulkContexts).containsExactly("assembly", "assembly").inOrder()
        assertThat(rig.packageReads).isEmpty()
    }

    // WHY: CLAIM(catalog-refresh-edits) must keep every edited field and fresh source metadata.
    // catches: revision-based whole-refresh discard, only guarding initial load, clearing the journal before
    // publication, restoring old row metadata, or replaying a stale edit instead of reading current preferences.
    @Test
    fun `edits during both assemblies win while discovered rows and metadata survive`() = runTest {
        val rig = Rig()
        val release = List(2) { CompletableDeferred<Unit>() }
        val scanGate = CompletableDeferred<Unit>()
        val publications = mutableListOf<List<AppPriorityInfo>>()
        var loads = 0
        var scans = 0
        val refresh = RefreshAppPriorityCatalog(
            loadCached = {
                val snapshot = rig.loader.cached()
                release[loads++].await()
                snapshot
            },
            discover = {
                scans++
                rig.sources["com.a"] = KnownPackageSource(
                    "com.a", "Alpha renamed", channelIds = setOf("old", "new"), appCategoryLabel = "Fresh label",
                )
                rig.sources["com.b"] = KnownPackageSource("com.b", "Beta discovered")
                scanGate.await()
            },
            loadPackage = rig.loader::forPackage,
            assemblyDispatcher = rig.dispatcher(testScheduler, "assembly"),
            discoveryDispatcher = rig.dispatcher(testScheduler, "discovery"),
        )
        val job = launch(rig.dispatcher(testScheduler, "ui")) { refresh.refresh { publications += it.apps } }
        runCurrent()
        assertThat(loads).isEqualTo(1)
        assertThat(scans).isEqualTo(0)
        rig.overrides["com.a"] = NotificationPriority.HIGH
        refresh.packageEdited("com.a")
        release[0].complete(Unit)
        runCurrent()
        assertThat(publications.single().single().currentPriority).isEqualTo(NotificationPriority.HIGH)

        // Edit during discovery, including an app absent from the cached publication.
        rig.overrides["com.a"] = NotificationPriority.LOW
        rig.overrides["com.b"] = NotificationPriority.HIGH
        refresh.packageEdited("com.a")
        refresh.packageEdited("com.b")
        scanGate.complete(Unit)
        runCurrent()
        assertThat(loads).isEqualTo(2)
        assertThat(publications).hasSize(1)
        rig.overrides["com.a"] = NotificationPriority.CRITICAL
        rig.locks += "com.a"
        rig.offsets["new"] = -1
        rig.queues["com.a"] = AppQueuePreference(QueueOrder.OLDEST_FIRST, 7)
        repeat(3) { refresh.packageEdited("com.a") }
        release[1].complete(Unit)
        runCurrent()
        job.join()

        assertThat(publications).hasSize(2)
        val rows = publications.last()
        assertThat(rows.map { it.packageName }).containsExactly("com.a", "com.b").inOrder()
        val alpha = rows.first()
        assertThat(alpha.currentPriority).isEqualTo(NotificationPriority.CRITICAL)
        assertThat(alpha.userOverride).isEqualTo(NotificationPriority.CRITICAL)
        assertThat(alpha.isLocked).isTrue()
        assertThat(alpha.priorityReason).isEqualTo("Set by user")
        assertThat(alpha.channels.map { it.channelId to it.offset }).containsExactly("new" to -1, "old" to 0).inOrder()
        assertThat(alpha.queuePreference).isEqualTo(AppQueuePreference(QueueOrder.OLDEST_FIRST, 7))
        assertThat(alpha.appName).isEqualTo("Alpha renamed")
        assertThat(alpha.appCategoryLabel).isEqualTo("Fresh label")
        assertThat(rows.last().currentPriority).isEqualTo(NotificationPriority.HIGH)
        assertThat(rows.last().appName).isEqualTo("Beta discovered")
        assertThat(scans).isEqualTo(1)
        assertThat(rig.bulkContexts).containsExactly("assembly", "assembly").inOrder()
        assertThat(rig.packageReads).containsExactly("com.a", "com.a", "com.b").inOrder()
        assertThat(rig.packageContexts).containsExactly("ui", "ui", "ui").inOrder()
    }

    // WHY: CLAIM(catalog-refresh-cancel) prevents a disposed screen's in-flight bulk load publishing late.
    // catches: launching detached work, swallowing cancellation, or leaving the refresh guard set on cancel.
    @Test
    fun `cancellation during either assembly prevents late publication and permits a later refresh`() = runTest {
        for (blockedLoad in 1..2) {
            val rig = Rig()
            val gate = CompletableDeferred<Unit>()
            val publications = mutableListOf<List<AppPriorityInfo>>()
            var loads = 0
            val refresh = RefreshAppPriorityCatalog(
                loadCached = {
                    val snapshot = rig.loader.cached()
                    // Model synchronous assembly finishing even after cancellation was requested.
                    if (++loads == blockedLoad) withContext(NonCancellable) { gate.await() }
                    snapshot
                },
                discover = {},
                loadPackage = rig.loader::forPackage,
                assemblyDispatcher = rig.dispatcher(testScheduler, "assembly"),
                discoveryDispatcher = rig.dispatcher(testScheduler, "discovery"),
            )
            val ui = rig.dispatcher(testScheduler, "ui")
            val job = launch(ui) { refresh.refresh { publications += it.apps } }
            runCurrent()
            assertThat(loads).isEqualTo(blockedLoad)
            assertThat(publications).hasSize(blockedLoad - 1)
            job.cancel()
            gate.complete(Unit)
            runCurrent()
            job.join()
            assertThat(job.isCancelled).isTrue()
            assertThat(publications).hasSize(blockedLoad - 1)
            val next = launch(ui) { refresh.refresh { publications += it.apps } }
            runCurrent()
            next.join()
            assertThat(publications).hasSize(blockedLoad + 1)
        }
    }
}
