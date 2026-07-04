package ly.com.tahaben.notification_filter_data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationDeliveryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var deliveryEngine: NotificationDeliveryEngine

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotificationDeliveryScheduler.ACTION_DELIVER_PENDING &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                deliveryEngine.maintainDeliverySchedule(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
