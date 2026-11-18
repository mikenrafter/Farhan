package ly.com.tahaben.notification_filter_data.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ly.com.tahaben.notification_filter_domain.schedule.AutomationAck
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import javax.inject.Inject

/**
 * The exported, versioned automation entry (Tasker, adb). Contract version 1, documented in
 * `notification_filter/AUTOMATION.md`.
 *
 * Action [ACTION_SET_SCHEDULE]; string extras [EXTRA_CREDENTIAL] and [EXTRA_PAYLOAD]. Nothing else is read: no
 * Serializable, no Parcelable, no other extra. Senders must address this app explicitly (package or component);
 * Android does not deliver implicit custom broadcasts to manifest receivers.
 *
 * Acknowledgement: an ordered broadcast gets result code [Activity.RESULT_OK] and data "accepted", or
 * [Activity.RESULT_CANCELED] and data "rejected:<ERROR>". Every outcome after authentication is also stored as the
 * "last result" the settings screen shows. No credential, payload or notification text is logged or returned.
 *
 * Admission: at most [MAX_IN_FLIGHT] requests run at once. Any further request is answered `rejected:BUSY` at
 * once (ordered broadcasts) without being parsed, queued or recorded; retry it. An accepted payload is safe to
 * resend: the last accepted write wins and a replay never refills spent points.
 */
@AndroidEntryPoint
class ScheduleAutomationReceiver : BroadcastReceiver() {
    @Inject lateinit var entry: ScheduleAutomationEntry

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SET_SCHEDULE) return
        val ordered = isOrderedBroadcast
        // Admission first, before extras are unparcelled, parsed or stored: a flood costs one failed tryAcquire each.
        if (!admission.tryEnter()) {
            if (ordered) { resultCode = Activity.RESULT_CANCELED; resultData = "rejected:" + AutomationError.BUSY.name }
            return
        }
        val pending: PendingResult
        val credential: String?
        val payload: String?
        try {
            // getStringExtra unparcels the whole extras bundle; a hostile sender's bad parcel must not crash us.
            val extras = try { intent.getStringExtra(EXTRA_CREDENTIAL) to intent.getStringExtra(EXTRA_PAYLOAD) }
                catch (_: RuntimeException) { null to null }
            credential = extras.first; payload = extras.second
            pending = goAsync()
        } catch (error: Throwable) { admission.leave(); throw error }
        scope.launch {
            val ack = try {
                withTimeout(HANDLE_TIMEOUT_MILLIS) { entry.handle(credential, payload, System.currentTimeMillis()) }
            } catch (_: TimeoutCancellationException) {
                AutomationAck.Rejected(AutomationError.STORAGE_FAILED)
            } catch (_: Exception) {
                AutomationAck.Rejected(AutomationError.STORAGE_FAILED)
            }
            try {
                admission.leave()
                if (ordered) {
                    pending.resultCode = if (ack is AutomationAck.Accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    pending.resultData = when (ack) {
                        AutomationAck.Accepted -> "accepted"
                        is AutomationAck.Rejected -> "rejected:" + ack.error.name
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SET_SCHEDULE = "ly.com.tahaben.notification_filter.action.SET_SCHEDULE"
        const val EXTRA_CREDENTIAL = "credential"
        const val EXTRA_PAYLOAD = "payload"
        /** A broadcast receiver must finish in about ten seconds; storage should take milliseconds. */
        const val HANDLE_TIMEOUT_MILLIS = 8_000L
        /** Requests handled at once. Others get BUSY immediately and are not queued; the sender retries. */
        const val MAX_IN_FLIGHT = 2
        private val admission = AutomationAdmission(MAX_IN_FLIGHT)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
