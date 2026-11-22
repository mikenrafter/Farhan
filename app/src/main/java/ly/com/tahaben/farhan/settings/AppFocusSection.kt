package ly.com.tahaben.farhan.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ly.com.tahaben.core_ui.components.SettingToggleRow
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.core_ui.components.SettingsRowDivider
import ly.com.tahaben.infinite_scroll_blocker_domain.use_cases.InfiniteScrollUseCases
import ly.com.tahaben.launcher_domain.preferences.Preference
import ly.com.tahaben.screen_grayscale_domain.use_cases.GrayscaleUseCases
import javax.inject.Inject

/**
 * Where one app sits in each focus tool's own app list, read and written through the same preferences the
 * tools' list screens use. The lists do not all mean the same thing, so each row says what its switch does:
 * the scroll blocker's list holds apps it leaves alone, while the other three hold the apps the tool acts on.
 */
internal data class AppFocusFlags(
    val scrollBlockerSkips: Boolean,
    val grayscale: Boolean,
    val timeLimit: Boolean,
    val mindfulLaunch: Boolean,
)

@HiltViewModel
internal class AppFocusViewModel @Inject constructor(
    private val infiniteScroll: InfiniteScrollUseCases,
    private val grayscale: GrayscaleUseCases,
    private val launcher: Preference,
) : ViewModel() {

    suspend fun load(packageName: String) = AppFocusFlags(
        scrollBlockerSkips = infiniteScroll.isPackageInInfiniteScrollExceptions(packageName),
        grayscale = grayscale.isPackageInGrayscaleWhiteList(packageName),
        timeLimit = launcher.isPackageInTimeLimitPackages(packageName),
        mindfulLaunch = launcher.isPackageInDelayedLaunchWhiteList(packageName),
    )

    fun setScrollBlockerSkips(packageName: String, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on) infiniteScroll.addPackageToInfiniteScrollExceptions(packageName)
        else infiniteScroll.removePackageFromInfiniteScrollExceptions(packageName)
    }

    fun setGrayscale(packageName: String, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on) grayscale.addPackageToGrayscaleWhiteList(packageName)
        else grayscale.removePackageFromGrayscaleWhiteList(packageName)
    }

    fun setTimeLimit(packageName: String, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on) launcher.addPackageToTimeLimitPackages(packageName)
        else launcher.removePackageFromTimeLimitPackages(packageName)
    }

    fun setMindfulLaunch(packageName: String, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on) launcher.addPackageToMLWhiteList(packageName)
        else launcher.removePackageFromMLWhiteList(packageName)
    }
}

/** The focus tools' per-app switches, for the bottom of an app's settings page. */
@Composable
internal fun ColumnScope.AppFocusSection(
    packageName: String,
    viewModel: AppFocusViewModel = hiltViewModel(),
) {
    var flags by remember(packageName) { mutableStateOf<AppFocusFlags?>(null) }
    LaunchedEffect(packageName) { flags = viewModel.load(packageName) }
    val current = flags ?: return
    SettingsBox("Focus tools") {
        SettingToggleRow(
            title = "Scroll blocker",
            summary = "Never block scrolling in this app",
            checked = current.scrollBlockerSkips,
            onCheckedChange = { flags = current.copy(scrollBlockerSkips = it); viewModel.setScrollBlockerSkips(packageName, it) },
        )
        SettingsRowDivider()
        SettingToggleRow(
            title = "Grayscale",
            summary = "Turn the screen gray in this app",
            checked = current.grayscale,
            onCheckedChange = { flags = current.copy(grayscale = it); viewModel.setGrayscale(packageName, it) },
        )
        SettingsRowDivider()
        SettingToggleRow(
            title = "Time limit",
            summary = "Apply the time limit to this app",
            checked = current.timeLimit,
            onCheckedChange = { flags = current.copy(timeLimit = it); viewModel.setTimeLimit(packageName, it) },
        )
        SettingsRowDivider()
        SettingToggleRow(
            title = "Mindful launch",
            summary = "Pause before this app opens",
            checked = current.mindfulLaunch,
            onCheckedChange = { flags = current.copy(mindfulLaunch = it); viewModel.setMindfulLaunch(packageName, it) },
        )
    }
}
