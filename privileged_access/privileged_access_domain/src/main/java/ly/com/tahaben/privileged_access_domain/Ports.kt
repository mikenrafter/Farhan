package ly.com.tahaben.privileged_access_domain

/** Reads of the phone's own state. None of them runs a shell command, and none changes anything the user would notice. */
interface PhoneFacts {
    val sdk: Int

    /** `AppOpsManager` mode of RECEIVE_SENSITIVE_NOTIFICATIONS for Farhan's uid. */
    fun sensitiveNotificationsOp(): AppOpMode

    /** `PackageManager.checkPermission(WRITE_SECURE_SETTINGS)`. */
    fun secureSettingsPermissionHeld(): Boolean

    /**
     * Writes back the value a secure setting already has, to prove a write is accepted. Called only right after the
     * user asked for the grant, never from a status check. Null when it could not be tried.
     */
    fun secureSettingsWriteWorks(): Boolean?
}

/** What survives a restart. The route choice is a setting; [linkDropPending] is internal bookkeeping. */
interface PrivilegedPreferences {
    var route: RoutePreference

    /**
     * Set before a revoke that would leave nothing allowed, and cleared once the retained key is dropped or the
     * revoke is seen not to have taken. Android may restart Farhan when WRITE_SECURE_SETTINGS is revoked, so the
     * key cleanup finishes at the next status check.
     */
    var linkDropPending: Boolean
}

/** Asks Android to disconnect and reconnect Farhan's notification listener so a new app-op takes effect. */
fun interface ListenerRebinder {
    fun rebind()
}

/** The red "Android is hiding codes" diagnostic; cleared once the grant works. */
fun interface OtpRedactionFlag {
    fun clear()
}

/**
 * Turns Grayscale off while Farhan can still write secure settings. Without it, revoking the permission could leave
 * the screen gray with nothing able to undo it.
 */
fun interface GrayscaleReset {
    fun resetBeforeRevoke()
}
