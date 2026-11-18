package ly.com.tahaben.notification_filter_data.automation

import ly.com.tahaben.notification_filter_domain.schedule.AutomationAck
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import ly.com.tahaben.notification_filter_domain.schedule.AutomationReceipt
import ly.com.tahaben.notification_filter_domain.schedule.AutomationReceiptStore
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleAutomation
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleIntent
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the receiver does after it has read two strings from the intent, so it is testable on the JVM:
 * authenticate, bound and parse, hand to the shared [ScheduleAutomation], and record a sanitized receipt.
 *
 * CLAIM(auth-before-parse): a disabled receiver or a wrong credential is answered before the payload is looked
 * at [ScheduleAutomationEntryTest].
 * CLAIM(receipt-sanitized): receipts hold a time, a mode and an error name; never the credential or the payload.
 *
 * Disabled requests record nothing and wrong-credential requests record at most once per
 * [AUTH_FAILURE_RECORD_INTERVAL_MILLIS], so an unauthenticated sender cannot make this app write storage in a loop.
 */
@Singleton
class ScheduleAutomationEntry @Inject constructor(
    private val automation: ScheduleAutomation,
    private val receipts: AutomationReceiptStore,
) {
    private var lastAuthFailureRecordedAt = Long.MIN_VALUE / 2

    /** True at most once per interval. Atomic: concurrent requests cannot both pass. */
    @Synchronized private fun shouldRecordAuthFailure(nowMillis: Long): Boolean {
        if (nowMillis - lastAuthFailureRecordedAt < AUTH_FAILURE_RECORD_INTERVAL_MILLIS) return false
        lastAuthFailureRecordedAt = nowMillis
        return true
    }

    suspend fun handle(credential: String?, payload: String?, nowMillis: Long): AutomationAck {
        val boundedCredential = credential?.takeIf { it.length <= MAX_CREDENTIAL_CHARS }
        var mode: ScheduleMode? = null
        val ack = run {
            automation.authenticate(boundedCredential)?.let { return@run AutomationAck.Rejected(it) }
            val raw = payload ?: return@run AutomationAck.Rejected(AutomationError.MALFORMED_PAYLOAD)
            when (val parsed = ScheduleIntentParser.parse(raw)) {
                is ScheduleIntentParser.Result.Failed -> AutomationAck.Rejected(parsed.error)
                is ScheduleIntentParser.Result.Parsed -> {
                    mode = if (parsed.mode == "dated") ScheduleMode.DATED else if (parsed.mode == null || parsed.mode == "repeating") ScheduleMode.REPEATING else null
                    automation.handle(ScheduleIntent(parsed.version, parsed.mode, parsed.localDate, parsed.windows, boundedCredential))
                }
            }
        }
        record(ack, mode, nowMillis)
        return ack
    }

    private fun record(ack: AutomationAck, mode: ScheduleMode?, nowMillis: Long) {
        val error = (ack as? AutomationAck.Rejected)?.error
        when (error) {
            AutomationError.DISABLED -> return
            AutomationError.BAD_CREDENTIAL -> if (!shouldRecordAuthFailure(nowMillis)) return
            else -> Unit
        }
        try { receipts.record(AutomationReceipt(nowMillis, mode, error)) } catch (_: Exception) { /* the ack is still returned */ }
    }

    companion object {
        const val MAX_CREDENTIAL_CHARS = 128
        const val AUTH_FAILURE_RECORD_INTERVAL_MILLIS = 10_000L
    }
}
