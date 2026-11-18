package ly.com.tahaben.farhan.settings

import android.app.AlertDialog
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ly.com.tahaben.settings_portability.*
import java.io.File
import java.util.concurrent.Executors

/** User chosen documents are the only public file boundary. Parsing and all store/file work run off main. */
class SettingsBackupActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    // The document picker can recreate this Activity or its process before returning a URI.
    private val pendingExport by lazy { PrivateSettingsFile(File(noBackupFilesDir, "settings_export_pending_v1")) }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "settings-document").apply { isDaemon = true } }
    private var pendingOperations = 0
    private val busy get() = pendingOperations > 0
    private var terminalOutcome = false

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        work {
            if (uri == null) {
                pendingExport.clear()
                onUi { status.text = "Export cancelled." }
                return@work
            }
            val bytes = checkNotNull(pendingExport.read()?.get("document")) { "Prepared backup is missing" }
                .toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES && PortableSettingsCodec.decode(bytes) is DecodeResult.Valid)
            contentResolver.openOutputStream(uri, "wt").use { stream ->
                checkNotNull(stream) { "Cannot open document" }
                stream.write(bytes)
                stream.flush()
            }
            pendingExport.clear()
            onUi { status.text = "Settings backup saved." }
        }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val bytes = contentResolver.openInputStream(uri).use { stream ->
                checkNotNull(stream) { "Cannot open document" }
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val remaining = MAX_BYTES + 1 - output.size()
                    if (remaining <= 0) error("Backup exceeds size limit")
                    val count = stream.read(chunk, 0, minOf(chunk.size, remaining))
                    if (count < 0) break
                    output.write(chunk, 0, count)
                    check(output.size() <= MAX_BYTES) { "Backup exceeds size limit" }
                }
                output.toByteArray()
            }
            when (val preview = ProcessStores.preview(bytes)) {
                is StageResult.Staged -> onUi { confirmReplace(bytes, preview.preview) }
                else -> onUi { status.text = describe(preview) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings backup"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        status = TextView(this).apply {
            text = "Save your settings to a file or replace them from a backup. Notification and usage history, credentials and device permissions are excluded. Automation is disabled after import.\n\nWidget styles are saved as choices. Add widgets again after restoring. Refresh intervals are device scheduling state and are not included. Keep backup files private."
        }
        exportButton = Button(this).apply {
            text = "Export settings"
            setOnClickListener {
                work {
                    when (val exported = ProcessStores.export()) {
                        is PortableSettingsResult.Ready -> {
                            val bytes = PortableSettingsCodec.encode(exported.settings)
                            check(bytes.size <= MAX_BYTES)
                            check(PortableSettingsCodec.decode(bytes) is DecodeResult.Valid)
                            pendingExport.write(mapOf("document" to bytes.toString(Charsets.UTF_8)))
                            onUi {
                                val unknown = exported.settings.unsupportedKeys
                                status.text = if (unknown.isEmpty()) "Choose where to save your settings backup."
                                    else "The following settings are not supported and will be omitted:\n" + unknown.joinToString("\n") { it.key }
                                if (unknown.isEmpty()) createDocument.launch("farhan-settings.json")
                                else AlertDialog.Builder(this@SettingsBackupActivity).setTitle("Some settings will be omitted")
                                    .setMessage(status.text).setNegativeButton("Cancel") { _, _ -> discardPreparedExport() }
                                    .setOnCancelListener { discardPreparedExport() }
                                    .setPositiveButton("Choose file") { _, _ -> createDocument.launch("farhan-settings.json") }.show()
                            }
                        }
                        is PortableSettingsResult.Failed -> onUi { status.text = "Export refused: ${exported.error.code.name}" }
                    }
                }
            }
        }
        importButton = Button(this).apply {
            text = "Import settings"
            setOnClickListener { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
        layout.addView(status)
        layout.addView(exportButton)
        layout.addView(importButton)
        setContentView(layout)
        showOutcome()
    }

    private fun showOutcome() = work {
        when (val record = ProcessStores.outcome()) {
            is ImportJournal.Applied -> onUi { acknowledgeOutcome("Settings were restored. Automation is off. Check device permissions and add widgets again using the saved style choices.") }
            is ImportJournal.RolledBack -> onUi { acknowledgeOutcome("Import was rolled back. Your previous settings were restored. Failure: ${record.failure.reason.name}${record.failure.component?.let { " / ${it.name}" } ?: ""}") }
            is ImportJournal.Staged -> onUi { status.text = "An import is ready. Close Farhan and reopen it to apply the confirmed replacement." }
            else -> Unit
        }
    }
    private fun acknowledgeOutcome(message: String) {
        terminalOutcome = true
        updateButtons()
        AlertDialog.Builder(this).setTitle("Import outcome").setMessage(message)
            .setCancelable(false).setPositiveButton("Acknowledge") { _, _ ->
                work {
                    ProcessStores.acknowledge()
                    onUi { terminalOutcome = false; status.text = message; updateButtons() }
                }
            }.show()
    }
    private fun confirmReplace(bytes: ByteArray, preview: ImportPreview) {
        val unsupported = if (preview.unsupportedKeys.isEmpty()) "" else
            "\n\nUnsupported settings were omitted:\n" + preview.unsupportedKeys.joinToString("\n") { it.key }
        val message = "Source: ${preview.source.packageName} ${preview.source.versionName}, user ${preview.source.userId}.\n\n" +
            "${preview.sharedPreferenceCount} shared settings, ${preview.dataStoreCount} app settings, " +
            "${preview.appActivityRowCount} launcher activities and ${preview.timeLimitRowCount} time limits.\n\n" +
            "Replace all current configuration and close Farhan? Open Farhan again to apply it before normal features start. " +
            "History is kept. Automation will be disabled. Launcher activities may need the same device and user. " +
            "Widget styles are restored as choices when adding widgets again. Device permissions and refresh intervals are not restored." + unsupported
        AlertDialog.Builder(this).setTitle("Replace settings?").setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace and close") { _, _ ->
                work {
                    when (val staged = ProcessStores.stage(bytes)) {
                        is StageResult.Staged -> onUi { finishAndRemoveTask(); Process.killProcess(Process.myPid()) }
                        else -> onUi { status.text = describe(staged) }
                    }
                }
            }.show()
    }
    private fun describe(result: StageResult): String = when (result) {
        is StageResult.Staged -> "Import is ready. Close and reopen Farhan."
        is StageResult.Rejected -> "Import refused: ${result.error.code.name}"
        is StageResult.NotStaged -> "Import was not staged: ${result.reason.name}. Acknowledge the previous outcome before another import."
    }
    private fun updateButtons() {
        exportButton.isEnabled = !busy
        importButton.isEnabled = !busy && !terminalOutcome
    }
    private fun discardPreparedExport() = work { pendingExport.clear() }
    private fun work(block: () -> Unit) {
        // A picker result may arrive while the startup outcome check is still running. Queue it, never drop it.
        pendingOperations++
        updateButtons()
        worker.execute {
            try { block() } catch (failure: Exception) { onUi { status.text = if (failure is UnsupportedSettingsStorageType)
                "Settings operation failed: UNSUPPORTED_STORAGE_TYPE. No success was recorded."
                else "Settings operation failed. No success was recorded. Try again." } }
            finally { onUi { pendingOperations--; updateButtons() } }
        }
    }
    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
    private fun onUi(action: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    companion object { private const val MAX_BYTES = 1 shl 20 }
}
