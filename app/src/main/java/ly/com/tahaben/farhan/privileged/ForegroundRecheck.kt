package ly.com.tahaben.farhan.privileged

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ly.com.tahaben.privileged_access_domain.PrivilegedAccess

/**
 * Re-reads the privileged-access link and grants when the app comes back to the foreground (the first activity to
 * start after none was). A status check reads the phone and runs no command and opens no connection.
 */
class ForegroundRecheck(private val access: dagger.Lazy<PrivilegedAccess>) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) scope.launch { access.get().refresh() }
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
