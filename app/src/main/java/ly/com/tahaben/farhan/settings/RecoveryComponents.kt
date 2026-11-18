package ly.com.tahaben.farhan.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import java.io.File

/** Persists exact PackageManager overrides before disabling components. RecoveryActivity is the marker. */
internal class RecoveryComponents(private val context: Context) {
    private val manager = context.packageManager
    private val recovery = ComponentName(context, RecoveryActivity::class.java)
    private val snapshot = PrivateSettingsFile(File(context.noBackupFilesDir, "component_states_v1"))
    fun isRecovering() = manager.getComponentEnabledSetting(recovery) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Suppress("DEPRECATION")
    fun block(): Boolean {
        if (!isRecovering()) manager.setComponentEnabledSetting(recovery, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val packageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS)
        val components = (packageInfo.activities.orEmpty().toList() + packageInfo.services.orEmpty().toList() +
            packageInfo.receivers.orEmpty().toList() + packageInfo.providers.orEmpty().toList())
            .filter { it.name != recovery.className }.distinctBy { it.name }
        val states = snapshot.read()?.toMutableMap() ?: linkedMapOf()
        validate(states)
        var extended = false
        for (component in components) if (component.name !in states) {
            states[component.name] = manager.getComponentEnabledSetting(ComponentName(context.packageName, component.name)).toString()
            extended = true
        }
        if (extended) snapshot.write(states)
        var changed = false
        for (component in components) {
            val name = ComponentName(context.packageName, component.name)
            val state = manager.getComponentEnabledSetting(name)
            // Components whose manifest is disabled are already stopped when their raw state is DEFAULT.
            val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && component.enabled
            if (enabled) {
                manager.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                changed = true
            }
        }
        return changed
    }
    fun heal() {
        if (!isRecovering()) return
        snapshot.read()?.let { states ->
            validate(states)
            states.forEach { (name, raw) ->
                val component = ComponentName(context.packageName, name)
                val expected = raw.toInt()
                if (manager.getComponentEnabledSetting(component) != expected)
                    manager.setComponentEnabledSetting(component, expected, PackageManager.DONT_KILL_APP)
            }
            snapshot.clear()
        }
        manager.setComponentEnabledSetting(recovery, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
    private fun validate(states: Map<String, String>) {
        require(states.keys.none { it == recovery.className || !it.contains('.') })
        states.values.forEach { require(it.toInt() in 0..4) }
    }
}
