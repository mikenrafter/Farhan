package ly.com.tahaben.farhan.settings

import android.content.Context
import android.os.Process
import ly.com.tahaben.settings_portability.SettingsStartupCoordinator

internal object SettingsStartup {
    /** Worker join finishes before Hilt's generated Application superclass can inject any store consumers. */
    fun run(context: Context): Boolean {
        var allowed = false
        var processDeath: Error? = null
        val worker = Thread({
            try {
                allowed = SettingsStartupCoordinator(ProcessStores.gate, { ProcessStores.startup(context) },
                    RecoveryComponents(context), { Process.killProcess(Process.myPid()) }).run()
            } catch (death: Error) { processDeath = death }
        }, "settings-startup")
        worker.start()
        try { worker.join() } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Process.killProcess(Process.myPid())
            return false
        }
        processDeath?.let { throw it }
        return allowed
    }
}
