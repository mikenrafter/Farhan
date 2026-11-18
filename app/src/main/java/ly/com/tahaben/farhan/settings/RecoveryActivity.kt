package ly.com.tahaben.farhan.settings

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ly.com.tahaben.settings_portability.StartupResult

/** Deliberately has no Hilt, Compose, preference or database dependency. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        val message = TextView(this)
        fun failureText(): String {
            val failure = (ProcessStores.startupResult as? StartupResult.RecoveryRequired)?.failure
            return "Settings recovery is required. Normal Farhan features are paused.\n\n" +
                (failure?.let { "Failure: ${it.reason.name}${it.component?.let { component -> " / ${component.name}" } ?: ""}" }
                    ?: "The recovery state could not be restored.") +
                "\n\nRetry keeps your previous settings available for recovery."
        }
        message.text = failureText()
        layout.addView(message)
        val retry = Button(this).apply { text = "Retry recovery" }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finishAndRemoveTask(); Process.killProcess(Process.myPid()) }
        }
        retry.setOnClickListener {
            retry.isEnabled = false
            Thread({
                val healed = try {
                    val outcome = ProcessStores.retry()
                    if (outcome.allowsNormalOperation) { RecoveryComponents(this).heal(); true } else false
                } catch (_: Exception) { false }
                runOnUiThread {
                    if (healed) {
                        message.text = "Settings recovery finished. Close this screen and open Farhan again.\n\nCheck your home app, notification access and accessibility permissions after reopening."
                        retry.visibility = android.view.View.GONE
                    } else {
                        message.text = failureText()
                        retry.isEnabled = true
                    }
                }
            }, "settings-retry").start()
        }
        layout.addView(retry)
        layout.addView(close)
        setContentView(layout)
    }
}
