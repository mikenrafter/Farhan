package ly.com.tahaben.farhan.settings

import android.content.Context
import android.os.Process
import ly.com.tahaben.settings_portability.*

internal object SettingsStartup {
    /** Worker join finishes before Hilt's generated Application superclass can inject any store consumers. */
    fun run(context: Context): Boolean {
        var allowed = false
        val worker = Thread({
            val components = RecoveryComponents(context)
            try {
                val result = ProcessStores.startup(context)
                if (result.allowsNormalOperation) {
                    components.heal()
                    ProcessStores.allowNormalOperation()
                    allowed = true
                } else if (components.block()) {
                    Process.killProcess(Process.myPid())
                }
            } catch (failure: Exception) {
                // A failed state snapshot or toggle cannot justify initializing normal consumers.
                try { if (components.block()) Process.killProcess(Process.myPid()) } catch (_: Exception) { }
            }
        }, "settings-startup")
        worker.start()
        try { worker.join() } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Process.killProcess(Process.myPid())
            return false
        }
        return allowed
    }
}
