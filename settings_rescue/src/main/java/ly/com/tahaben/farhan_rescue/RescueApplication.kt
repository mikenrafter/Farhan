package ly.com.tahaben.farhan_rescue

import android.app.Application

/**
 * CLAIM(startup-sweep): on every start of the UI process, all raw copies from earlier runs are deleted, and an ADB
 * identity older than its limit is erased. The `:adb_bridge` process skips this: it starts while a job may be
 * about to begin, and a sweep there could remove that job's directory.
 */
class RescueApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == packageName) {
            val paths = RescuePaths(this)
            paths.rawJobs.deleteAll()
            paths.identity.load(System.currentTimeMillis())
        }
    }
}
