package ly.com.tahaben.farhan_rescue.bridge

import ly.com.tahaben.farhan_rescue.adb.PackageVersion
import ly.com.tahaben.farhan_rescue.capture.*
import ly.com.tahaben.farhan_rescue.input.*

enum class OperationKind { PAIR, CHECK, EXPORT }

/**
 * What the UI may ask the bridge. There is no free-text command: the bridge decodes into these types, and every
 * field is re-validated there (CLAIM(bridge-revalidates): a caller bypassing the form still cannot reach a
 * non-loopback host, a bad package or a bad port [BridgeProtocolTest]).
 */
sealed interface BridgeRequest {
    val kind: OperationKind

    data class Pair(val host: LoopbackHost, val port: TcpPort, val code: PairingCode) : BridgeRequest {
        override val kind get() = OperationKind.PAIR
    }

    data class Check(val host: LoopbackHost, val port: TcpPort, val target: SourceTarget) : BridgeRequest {
        override val kind get() = OperationKind.CHECK
    }

    data class Export(val host: LoopbackHost, val port: TcpPort, val target: SourceTarget, val jobId: JobId) : BridgeRequest {
        override val kind get() = OperationKind.EXPORT
    }
}

sealed interface BridgeEvent {
    data class Hello(val pid: Int) : BridgeEvent
    data class ProgressUpdate(val progress: Progress) : BridgeEvent
    data object Paired : BridgeEvent
    data class Checked(val facts: CheckFacts) : BridgeEvent
    data class Captured(val facts: CaptureFacts) : BridgeEvent
    data class Failed(val code: FailureCode) : BridgeEvent
}

/** Binder message ids. */
object BridgeMessages {
    const val REGISTER = 1
    const val REQUEST = 2
    const val CANCEL = 3
    const val EVENT = 101
}

/**
 * Flat key-value form of requests and events, the only thing that crosses Binder. Values are String, Int, Long or a
 * list of String; the Android layer copies them into a Bundle. CLAIM(small-binder): the largest event carries at
 * most [MAX_LOCAL_PATHS] short path strings, far below the 1 MiB transaction limit; recovered settings never cross
 * Binder, they stay in the private job directory [BridgeProtocolTest].
 */
typealias Wire = Map<String, Any?>

object BridgeWire {
    const val MAX_LOCAL_PATHS = 300
    private const val KIND = "kind"

    /** Request fields plus the UI's operation id, which the bridge echoes on every event of that operation. */
    fun encode(request: BridgeRequest, operationId: Long): Wire = requestFields(request) + ("op" to operationId)

    /** The operation id carried by a request or an event; null when absent or not a Long. */
    fun operationOf(wire: Wire): Long? = wire["op"] as? Long

    private fun requestFields(request: BridgeRequest): Wire = when (request) {
        is BridgeRequest.Pair -> mapOf(KIND to "pair", "host" to request.host.address, "port" to request.port.value.toString(), "code" to request.code.reveal())
        is BridgeRequest.Check -> mapOf(KIND to "check", "host" to request.host.address, "port" to request.port.value.toString(),
            "package" to request.target.packageName.value, "user" to request.target.user.value.toString())
        is BridgeRequest.Export -> mapOf(KIND to "export", "host" to request.host.address, "port" to request.port.value.toString(),
            "package" to request.target.packageName.value, "user" to request.target.user.value.toString(), "job" to request.jobId.value)
    }

    /** Null for anything that is not exactly a valid request. Never throws on hostile input. */
    fun decodeRequest(wire: Wire): BridgeRequest? {
        fun text(key: String): String? = wire[key] as? String
        val host = LoopbackHost.parse(text("host") ?: return null).orNull() ?: return null
        val port = TcpPort.parse(text("port") ?: return null).orNull() ?: return null
        fun target(): SourceTarget? {
            val pkg = PackageName.parse(text("package") ?: return null).orNull() ?: return null
            val user = AndroidUserId.parse(text("user") ?: return null).orNull() ?: return null
            return SourceTarget(pkg, user)
        }
        return when (text(KIND)) {
            "pair" -> BridgeRequest.Pair(host, port, PairingCode.parse(text("code") ?: return null).orNull() ?: return null)
            "check" -> BridgeRequest.Check(host, port, target() ?: return null)
            "export" -> BridgeRequest.Export(host, port, target() ?: return null, JobId.parse(text("job") ?: return null).orNull() ?: return null)
            else -> null
        }
    }

    fun encode(event: BridgeEvent, operationId: Long? = null): Wire =
        eventFields(event).let { if (operationId != null) it + ("op" to operationId) else it }

    private fun eventFields(event: BridgeEvent): Wire = when (event) {
        is BridgeEvent.Hello -> mapOf(KIND to "hello", "pid" to event.pid)
        is BridgeEvent.ProgressUpdate -> mapOf(KIND to "progress", "stage" to event.progress.stage.name,
            "done" to event.progress.done, "total" to event.progress.total)
        BridgeEvent.Paired -> mapOf(KIND to "paired")
        is BridgeEvent.Checked -> mapOf(KIND to "checked", "uid" to event.facts.uid,
            "versionName" to (event.facts.version?.name ?: ""), "versionCode" to (event.facts.version?.code ?: -1L),
            "files" to event.facts.plannedFileCount, "database" to if (event.facts.hasDatabase) 1 else 0,
            "backup" to if (event.facts.usedPreferencesBackup) 1 else 0)
        is BridgeEvent.Captured -> mapOf(KIND to "captured", "uid" to event.facts.uid,
            "versionName" to (event.facts.version?.name ?: ""), "versionCode" to (event.facts.version?.code ?: -1L),
            "paths" to event.facts.localPaths, "backup" to if (event.facts.usedPreferencesBackup) 1 else 0,
            "integrity" to event.facts.integrity.name)
        is BridgeEvent.Failed -> mapOf(KIND to "failed", "code" to event.code.name)
    }

    /** Null for a malformed event; the UI then treats the bridge as failed (BRIDGE_DIED), never guesses. */
    fun decodeEvent(wire: Wire): BridgeEvent? = try {
        fun int(key: String) = wire[key] as? Int
        fun long(key: String) = wire[key] as? Long
        fun string(key: String) = wire[key] as? String
        fun version(): PackageVersion? {
            val name = string("versionName") ?: return null
            val code = long("versionCode") ?: return null
            return if (name.isEmpty() || code < 0) null else PackageVersion(name, code)
        }
        when (string(KIND)) {
            "hello" -> int("pid")?.takeIf { it > 0 }?.let(BridgeEvent::Hello)
            "progress" -> BridgeEvent.ProgressUpdate(Progress(Stage.valueOf(string("stage")!!), int("done")!!, int("total")!!))
            "paired" -> BridgeEvent.Paired
            "checked" -> BridgeEvent.Checked(CheckFacts(long("uid")!!, version(), int("files")!!, int("database") == 1, int("backup") == 1))
            "captured" -> {
                val paths = (wire["paths"] as List<*>).map { it as String }
                if (paths.size > MAX_LOCAL_PATHS) null
                else BridgeEvent.Captured(CaptureFacts(long("uid")!!, version(), paths, int("backup") == 1, Integrity.valueOf(string("integrity")!!)))
            }
            "failed" -> BridgeEvent.Failed(FailureCode.valueOf(string("code")!!))
            else -> null
        }
    } catch (failure: Exception) {
        null
    }
}
