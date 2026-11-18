package ly.com.tahaben.notification_filter_domain.use_cases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo

/** Rows and their package index are prepared together on the assembly dispatcher. */
class AppPriorityCatalog internal constructor(
    val apps: List<AppPriorityInfo>,
    val indices: Map<String, Int>,
)

/**
 * Publishes cached rows before discovery, then the refreshed catalog. Calls and [packageEdited] must
 * share the caller/UI context. Overlapping refresh calls return without starting another load.
 * Loader failures propagate; cancellation and failures release the in-flight edit set.
 *
 * CLAIM(catalog-refresh-edits): only edited packages are rebuilt on the caller context, immediately
 * before publication, so background snapshots cannot undo edits or discard discovered rows
 * [RefreshAppPriorityCatalogTest]. The set holds distinct packages for this refresh only; no retries.
 *
 * CLAIM(catalog-refresh-context): both bulk loads run on [assemblyDispatcher], and cached publication
 * precedes discovery. Per-package reconciliation and publication run on the calling context
 * [RefreshAppPriorityCatalogTest]. Call refresh and packageEdited from the same serial UI context.
 * CLAIM(catalog-refresh-cancel): cancellation prevents later publication and releases the refresh guard
 * [RefreshAppPriorityCatalogTest]. Loader/discovery exceptions propagate after releasing the guard.
 */
class RefreshAppPriorityCatalog(
    private val loadCached: suspend () -> List<AppPriorityInfo>,
    private val discover: suspend () -> Unit,
    private val loadPackage: (String) -> AppPriorityInfo?,
    private val assemblyDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val discoveryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var editedPackages: MutableSet<String>? = null

    fun packageEdited(packageName: String) {
        editedPackages?.add(packageName)
    }

    suspend fun refresh(publish: (AppPriorityCatalog) -> Unit) {
        if (editedPackages != null) return
        val edits = mutableSetOf<String>()
        editedPackages = edits
        try {
            assembleAndPublish(edits, publish)
            withContext(discoveryDispatcher) { discover() }
            assembleAndPublish(edits, publish)
        } finally {
            editedPackages = null
        }
    }

    private suspend fun assembleAndPublish(
        edits: MutableSet<String>,
        publish: (AppPriorityCatalog) -> Unit,
    ) {
        val (rows, indices) = withContext(assemblyDispatcher) {
            val rows = loadCached().toMutableList()
            rows to rows.withIndex().associate { (index, app) -> app.packageName to index }
        }
        // ↳ CLAIM(catalog-refresh-edits). No suspension between the latest per-package read and publish.
        edits.forEach { packageName ->
            val index = indices[packageName] ?: return@forEach
            loadPackage(packageName)?.let { rows[index] = it }
        }
        currentCoroutineContext().ensureActive()
        edits.clear()
        publish(AppPriorityCatalog(rows, indices))
    }
}
