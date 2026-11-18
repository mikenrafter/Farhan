package ly.com.tahaben.farhan_rescue.bridge

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import ly.com.tahaben.farhan_rescue.RescuePaths
import ly.com.tahaben.farhan_rescue.adb.AdbShell
import ly.com.tahaben.farhan_rescue.adb.IdentityFactory
import ly.com.tahaben.farhan_rescue.adb.RescueAdbManager
import ly.com.tahaben.farhan_rescue.capture.CaptureEngine
import ly.com.tahaben.farhan_rescue.capture.CaptureException
import ly.com.tahaben.farhan_rescue.capture.FailureCode
import ly.com.tahaben.farhan_rescue.input.LoopbackHost
import ly.com.tahaben.farhan_rescue.input.TcpPort
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only code that talks to ADB. It runs in its own process (`:adb_bridge`), bound and not exported.
 *
 * Why a separate process: libadb's pairing opens a socket with no read timeout and holds the manager lock while it
 * waits [PairingConnectionCtx.java: `new Socket(host, port)`, blocking TLS/readFully; AbsAdbConnectionManager.pair is
 * `synchronized (mLock)`]. Closing the manager or cancelling a coroutine cannot reach that thread. The service's main
 * thread stays free, so it can end the whole process on a cancel message (CLAIM(real-cancel)), and the UI can kill
 * the same pid as a backup.
 *
 * Invariants:
 *  - CLAIM(one-inflight): a second request while one runs is answered BUSY and not started.
 *  - CLAIM(bridge-revalidates): the request is decoded through [BridgeWire.decodeRequest], which re-parses every
 *    field; a non-loopback host never reaches libadb.
 *  - CLAIM(no-secret-log): nothing here logs a request, a pairing code, an exception message or any output text.
 *  - CLAIM(unbind-ends-process): when the last client unbinds the process ends, so a blocked socket never lingers.
 *  - Recovered bytes are written to the private job directory; only counts, paths and flags are sent back.
 */
class AdbBridgeService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper()) { message -> handle(message); true }
    private val inbound = Messenger(mainHandler)
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "adb-bridge-worker").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    @Volatile private var client: Messenger? = null
    private lateinit var paths: RescuePaths

    override fun onCreate() {
        super.onCreate()
        paths = RescuePaths(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = inbound.binder

    override fun onUnbind(intent: Intent?): Boolean {
        endProcess()
        return false
    }

    override fun onDestroy() {
        endProcess()
    }

    private fun endProcess() {
        Process.killProcess(Process.myPid())
    }

    private fun handle(message: Message) {
        when (message.what) {
            BridgeMessages.REGISTER -> {
                client = message.replyTo
                send(BridgeEvent.Hello(Process.myPid()), null)
            }
            BridgeMessages.CANCEL -> endProcess()
            BridgeMessages.REQUEST -> {
                val wire = BundleWire.fromBundle(message.data)
                val request = BridgeWire.decodeRequest(wire)
                val operation = BridgeWire.operationOf(wire) ?: -1L
                when {
                    request == null -> send(BridgeEvent.Failed(FailureCode.BAD_REQUEST), operation)
                    !running.compareAndSet(false, true) -> send(BridgeEvent.Failed(FailureCode.BUSY), operation)
                    else -> worker.execute {
                        try { send(execute(request, operation), operation) } finally { running.set(false) }
                    }
                }
            }
        }
    }

    private fun send(event: BridgeEvent, operation: Long?) {
        val target = client ?: return
        val reply = Message.obtain(null, BridgeMessages.EVENT).apply { data = BundleWire.toBundle(BridgeWire.encode(event, operation)) }
        try { target.send(reply) } catch (gone: RemoteException) { endProcess() }
    }

    private fun execute(request: BridgeRequest, operation: Long): BridgeEvent = try {
        when (request) {
            is BridgeRequest.Pair -> pair(request)
            is BridgeRequest.Check -> withConnection(request.host, request.port) { shell ->
                BridgeEvent.Checked(CaptureEngine(shell) { progress -> send(BridgeEvent.ProgressUpdate(progress), operation) }.check(request.target))
            }
            is BridgeRequest.Export -> withConnection(request.host, request.port) { shell ->
                val job = try { paths.rawJobs.create(request.jobId) } catch (failure: Exception) { throw CaptureException(FailureCode.STORAGE_FAILED) }
                val facts = CaptureEngine(shell) { progress -> send(BridgeEvent.ProgressUpdate(progress), operation) }.export(request.target, job)
                // CLAIM(identity-erased): the key is useless once the capture completed, so it is removed now.
                paths.identity.erase()
                BridgeEvent.Captured(facts)
            }
        }
    } catch (failure: CaptureException) {
        BridgeEvent.Failed(failure.code)
    } catch (failure: AdbPairingRequiredException) {
        BridgeEvent.Failed(FailureCode.PAIRING_REQUIRED)
    } catch (failure: AdbAuthenticationFailedException) {
        BridgeEvent.Failed(FailureCode.PAIRING_REQUIRED)
    } catch (failure: java.io.IOException) {
        // Refused socket, dropped connection or failed stream. Only the class is known; never the message.
        BridgeEvent.Failed(FailureCode.CONNECT_FAILED)
    } catch (failure: Exception) {
        BridgeEvent.Failed(FailureCode.INTERNAL)
    }

    private fun pair(request: BridgeRequest.Pair): BridgeEvent {
        val now = System.currentTimeMillis()
        val identity = try {
            paths.identity.load(now) ?: IdentityFactory.generate(now).also(paths.identity::save)
        } catch (failure: Exception) {
            return BridgeEvent.Failed(FailureCode.IDENTITY_FAILED)
        }
        val manager = RescueAdbManager.restore(identity)
        // CLAIM(pair-true-only): libadb documents `true` for success and "false otherwise"; only true counts.
        val paired = try {
            manager.pair(request.host.address, request.port.value, request.code.reveal())
        } catch (failure: Exception) {
            false
        } finally {
            closeQuietly(manager)
        }
        return if (paired) BridgeEvent.Paired else BridgeEvent.Failed(FailureCode.PAIR_FAILED)
    }

    private fun withConnection(host: LoopbackHost, port: TcpPort, block: (AdbShell) -> BridgeEvent): BridgeEvent {
        val identity = paths.identity.load(System.currentTimeMillis()) ?: return BridgeEvent.Failed(FailureCode.PAIRING_REQUIRED)
        val manager = RescueAdbManager.restore(identity)
        try {
            val connected = manager.connect(host.address, port.value)
            if (!connected) return BridgeEvent.Failed(FailureCode.CONNECT_FAILED)
            return block(AdbShell(manager))
        } finally {
            closeQuietly(manager)
        }
    }

    private fun closeQuietly(manager: RescueAdbManager) {
        try { manager.disconnect() } catch (ignored: Exception) { }
    }
}
