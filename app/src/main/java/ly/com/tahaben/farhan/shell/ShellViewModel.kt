package ly.com.tahaben.farhan.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
internal class ShellViewModel @Inject constructor(
    private val store: FeatureSwitchStore,
) : ViewModel() {
    val switches: StateFlow<FeatureSwitches> = store.switches

    fun setUsage(enabled: Boolean) = store.setUsage(enabled)
    fun setLauncher(enabled: Boolean) = store.setLauncher(enabled)
    fun setFocus(enabled: Boolean) = store.setFocus(enabled)
}
