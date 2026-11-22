package ly.com.tahaben.privileged_access_data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ly.com.tahaben.privileged_access_domain.Route
import ly.com.tahaben.privileged_access_domain.RouteBlocker
import ly.com.tahaben.privileged_access_domain.RouteReadiness
import ly.com.tahaben.privileged_access_domain.ShellCommand
import ly.com.tahaben.privileged_access_domain.ShellResult
import ly.com.tahaben.privileged_access_domain.ShizukuPermissionResult
import ly.com.tahaben.privileged_access_domain.ShizukuRoute
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku route, on Shizuku API 13.1.5. `Shizuku.newProcess` is private in that API, so a command runs in a user
 * service: Shizuku starts [ExecUserService] with shell rights, Farhan binds it, calls it once and unbinds it
 * (`daemon(false)`: the process ends with the binding).
 *
 * Nothing is sent anywhere: Shizuku is a local binder, and no network is involved.
 *
 * Not checked on a device: Shizuku was not installed on any phone or emulator available to this work.
 */
class AndroidShizukuRoute(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ShizukuRoute {
    override val route = Route.SHIZUKU

    override suspend fun readiness(): RouteReadiness = blocker()?.let { RouteReadiness.Blocked(it) } ?: RouteReadiness.Ready

    /** Cheap, no prompt: is the Shizuku binder alive, new enough, and has Farhan been allowed? */
    private fun blocker(): RouteBlocker? {
        // The Shizuku API library needs Android 7; on Android 6 none of its classes may load.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return RouteBlocker.SHIZUKU_TOO_OLD
        if (!Shizuku.pingBinder()) return if (isManagerInstalled()) RouteBlocker.SHIZUKU_NOT_RUNNING else RouteBlocker.SHIZUKU_NOT_INSTALLED
        return try {
            when {
                Shizuku.isPreV11() -> RouteBlocker.SHIZUKU_TOO_OLD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> null
                // "Deny and don't ask again": Shizuku will not show its dialog again.
                Shizuku.shouldShowRequestPermissionRationale() -> RouteBlocker.SHIZUKU_PERMISSION_DENIED
                else -> RouteBlocker.SHIZUKU_PERMISSION_NEEDED
            }
        } catch (gone: Exception) {
            // The binder died between the ping and the call.
            RouteBlocker.SHIZUKU_NOT_RUNNING
        }
    }

    private fun isManagerInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (missing: PackageManager.NameNotFoundException) {
        false
    }

    override suspend fun requestPermission(): ShizukuPermissionResult {
        return when (val current = blocker()) {
            null -> ShizukuPermissionResult.Granted
            RouteBlocker.SHIZUKU_PERMISSION_NEEDED ->
                if (prompt()) ShizukuPermissionResult.Granted else ShizukuPermissionResult.Blocked(RouteBlocker.SHIZUKU_PERMISSION_DENIED)
            else -> ShizukuPermissionResult.Blocked(current)
        }
    }

    private suspend fun prompt(): Boolean = suspendCancellableCoroutine { continuation ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (failure: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    override suspend fun run(command: ShellCommand): ShellResult = withContext(io) {
        blocker()?.let { return@withContext ShellResult.Blocked(it) }
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ExecUserService::class.java.name))
            .processNameSuffix("privileged_exec")
            .daemon(false)
            .version(SERVICE_VERSION)
        val connected = CompletableDeferred<IExecService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(if (binder != null && binder.pingBinder()) IExecService.Stub.asInterface(binder) else null)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }
        }
        try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { connected.await() }
                ?: return@withContext ShellResult.Blocked(RouteBlocker.SHIZUKU_SERVICE_FAILED)
            val reply = service.run(command.args.toTypedArray())
            ShellResult.Ran(reply.getInt(ExecUserService.KEY_EXIT, -1), reply.getString(ExecUserService.KEY_OUTPUT).orEmpty())
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // RemoteException, SecurityException or IllegalStateException: Shizuku went away or refused. The text is not kept.
            ShellResult.Blocked(RouteBlocker.SHIZUKU_SERVICE_FAILED)
        } finally {
            try { Shizuku.unbindUserService(args, connection, true) } catch (ignored: Exception) { }
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4101
        const val SERVICE_VERSION = 1
        const val BIND_TIMEOUT_MILLIS = 10_000L
    }
}
