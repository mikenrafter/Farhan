package ly.com.tahaben.farhan_rescue.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ly.com.tahaben.farhan_rescue.R
import ly.com.tahaben.farhan_rescue.RescuePaths
import ly.com.tahaben.farhan_rescue.bridge.*
import ly.com.tahaben.farhan_rescue.capture.CaptureFacts
import ly.com.tahaben.farhan_rescue.capture.FailureCode
import ly.com.tahaben.farhan_rescue.convert.AndroidWidgetProfilePort
import ly.com.tahaben.farhan_rescue.convert.ConversionResult
import ly.com.tahaben.farhan_rescue.convert.ExportPreview
import ly.com.tahaben.farhan_rescue.convert.RescueConverter
import ly.com.tahaben.farhan_rescue.input.*
import ly.com.tahaben.settings_android.WidgetProfiles
import ly.com.tahaben.settings_portability.DecodeResult
import ly.com.tahaben.settings_portability.PortableSettingsCodec
import ly.com.tahaben.settings_portability.SettingValue
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * The whole helper screen: plain Activity, native views, no Hilt, no Compose.
 *
 * Lifecycle rules (all tested only on a device; see the report):
 *  - CLAIM(destroy-cancels): [onDestroy] cancels any running operation (kills the bridge through [BridgeSession]),
 *    deletes every raw copy, erases the pairing key when something was running or the screen is finishing, and
 *    unbinds the bridge, whose process then ends itself. Configuration changes do not recreate this Activity
 *    (manifest `configChanges`), so a rotation does not cancel.
 *  - CLAIM(one-inflight): all bridge steps go through [OperationTracker]; the buttons are also disabled while busy.
 *  - CLAIM(sam-after-valid): the Storage Access Framework picker opens only from [Phase.REVIEW], which exists only
 *    after [PortableFileSealer] accepted the file and it is stored as pending [ConversionResult.Ready].
 *  - CLAIM(no-code-kept): the pairing code lives in its field (autofill off, not saved) and in one request object;
 *    the field is cleared as soon as the request is sent.
 */
class RescueActivity : Activity() {
    private enum class Phase { IDLE, WORKING, CONVERTING, REVIEW, SAVING, SAVED }

    private lateinit var paths: RescuePaths
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newSingleThreadExecutor { Thread(it, "rescue-worker").apply { isDaemon = true } }
    private val converter = RescueConverter()
    private var destroyed = false

    private var phase = Phase.IDLE
    private var statusText: String = ""
    private var preview: ExportPreview? = null
    private var copiedWithoutDigest = false
    private var currentJob: JobId? = null
    private var currentTarget: SourceTarget? = null
    private var conversionToken = 0L

    private var bridgeBinder: IBinder? = null
    private var bridge: Messenger? = null
    private var generation = 0L
    private var rebindPending = false
    private val session = BridgeSession(Process.myPid(), { pid -> Process.killProcess(pid) }, { bridgeBinder?.pingBinder() == true })
    private val tracker = OperationTracker(session, HandlerScheduler(main), SystemClock::elapsedRealtime, onTimedOut = ::onTimedOut)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bridgeBinder = service
            bridge = Messenger(service)
            val connectionGeneration = session.onBound()
            generation = connectionGeneration
            // A fresh reply Messenger per connection, closed over its generation: a queued reply from an earlier
            // connection can never be mistaken for this one's [EventRouting].
            val inbound = Messenger(Handler(Looper.getMainLooper()) { message -> onBridgeMessage(message, connectionGeneration); true })
            sendToBridge(Message.obtain(null, BridgeMessages.REGISTER).apply { replyTo = inbound })
        }
        override fun onServiceDisconnected(name: ComponentName) = lostBridge()
        override fun onBindingDied(name: ComponentName) = lostBridge()
        override fun onNullBinding(name: ComponentName) = lostBridge()
    }

    private lateinit var packageField: EditText
    private lateinit var userField: EditText
    private lateinit var pairingPortField: EditText
    private lateinit var pairingCodeField: EditText
    private lateinit var connectPortField: EditText
    private lateinit var confirmStop: CheckBox
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var reviewView: TextView
    private lateinit var savedView: TextView
    private val setupViews = mutableListOf<View>()
    private val actionButtons = mutableListOf<Button>()
    private lateinit var cancelButton: Button
    private lateinit var saveButton: Button
    private lateinit var finishButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = RescuePaths(this)
        setContentView(buildLayout())
        restorePendingBackup()
        render()
        bindBridge()
    }

    override fun onDestroy() {
        destroyed = true
        val wasRunning = tracker.cancel()
        if (wasRunning) sendToBridge(Message.obtain(null, BridgeMessages.CANCEL))
        conversionToken++
        paths.rawJobs.deleteAll()
        if (wasRunning || isFinishing) paths.identity.erase()
        try { unbindService(connection) } catch (ignored: IllegalArgumentException) { }
        workers.shutdownNow()
        super.onDestroy()
    }

    // ---- bridge plumbing -------------------------------------------------------------------------------------

    private fun bindBridge() {
        bindService(Intent(this, AdbBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
        statusText = getString(R.string.bridge_starting)
    }

    private fun lostBridge() {
        session.onUnbound()
        bridgeBinder = null
        bridge = null
        if (tracker.cancel()) {
            paths.rawJobs.deleteAll()
            showFailure(FailureCode.BRIDGE_DIED)
        }
        if (!destroyed && !rebindPending) {
            rebindPending = true
            main.post {
                rebindPending = false
                if (destroyed) return@post
                try { unbindService(connection) } catch (ignored: IllegalArgumentException) { }
                bindBridge()
            }
        }
        render()
    }

    private fun sendToBridge(message: Message) {
        val target = bridge ?: return
        try { target.send(message) } catch (gone: RemoteException) { lostBridge() }
    }

    private fun start(request: BridgeRequest) {
        val operation = tracker.begin(request.kind)
        if (operation == null) {
            statusText = getString(if (session.isLive) R.string.failure_busy else R.string.bridge_starting)
            render()
            return
        }
        phase = Phase.WORKING
        statusText = getString(R.string.working)
        sendToBridge(Message.obtain(null, BridgeMessages.REQUEST).apply { data = BundleWire.toBundle(BridgeWire.encode(request, operation.id)) })
        render()
    }

    private fun onBridgeMessage(message: Message, connectionGeneration: Long) {
        if (message.what != BridgeMessages.EVENT) return
        val wire = BundleWire.fromBundle(message.data)
        val event = BridgeWire.decodeEvent(wire)
        if (event == null) {
            if (connectionGeneration == generation) failOperation(FailureCode.BRIDGE_DIED)
            return
        }
        if (!EventRouting.accept(event, connectionGeneration, generation, BridgeWire.operationOf(wire), tracker.inflight)) return
        when (event) {
            is BridgeEvent.Hello -> session.onHello(event.pid, generation).also {
                if (it && phase == Phase.IDLE && statusText == getString(R.string.bridge_starting)) statusText = ""
            }
            is BridgeEvent.ProgressUpdate -> {
                tracker.inflight?.let { tracker.progress(it.id) }
                val progress = event.progress
                statusText = if (progress.total > 0) getString(stageMessage(progress.stage), progress.done + 1, progress.total) else getString(stageMessage(progress.stage))
            }
            BridgeEvent.Paired -> finishOperation { statusText = getString(R.string.paired_ok) }
            is BridgeEvent.Checked -> finishOperation {
                val facts = event.facts
                val version = facts.version?.name ?: getString(R.string.checked_version_unknown)
                val database = getString(if (facts.hasDatabase) R.string.checked_database_yes else R.string.checked_database_no)
                statusText = getString(R.string.checked_ok, version, facts.plannedFileCount, database)
            }
            is BridgeEvent.Captured -> finishOperation { startConversion(event.facts) }
            is BridgeEvent.Failed -> failOperation(event.code)
        }
        render()
    }

    private inline fun finishOperation(then: () -> Unit) {
        tracker.inflight?.let { tracker.complete(it.id) }
        if (phase == Phase.WORKING) phase = Phase.IDLE
        then()
    }

    private fun failOperation(code: FailureCode) {
        val failed = tracker.inflight
        failed?.let { tracker.complete(it.id) }
        paths.rawJobs.deleteAll()
        // CLAIM(export-cleanup): a failed export leaves no raw copy and no pairing key; the source stays stopped until reopened.
        if (failed?.kind == OperationKind.EXPORT) { paths.identity.erase(); currentJob = null }
        showFailure(code)
        render()
    }

    private fun onTimedOut(operation: OperationTracker.Inflight) {
        currentJob = null
        paths.rawJobs.deleteAll()
        paths.identity.erase()
        showFailure(FailureCode.TIMED_OUT)
        render()
    }

    private fun showFailure(code: FailureCode) {
        phase = Phase.IDLE
        statusText = getString(failureMessage(code))
    }

    /** Cancel: end the bridge process (the only way to stop a blocked pairing), erase the key and every copy. */
    private fun cancelEverything() {
        sendToBridge(Message.obtain(null, BridgeMessages.CANCEL))
        tracker.cancel()
        conversionToken++
        paths.rawJobs.deleteAll()
        paths.identity.erase()
        showFailure(FailureCode.CANCELLED)
        render()
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    private fun onPair() {
        val port = parsePort(pairingPortField) ?: return
        val code = PairingCode.parse(pairingCodeField.text.toString()).orNull()
        if (code == null) { pairingCodeField.error = getString(R.string.error_code); return }
        pairingCodeField.text.clear()
        start(BridgeRequest.Pair(LoopbackHost.IPV4, port, code))
    }

    private fun onCheck() {
        val target = parseTarget() ?: return
        val port = parsePort(connectPortField) ?: return
        start(BridgeRequest.Check(LoopbackHost.IPV4, port, target))
    }

    private fun onExport() {
        val target = parseTarget() ?: return
        val port = parsePort(connectPortField) ?: return
        if (!confirmStop.isChecked) return
        val job = JobId.random()
        currentJob = job
        currentTarget = target
        paths.pending.clear()
        start(BridgeRequest.Export(LoopbackHost.IPV4, port, target, job))
    }

    private fun parseTarget(): SourceTarget? {
        val pkg = when (val parsed = PackageName.parse(packageField.text.toString().trim())) {
            is Checked.Ok -> parsed.value
            is Checked.Rejected -> {
                packageField.error = getString(if (parsed.error == InputError.PACKAGE_IS_HELPER) R.string.error_package_helper else R.string.error_package)
                return null
            }
        }
        val user = AndroidUserId.parse(userField.text.toString().trim()).orNull()
        if (user == null) { userField.error = getString(R.string.error_user); return null }
        return SourceTarget(pkg, user)
    }

    private fun parsePort(field: EditText): TcpPort? =
        TcpPort.parse(field.text.toString().trim()).orNull().also { if (it == null) field.error = getString(R.string.error_port) }

    private fun openWirelessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (missing: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---- conversion and saving -------------------------------------------------------------------------------

    private fun startConversion(facts: CaptureFacts) {
        copiedWithoutDigest = facts.integrity == ly.com.tahaben.farhan_rescue.capture.Integrity.SIZE_ONLY
        val job = currentJob
        val target = currentTarget
        if (job == null || target == null) { showFailure(FailureCode.INTERNAL); return }
        phase = Phase.CONVERTING
        statusText = getString(R.string.progress_converting)
        val token = ++conversionToken
        workers.execute {
            val result = try {
                val directory = paths.rawJobs.open(job)
                if (directory == null) ConversionResult.Failed(ly.com.tahaben.farhan_rescue.convert.ConversionFailure.FILES_MISSING)
                else converter.convert(directory, facts, target)
            } catch (failure: Exception) {
                ConversionResult.Failed(ly.com.tahaben.farhan_rescue.convert.ConversionFailure.COPY_UNREADABLE)
            } finally {
                paths.rawJobs.delete(job)
            }
            val stored = (result as? ConversionResult.Ready)?.let { ready ->
                try { paths.pending.save(ready.fileBytes); true } catch (failure: Exception) { false }
            } ?: true
            main.post {
                if (destroyed || token != conversionToken) { paths.pending.clear(); return@post }
                currentJob = null
                when {
                    result is ConversionResult.Ready && stored -> { preview = result.preview; phase = Phase.REVIEW; statusText = "" }
                    result is ConversionResult.Ready -> showFailure(FailureCode.STORAGE_FAILED)
                    result is ConversionResult.Failed -> {
                        phase = Phase.IDLE
                        val reason = getString(conversionMessage(result.failure), result.location ?: "")
                        statusText = getString(R.string.failure_prefix, reason)
                    }
                }
                render()
            }
        }
    }

    private fun restorePendingBackup() {
        val bytes = try { paths.pending.load() } catch (failure: Exception) { null } ?: return
        val settings = (PortableSettingsCodec.decode(bytes) as? DecodeResult.Valid)?.settings ?: run { paths.pending.clear(); return }
        val encoded = (settings.configuration.dataStore[WidgetProfiles.KEY] as? SettingValue.Text)?.value
        preview = ExportPreview(
            settings.source, settings.source.versionName != "unknown",
            settings.configuration.sharedPreferences.size, settings.configuration.dataStore.size,
            settings.configuration.rows.apps.size, settings.configuration.rows.timeLimits.size,
            try { AndroidWidgetProfilePort.count(encoded) } catch (failure: Exception) { 0 },
            settings.unsupportedKeys, emptyList(),
        )
        phase = Phase.REVIEW
        statusText = getString(R.string.pending_found)
    }

    @Suppress("DEPRECATION")
    private fun chooseSaveLocation() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "farhan-settings-${LocalDate.now()}.json")
        startActivityForResult(intent, REQUEST_SAVE)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            statusText = getString(R.string.save_cancelled)
            render()
            return
        }
        phase = Phase.SAVING
        statusText = getString(R.string.progress_saving)
        render()
        workers.execute {
            val saved = copyPendingTo(uri)
            main.post {
                if (destroyed) return@post
                phase = if (saved) Phase.SAVED else Phase.REVIEW
                statusText = if (saved) "" else getString(R.string.save_failed)
                render()
            }
        }
    }

    /** Writes the pending file and reads it back; true only when the document holds exactly those bytes. */
    private fun copyPendingTo(uri: Uri): Boolean = try {
        val bytes = paths.pending.load()
        if (bytes == null) false
        else {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: throw java.io.IOException()
            val readBack = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buffer.size() <= bytes.size) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            readBack != null && readBack.contentEquals(bytes)
        }
    } catch (failure: Exception) {
        false
    }

    private fun finishAndClear() {
        paths.pending.clear()
        paths.identity.erase()
        paths.rawJobs.deleteAll()
        preview = null
        phase = Phase.IDLE
        statusText = ""
        render()
    }

    // ---- views -----------------------------------------------------------------------------------------------

    private fun render() {
        val idle = phase == Phase.IDLE
        val busy = phase == Phase.WORKING || phase == Phase.CONVERTING || phase == Phase.SAVING
        setupViews.forEach { it.visibility = if (idle) View.VISIBLE else View.GONE }
        actionButtons.forEach { it.isEnabled = idle && session.isLive }
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        cancelButton.visibility = if (phase == Phase.WORKING || phase == Phase.CONVERTING) View.VISIBLE else View.GONE
        statusView.text = statusText
        statusView.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE
        val showReview = phase == Phase.REVIEW || phase == Phase.SAVING || phase == Phase.SAVED
        reviewView.visibility = if (showReview) View.VISIBLE else View.GONE
        reviewView.text = preview?.let(::reviewText).orEmpty()
        saveButton.visibility = if (phase == Phase.REVIEW || phase == Phase.SAVED) View.VISIBLE else View.GONE
        saveButton.isEnabled = phase == Phase.REVIEW
        savedView.visibility = if (phase == Phase.SAVED) View.VISIBLE else View.GONE
        finishButton.visibility = if (phase == Phase.REVIEW || phase == Phase.SAVED) View.VISIBLE else View.GONE
    }

    private fun reviewText(preview: ExportPreview): String {
        val source = preview.source
        val version = if (preview.versionKnown) source.versionName else getString(R.string.review_version_unknown)
        val lines = mutableListOf(
            getString(R.string.review_body, source.packageName, version, source.userId,
                preview.sharedPreferenceCount + preview.dataStoreCount, preview.appRowCount, preview.timeLimitCount, preview.widgetProfileCount),
        )
        if (preview.unsupported.isNotEmpty()) {
            val names = preview.unsupported.take(MAX_NAMES_SHOWN).joinToString(", ") { it.key }
            lines += getString(R.string.review_unsupported, preview.unsupported.size, names)
        }
        if (preview.omitted.isNotEmpty()) lines += getString(R.string.review_omitted, preview.omitted.size)
        if (copiedWithoutDigest) lines += getString(R.string.review_size_only)
        lines += getString(R.string.review_widgets)
        if (preview.appRowCount > 0) lines += getString(R.string.review_launcher_rows)
        return lines.joinToString("\n\n")
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        fun add(view: View, setup: Boolean = false, action: Boolean = false): View {
            column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (setup) setupViews += view
            return view
        }
        fun title(res: Int, setup: Boolean = true) = add(TextView(this).apply {
            setText(res); textSize = 18f; setPadding(0, dp(20), 0, dp(4))
        }, setup)
        fun body(res: Int, setup: Boolean = true) = add(TextView(this).apply { setText(res) }, setup)
        fun field(hint: Int, type: Int, maxLength: Int = 0): EditText = EditText(this).apply {
            setHint(hint); inputType = type
            if (maxLength > 0) filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }.also { add(it, true) }
        fun button(text: Int, setup: Boolean = true, guarded: Boolean = false, onClick: () -> Unit): Button =
            Button(this).apply { setText(text); setOnClickListener { onClick() } }.also {
                add(it, setup)
                if (guarded) actionButtons += it
            }

        add(TextView(this).apply { setText(R.string.app_name); textSize = 24f })
        body(R.string.intro, setup = false)
        statusView = add(TextView(this).apply { setPadding(0, dp(12), 0, dp(12)); textSize = 16f }) as TextView
        progressBar = add(ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }) as ProgressBar
        cancelButton = Button(this).apply { setText(R.string.cancel); setOnClickListener { cancelEverything() } }.also { add(it) }

        title(R.string.step_source)
        packageField = field(R.string.hint_package, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply { setText(PackageName.DEFAULT) }
        userField = field(R.string.hint_user, InputType.TYPE_CLASS_NUMBER, 9).apply { setText(AndroidUserId.DEFAULT.toString()) }

        title(R.string.step_pair)
        body(R.string.pair_body)
        button(R.string.open_wireless_settings) { openWirelessSettings() }
        pairingPortField = field(R.string.hint_pairing_port, InputType.TYPE_CLASS_NUMBER, 5)
        pairingCodeField = field(R.string.hint_pairing_code, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 6).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        button(R.string.pair, guarded = true) { onPair() }

        title(R.string.step_check)
        body(R.string.check_body)
        connectPortField = field(R.string.hint_connect_port, InputType.TYPE_CLASS_NUMBER, 5)
        button(R.string.check, guarded = true) { onCheck() }

        title(R.string.step_export)
        body(R.string.export_body)
        confirmStop = CheckBox(this).apply { setText(R.string.confirm_stop) }.also { add(it, true) }
        button(R.string.export, guarded = true) { onExport() }

        reviewView = add(TextView(this).apply { setPadding(0, dp(12), 0, dp(12)); visibility = View.GONE }) as TextView
        saveButton = button(R.string.save_file, setup = false) { chooseSaveLocation() }
        savedView = add(TextView(this).apply {
            text = getString(R.string.saved_title) + "\n\n" + getString(R.string.saved_next); visibility = View.GONE; setPadding(0, dp(12), 0, dp(12))
        }) as TextView
        finishButton = button(R.string.finish, setup = false) { finishAndClear() }

        button(R.string.reset, setup = false) { cancelEverything() }
        button(R.string.licenses, setup = false) { showLicenses() }
        return ScrollView(this).apply { addView(column); isFillViewport = true }
    }

    private fun showLicenses() {
        val text = resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
        val view = ScrollView(this).apply { addView(TextView(context).apply { this.text = text; setPadding(dp(16), dp(16), dp(16), dp(16)) }) }
        AlertDialog.Builder(this).setTitle(R.string.licenses).setView(view).setPositiveButton(R.string.close, null).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_SAVE = 41
        const val MAX_NAMES_SHOWN = 40
    }
}
