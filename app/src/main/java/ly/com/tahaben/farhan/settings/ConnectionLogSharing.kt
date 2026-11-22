package ly.com.tahaben.farhan.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

/** Copy and Share for the connection log. The log holds ports and exception text, never a code or a key. */
internal object ConnectionLogSharing {
    /** Marked sensitive like the verification code copy, so the clipboard preview does not show it. */
    fun copy(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Farhan connection log", text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        manager.setPrimaryClip(clip)
    }

    fun share(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
