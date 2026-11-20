package ly.com.tahaben.notification_filter_presentation.apppriority

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppIconLoaderTest {
    // why: coming back to a visible row should not decode the same sized icon again.
    // catches: bypassing the cache, ignoring size, or accumulating icons beyond its bound.
    @Test fun cachesEachSizeAndEvictsLeastRecentlyUsed() = runTest {
        val source = FakeIconSource { packageName, pixels -> "$packageName/$pixels" }
        val loader = AppIconLoader(source, StandardTestDispatcher(testScheduler), capacity = 2)
        assertEquals(AppIcon.Loaded("maps/40"), loader.load("maps", 40))
        assertEquals(AppIcon.Loaded("maps/80"), loader.load("maps", 80))
        assertEquals(AppIcon.Loaded("maps/40"), loader.load("maps", 40))
        assertEquals(AppIcon.Loaded("mail/40"), loader.load("mail", 40))
        assertEquals(AppIcon.Loaded("maps/80"), loader.load("maps", 80))
        assertEquals(listOf("maps" to 40, "maps" to 80, "mail" to 40, "maps" to 80), source.requests)
    }

    // why: older catalog rows can outlive the package or refer to an inaccessible work profile.
    // catches: retrying a known missing icon every time the row scrolls back into view.
    @Test fun missingIconUsesCachedLetterTile() = runTest {
        val source = FakeIconSource { _, _ -> null }
        val loader = AppIconLoader(source, StandardTestDispatcher(testScheduler))
        assertEquals(AppIcon.LetterTile, loader.load("missing", 40))
        assertEquals(AppIcon.LetterTile, loader.load("missing", 40))
        assertEquals(listOf("missing" to 40), source.requests)
    }

    // why: a row can disappear while the platform is still decoding its icon.
    // catches: returning or caching a late decode after the caller cancels.
    @Test fun cancelledRequestCannotPublishOrCacheLateDecode() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeIconSource { _, _ ->
            withContext(NonCancellable) { gate.await() }
            "icon"
        }
        val loader = AppIconLoader(source, StandardTestDispatcher(testScheduler))
        val delivered = mutableListOf<AppIcon<String>>()
        val request = launch { delivered += loader.load("maps", 40) }
        runCurrent()
        assertEquals(listOf("maps" to 40), source.requests)
        request.cancel()
        gate.complete(Unit)
        request.join()
        assertEquals(emptyList<AppIcon<String>>(), delivered)
        assertEquals(AppIcon.Loaded("icon"), loader.load("maps", 40))
        assertEquals(listOf("maps" to 40, "maps" to 40), source.requests)
    }

    private class FakeIconSource(
        private val resolve: suspend (String, Int) -> String?,
    ) : AppIconSource<String> {
        val requests = mutableListOf<Pair<String, Int>>()

        override suspend fun read(packageName: String, pixels: Int): String? {
            requests += packageName to pixels
            return resolve(packageName, pixels)
        }
    }
}
