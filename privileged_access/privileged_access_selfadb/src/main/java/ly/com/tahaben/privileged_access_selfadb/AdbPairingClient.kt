package ly.com.tahaben.privileged_access_selfadb

import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/** An open TLS connection to a pairing port. */
internal interface PairingTls : Closeable {
    val input: InputStream
    val output: OutputStream

    /** The socket's read timeout, in milliseconds. */
    var readTimeoutMillis: Int

    /** RFC 5705 keying material, bound to this connection; adbd adds it to the pairing code. */
    fun exportKeyingMaterial(label: String, length: Int): ByteArray

    /** Negotiated protocol, cipher and the daemon's certificate, for the connection log. */
    fun describe(): String = ""
}

/** The two network steps, apart so a test can break each one. */
internal interface PairingTransport {
    fun connect(host: String, port: Int): Socket
    fun handshake(plain: Socket, host: String, port: Int): PairingTls
}

/**
 * Production transport. TLS comes from the bundled Conscrypt because the platform one hides `exportKeyingMaterial`
 * from apps. The sockets have timeouts so a daemon that stops answering ends the attempt instead of hanging it.
 */
internal class ConscryptPairingTransport(
    private val tls: TlsUpgrade,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
) : PairingTransport {
    /**
     * The TCP connect, and nothing that depends on the peer: the options are set on the unconnected socket, so a
     * daemon that resets right after accepting cannot make an option call fail and be mistaken for a connect failure.
     */
    override fun connect(host: String, port: Int): Socket = Socket().also { socket ->
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        } catch (failure: Throwable) {
            try { socket.close() } catch (ignored: IOException) { }
            throw failure
        }
    }

    /** Wrapping the socket and the handshake itself: the whole tls-handshake step, and nothing after it. */
    override fun handshake(plain: Socket, host: String, port: Int): PairingTls {
        val socket = tls.upgrade(plain, host, port)
        socket.soTimeout = readTimeoutMillis
        socket.startHandshake()
        return object : PairingTls {
            override val input: InputStream get() = socket.inputStream
            override val output: OutputStream get() = socket.outputStream
            override var readTimeoutMillis: Int
                get() = socket.soTimeout
                set(value) { socket.soTimeout = value }
            override fun exportKeyingMaterial(label: String, length: Int): ByteArray = exportFrom(socket, label, length)
            override fun describe(): String = TlsDetails.of(socket)
            override fun close() = socket.close()
        }
    }

    private fun exportFrom(socket: SSLSocket, label: String, length: Int): ByteArray =
        Conscrypt.exportKeyingMaterial(socket, label, null, length)
            ?: throw IOException("no keying material")
}

/** What a finished TLS handshake negotiated. Public facts of the connection: no key material. */
internal object TlsDetails {
    fun of(socket: SSLSocket): String = try {
        val session = socket.session
        val peer = try {
            (session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate)
                ?.let { "peer=${it.subjectX500Principal.name} sig=${it.sigAlgName} key=${it.publicKey.algorithm}" }
        } catch (failure: Exception) {
            "peer=<none: ${failure.javaClass.simpleName}>"
        }
        "protocol=${session.protocol} cipher=${session.cipherSuite} socket=${socket.javaClass.name} ${peer ?: ""}".trim()
    } catch (failure: Exception) {
        "details unavailable (${failure.javaClass.simpleName})"
    }
}

internal sealed interface PairingOutcome {
    data object Paired : PairingOutcome

    /** The daemon answered and did not accept the pairing: a wrong or expired code, or a closed dialog. */
    data object Rejected : PairingOutcome

    data class Broke(val at: StepFailure) : PairingOutcome
}

internal object PairingRetry {
    /** True when the attempt ended before anything made from the code was sent, so another port or way in may still work. */
    fun worthRetrying(outcome: PairingOutcome): Boolean =
        outcome is PairingOutcome.Broke && outcome.at.step in BEFORE_THE_CODE && !outcome.at.networkDenied

    /**
     * Which of two attempts (the typed port, then the discovered one) to report. If the second also broke before the
     * code was used, the first is reported: it is the port the user typed, and the second's reset may be only the
     * daemon tearing down after the first.
     */
    fun report(first: PairingOutcome, second: PairingOutcome): PairingOutcome =
        if (worthRetrying(second)) first else second

    val BEFORE_THE_CODE = setOf(SelfAdbStep.CONNECT_SOCKET, SelfAdbStep.TLS_HANDSHAKE, SelfAdbStep.FIRST_READ)
}

internal class StepException(val step: SelfAdbStep, cause: Throwable) : RuntimeException(null, cause)

/** Runs one step: logs its start and time, and tags anything it throws, errors included, with the step. */
internal class Stepper(val log: StepLog, val operation: String) {
    inline fun <T> run(step: SelfAdbStep, block: () -> T): T {
        log.reached(operation, step)
        val started = System.nanoTime()
        try {
            return block().also { log.note(operation, step, "ok in ${(System.nanoTime() - started) / 1_000_000} ms") }
        } catch (failure: Throwable) {
            throw StepException(step, failure)
        }
    }
}

/** A TLS connection that got past the handshake and a quiet first read, with the keying material taken from it. */
internal class OpenedTls(val plain: Socket, val tls: PairingTls, val input: InputStream, val keyingMaterial: ByteArray) : Closeable {
    override fun close() {
        try { tls.close() } catch (ignored: Throwable) { }
        try { plain.close() } catch (ignored: Throwable) { }
    }
}

internal object PairingTlsOpener {
    const val SETTLE_MILLIS = 500
    const val EXPORTED_KEY_LABEL = "adb-label\u0000"
    const val EXPORTED_KEY_BYTES = 64

    /**
     * The steps that need no pairing code, each its own step: TCP connect, TLS handshake, the first read, then the key
     * export. Nothing made from the code has been sent when this returns, so a caller can stop here without using it
     * up. Throws [StepException]; whatever was opened is closed first.
     *
     * The first read exists because TLS 1.3 finishes the client's handshake before the daemon has judged the client's
     * certificate: a refusal arrives as a reset or alert on the first read, not as a handshake error. A daemon that
     * is fine stays quiet until we speak (or sends its own first packet, which is put back).
     */
    fun open(stepper: Stepper, transport: PairingTransport, host: String, port: Int): OpenedTls {
        val opened = mutableListOf<Closeable>()
        try {
            val plain = stepper.run(SelfAdbStep.CONNECT_SOCKET) { transport.connect(host, port) }
            opened += plain
            val tls = stepper.run(SelfAdbStep.TLS_HANDSHAKE) { transport.handshake(plain, host, port) }
            opened += tls
            stepper.log.note(stepper.operation, SelfAdbStep.TLS_HANDSHAKE, tls.describe())
            val input = PushbackInputStream(tls.input, 1)
            stepper.run(SelfAdbStep.FIRST_READ) { settle(stepper, tls, input) }
            val keying = stepper.run(SelfAdbStep.EXPORT_KEYING_MATERIAL) { tls.exportKeyingMaterial(EXPORTED_KEY_LABEL, EXPORTED_KEY_BYTES) }
            return OpenedTls(plain, tls, input, keying)
        } catch (failure: Throwable) {
            opened.asReversed().forEach { try { it.close() } catch (ignored: Throwable) { } }
            throw failure
        }
    }

    private fun settle(stepper: Stepper, tls: PairingTls, input: PushbackInputStream) {
        val previous = tls.readTimeoutMillis
        tls.readTimeoutMillis = SETTLE_MILLIS
        try {
            val first = input.read()
            if (first < 0) throw EOFException("the daemon closed the connection right after the handshake")
            input.unread(first)
            stepper.log.note(stepper.operation, SelfAdbStep.FIRST_READ, "the daemon sent data first")
        } catch (quiet: SocketTimeoutException) {
            stepper.log.note(stepper.operation, SelfAdbStep.FIRST_READ, "quiet for $SETTLE_MILLIS ms: the daemon is waiting for us")
        } finally {
            tls.readTimeoutMillis = previous
        }
    }
}

/**
 * The wireless-debugging pairing protocol as a client (AOSP `pairing_connection/pairing_connection.cpp`), written here
 * from the public protocol so each step can be named when it fails:
 *
 * 1. plain TCP to the pairing port, then TLS 1.3 with a client certificate (see [PairingTlsOpener] for the steps);
 * 2. keying material exported from the TLS session is appended to the code to make the SPAKE2 password;
 * 3. one SPAKE2 message each way, which gives an AES-128-GCM key;
 * 4. one encrypted PeerInfo each way: ours carries the adb public key, which adbd then keeps.
 *
 * Packets are a 6-byte header (version 1, type, 4-byte big-endian length) and the payload.
 */
internal class AdbPairingClient(
    private val variants: List<PairingVariant>,
    private val spake2: (ByteArray) -> PairingSpake2 = ::NativeSpake2,
    private val log: StepLog = NoStepLog,
) {
    /**
     * Never throws: every failure comes back as [PairingOutcome.Broke] with the step and the exception class.
     *
     * The first variant is the real attempt. A later one runs only when the one before it broke before anything made
     * from the code was sent (connect, handshake or first read), so a code is never spent on a retry. If all of them
     * break there, the first variant's failure is the one reported; the log has them all.
     */
    fun pair(host: String, port: Int, code: String, adbPublicKey: ByteArray): PairingOutcome {
        var first: PairingOutcome.Broke? = null
        for ((index, variant) in variants.withIndex()) {
            val target = variant.host ?: host
            log.note(OPERATION, null, "variant ${index + 1} of ${variants.size}: ${variant.label}; target $target:$port")
            val outcome = pairWith(variant.transport, target, port, code, adbPublicKey)
            if (outcome is PairingOutcome.Broke && outcome.at.networkDenied) {
                // The operating system refuses every socket, so another way of connecting cannot work.
                log.note(OPERATION, outcome.at.step, "the operating system refused the socket (EPERM); not trying the other ${variants.size - index - 1} variants")
                return outcome
            }
            val beforeCode = PairingRetry.worthRetrying(outcome)
            if (beforeCode && index < variants.lastIndex) {
                if (first == null) first = outcome as PairingOutcome.Broke
                log.note(OPERATION, null, "variant ${index + 1} broke before the code was used; trying the next one")
                continue
            }
            if (first != null) {
                log.note(OPERATION, null, "variant ${index + 1} (${variant.label}) ended: ${describe(outcome)}; variant 1 had broken at ${first.at.step.tag}")
                if (beforeCode) { log.note(OPERATION, null, "result: ${describe(first)}"); return first }
            }
            log.note(OPERATION, null, "result: ${describe(outcome)}")
            return outcome
        }
        return first ?: PairingOutcome.Rejected
    }

    private fun describe(outcome: PairingOutcome): String = when (outcome) {
        PairingOutcome.Paired -> "paired"
        PairingOutcome.Rejected -> "refused by the daemon"
        is PairingOutcome.Broke -> "broke at ${outcome.at.step.tag} (${outcome.at.exceptionClass})"
    }

    private fun pairWith(transport: PairingTransport, host: String, port: Int, code: String, adbPublicKey: ByteArray): PairingOutcome {
        val stepper = Stepper(log, OPERATION)
        var opened: OpenedTls? = null
        var crypto: PairingCrypto? = null
        return try {
            val tls = PairingTlsOpener.open(stepper, transport, host, port).also { opened = it }
            val input = DataInputStream(tls.input)
            val output = DataOutputStream(tls.tls.output)

            val ready = stepper.run(SelfAdbStep.SPAKE2) {
                val made = PairingCrypto(spake2(code.toByteArray(Charsets.UTF_8) + tls.keyingMaterial)).also { crypto = it }
                writePacket(output, TYPE_SPAKE2, made.message)
                val theirs = readPacket(input, TYPE_SPAKE2)
                theirs != null && made.initCipher(theirs)
            }
            if (!ready) return PairingOutcome.Rejected

            val accepted = stepper.run(SelfAdbStep.PEER_INFO) { exchangePeerInfo(input, output, crypto!!, adbPublicKey) }
            if (accepted) PairingOutcome.Paired else PairingOutcome.Rejected
        } catch (stopped: StepException) {
            val failure = StepFailure.of(stopped.step, stopped.cause ?: stopped)
            log.failed(OPERATION, failure, stopped.cause)
            PairingOutcome.Broke(failure)
        } finally {
            try { crypto?.destroy() } catch (ignored: Throwable) { }
            opened?.close()
        }
    }

    private fun exchangePeerInfo(input: DataInputStream, output: DataOutputStream, crypto: PairingCrypto, adbPublicKey: ByteArray): Boolean {
        val ours = ByteArray(PEER_INFO_BYTES)
        ours[0] = PEER_INFO_RSA_PUBLIC_KEY
        adbPublicKey.copyInto(ours, 1, 0, minOf(adbPublicKey.size, PEER_INFO_BYTES - 1))
        writePacket(output, TYPE_PEER_INFO, crypto.encrypt(ours) ?: return false)
        // A wrong code makes the daemon fail our PeerInfo's tag and close the connection without an answer.
        val theirs = try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (closed: EOFException) {
            log.failed(OPERATION, StepFailure.of(SelfAdbStep.PEER_INFO, closed), closed)
            null
        } ?: return false
        return crypto.decrypt(theirs)?.size == PEER_INFO_BYTES
    }

    private fun writePacket(output: DataOutputStream, type: Byte, payload: ByteArray) {
        output.write(ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).put(VERSION).put(type).putInt(payload.size).array())
        output.write(payload)
        output.flush()
    }

    /** The payload, or null when the header is not a packet of the expected type. */
    private fun readPacket(input: DataInputStream, expected: Byte): ByteArray? {
        val header = ByteBuffer.wrap(ByteArray(HEADER_BYTES).also(input::readFully)).order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val size = header.getInt()
        if (version != VERSION || type != expected || size <= 0 || size > MAX_PAYLOAD_BYTES) return null
        return ByteArray(size).also(input::readFully)
    }

    companion object {
        const val OPERATION = "pair"
        const val EXPORTED_KEY_LABEL = PairingTlsOpener.EXPORTED_KEY_LABEL
        const val EXPORTED_KEY_BYTES = PairingTlsOpener.EXPORTED_KEY_BYTES
        private const val HEADER_BYTES = 6
        private const val VERSION: Byte = 1
        private const val TYPE_SPAKE2: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_BYTES = 8192
        private const val PEER_INFO_RSA_PUBLIC_KEY: Byte = 0
        private const val MAX_PAYLOAD_BYTES = 2 * PEER_INFO_BYTES
    }
}
