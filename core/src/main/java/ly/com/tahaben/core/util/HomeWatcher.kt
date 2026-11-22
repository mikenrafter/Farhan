package ly.com.tahaben.core.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import timber.log.Timber

class HomeWatcher(private val context: Context) {

    private var listener: OnHomePressedListener? = null
    private var receiver: InnerReceiver? = null
    private var registered = false
    private val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)

    fun setOnHomePressedListener(listener: OnHomePressedListener) {
        this.listener = listener
        receiver = InnerReceiver()
    }

    fun startWatch() {
        val r = receiver ?: return
        if (registered) return
        // Android 14+ throws SecurityException unless the receiver says whether it is exported, and newer releases no
        // longer treat this action as system-only. NOT_EXPORTED still receives the system's own broadcast.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, filter)
            }
            registered = true
        } catch (e: Exception) {
            Timber.e(e, "Home watcher could not register")
        }
    }

    fun stopWatch() {
        val r = receiver ?: return
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(r)
        } catch (e: Exception) {
            Timber.e(e, "Home watcher could not unregister")
        }
    }

    inner class InnerReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            val reason = intent.getStringExtra("reason") ?: return
            if (reason != "homekey") return
            listener?.onHomePressed()
        }
    }

    interface OnHomePressedListener {
        fun onHomePressed()
    }
}