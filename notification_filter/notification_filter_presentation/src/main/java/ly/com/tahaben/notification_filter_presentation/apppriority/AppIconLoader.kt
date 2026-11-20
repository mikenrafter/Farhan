package ly.com.tahaben.notification_filter_presentation.apppriority

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface AppIconSource<Icon : Any> {
    /** Reads a display-sized icon, or returns null if the package has no accessible icon. */
    suspend fun read(packageName: String, pixels: Int): Icon?
}

internal sealed interface AppIcon<out Icon> {
    data class Loaded<Icon>(val icon: Icon) : AppIcon<Icon>
    data object LetterTile : AppIcon<Nothing>
}

/**
 * Loads and caches display-sized icons on [dispatcher]. Cancellation and unexpected source
 * errors propagate. A cancelled decode is checked before it can warm the cache.
 */
internal class AppIconLoader<Icon : Any>(
    private val source: AppIconSource<Icon>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val capacity: Int = 128,
) {
    private data class Request(val packageName: String, val pixels: Int)

    private val cache = LinkedHashMap<Request, AppIcon<Icon>>(16, 0.75f, true)

    suspend fun load(packageName: String, pixels: Int): AppIcon<Icon> = withContext(dispatcher) {
        val request = Request(packageName, pixels)
        synchronized(cache) { cache[request] }?.let { return@withContext it }

        val icon = source.read(packageName, pixels)?.let { AppIcon.Loaded(it) } ?: AppIcon.LetterTile
        ensureActive()
        synchronized(cache) {
            cache[request] = icon
            if (cache.size > capacity) cache.remove(cache.keys.first())
        }
        icon
    }
}
