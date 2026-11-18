package ly.com.tahaben.farhan.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ly.com.tahaben.settings_portability.ComponentStates
import ly.com.tahaben.settings_portability.ComponentStateSnapshot
import ly.com.tahaben.settings_portability.RecoveryComponent
import ly.com.tahaben.settings_portability.RecoveryComponentProtocol
import ly.com.tahaben.settings_portability.RecoveryControl
import java.io.File

/** Android ports only; block/heal ordering lives in the JVM-tested production protocol. */
internal class RecoveryComponents(context: Context) : RecoveryControl {
    private val manager = context.packageManager
    private val marker = ComponentName(context, RecoveryActivity::class.java).className
    private val file = PrivateSettingsFile(File(context.noBackupFilesDir, "component_states_v1"))
    private val protocol = RecoveryComponentProtocol(marker, object : ComponentStates {
        @Suppress("DEPRECATION")
        override fun list(): List<RecoveryComponent> {
            val info = manager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS)
            return (info.activities.orEmpty().toList() + info.services.orEmpty().toList() +
                info.receivers.orEmpty().toList() + info.providers.orEmpty().toList())
                .map { RecoveryComponent(it.name, it.enabled) }
        }
        override fun get(name: String) = manager.getComponentEnabledSetting(ComponentName(context.packageName, name))
        override fun set(name: String, state: Int) = manager.setComponentEnabledSetting(
            ComponentName(context.packageName, name), state, PackageManager.DONT_KILL_APP)
    }, object : ComponentStateSnapshot {
        override fun read() = file.read()?.mapValues { it.value.toInt() }
        override fun write(states: Map<String, Int>) = file.write(states.mapValues { it.value.toString() })
        override fun clear() = file.clear()
    })
    override fun block() = protocol.block()
    override fun heal() = protocol.heal()
}
