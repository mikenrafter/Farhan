package ly.com.tahaben.privileged_access_data

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import ly.com.tahaben.privileged_access_domain.AppOpMode
import ly.com.tahaben.privileged_access_domain.PhoneFacts
import ly.com.tahaben.privileged_access_domain.PrivilegedCommand

/** Reads Farhan's own grants. Nothing here needs a shell, and a status check never writes. */
class AndroidPhoneFacts(private val context: Context) : PhoneFacts {
    override val sdk: Int get() = Build.VERSION.SDK_INT

    /**
     * `AppOpsManager` raw mode of RECEIVE_SENSITIVE_NOTIFICATIONS for this uid. The op string is not in the public
     * SDK, so an unknown name (an older Android) throws and reads as [AppOpMode.UNKNOWN].
     */
    override fun sensitiveNotificationsOp(): AppOpMode {
        if (Build.VERSION.SDK_INT < 35) return AppOpMode.UNKNOWN
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            AppOpMode.fromPlatformMode(ops.unsafeCheckOpRawNoThrow(OP_STRING, Process.myUid(), context.packageName))
        } catch (failure: RuntimeException) {
            AppOpMode.UNKNOWN
        }
    }

    override fun secureSettingsPermissionHeld(): Boolean =
        context.packageManager.checkPermission(PrivilegedCommand.PERMISSION_WRITE_SECURE_SETTINGS, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    /** Writes the daltonizer switch back to the value it already has: a write that changes nothing the user can see. */
    override fun secureSettingsWriteWorks(): Boolean? = try {
        val resolver = context.contentResolver
        val current = Settings.Secure.getInt(resolver, DALTONIZER_ENABLED, 0)
        Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, current)
    } catch (denied: SecurityException) {
        false
    } catch (failure: RuntimeException) {
        null
    }

    private companion object {
        const val OP_STRING = "android:receive_sensitive_notifications"
        const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    }
}
