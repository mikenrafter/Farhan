package ly.com.tahaben.privileged_access_selfadb

import android.util.Log
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure

/**
 * What the self-ADB route says about itself. The platform log gets step names and exception class names only. The
 * [ConnectionLog] behind the app's "Copy log" also gets exception messages and cause chains; neither ever gets a
 * pairing code, a key, the adb public key or command output.
 */
internal interface StepLog {
    /** A step is about to run. Info level, so a hang shows as the last step reached. */
    fun reached(operation: String, step: SelfAdbStep)

    /** A step broke. Warn level. [cause] is the throwable, kept only for the connection log. */
    fun failed(operation: String, failure: StepFailure, cause: Throwable? = null)

    /** Something worth keeping that is not a start or a failure: a result, TLS details, a decision. Connection log only. */
    fun note(operation: String, step: SelfAdbStep?, text: String) = Unit
}

internal class AndroidStepLog(private val connectionLog: ConnectionLog) : StepLog {
    override fun reached(operation: String, step: SelfAdbStep) {
        try { Log.i(TAG, "$operation step=${step.tag}") } catch (ignored: RuntimeException) { }
        connectionLog.add(operation, step.tag, "start")
    }

    override fun failed(operation: String, failure: StepFailure, cause: Throwable?) {
        try { Log.w(TAG, "$operation failed step=${failure.step.tag} exception=${failure.exceptionClass}") } catch (ignored: RuntimeException) { }
        if (cause != null) connectionLog.addFailure(operation, failure.step.tag, cause)
        else connectionLog.add(operation, failure.step.tag, "FAIL", failure.exceptionClass)
    }

    override fun note(operation: String, step: SelfAdbStep?, text: String) {
        connectionLog.add(operation, step?.tag ?: "-", "info", text)
    }

    companion object { const val TAG = "FarhanSelfAdb" }
}

internal object NoStepLog : StepLog {
    override fun reached(operation: String, step: SelfAdbStep) = Unit
    override fun failed(operation: String, failure: StepFailure, cause: Throwable?) = Unit
}
