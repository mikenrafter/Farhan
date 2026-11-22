package ly.com.tahaben.farhan.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ly.com.tahaben.core.util.HomeWatcher
import ly.com.tahaben.launcher_presentation.launcher.LauncherScreen

/**
 * The existing launcher screen, unchanged. LauncherActivity starts the home watcher on start and stops it on
 * pause; this does the same while the tab is on screen.
 */
@Composable
internal fun LauncherTab() {
    val context = LocalContext.current
    val homeWatcher = remember { HomeWatcher(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, homeWatcher) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> homeWatcher.startWatch()
                Lifecycle.Event.ON_PAUSE -> homeWatcher.stopWatch()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            homeWatcher.stopWatch()
        }
    }
    LauncherScreen(homeWatcher)
}
