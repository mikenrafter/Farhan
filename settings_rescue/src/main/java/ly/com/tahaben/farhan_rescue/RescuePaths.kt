package ly.com.tahaben.farhan_rescue

import android.content.Context
import ly.com.tahaben.farhan_rescue.storage.IdentityStore
import ly.com.tahaben.farhan_rescue.storage.PendingExportStore
import ly.com.tahaben.farhan_rescue.storage.RawJobStore
import java.io.File

/**
 * Where the helper keeps things. Both processes (UI and `:adb_bridge`) share one UID and so one data directory.
 *  - raw copies of the old app's files: `cache/raw/<job>` (deleted on success, error, cancel and next start)
 *  - throwaway ADB identity: `no_backup/adb_identity` (erased on completed capture, reset, cancel; 30 minute limit)
 *  - converted portable file waiting to be saved: `no_backup/pending/portable-settings.json`
 */
internal class RescuePaths(context: Context) {
    private val cache = context.cacheDir
    private val noBackup = context.noBackupFilesDir
    val rawJobs = RawJobStore(File(cache, "raw"))
    val identity = IdentityStore(File(noBackup, "adb_identity"))
    val pending = PendingExportStore(File(noBackup, "pending/portable-settings.json"))
}
