package ly.com.tahaben.notification_filter_data.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

/**
 * Handles the Copy action of a code notification. Not exported: only the notification's own immutable
 * PendingIntent reaches it. It copies and does nothing else: it does not cancel the notification, collapse the
 * shade or send ACTION_CLOSE_SYSTEM_DIALOGS (restricted for apps on Android 12+).
 */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.isNotBlank() } ?: return
        copySensitive(context, code)
        // Android 13+ shows its own "Copied" confirmation. Earlier versions get a toast that never names the code.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_COPY = "ly.com.tahaben.notification_filter.action.COPY_CODE"
        const val EXTRA_CODE = "code"

        /** Marks the clip sensitive so the clipboard preview and keyboard suggestions do not show it. */
        internal fun copySensitive(context: Context, code: String) {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Farhan verification code", code)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            manager.setPrimaryClip(clip)
        }
    }
}
