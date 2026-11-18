package ly.com.tahaben.farhan_rescue.ui

import android.os.Handler
import ly.com.tahaben.farhan_rescue.bridge.Cancellable
import ly.com.tahaben.farhan_rescue.bridge.Scheduler

internal class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
