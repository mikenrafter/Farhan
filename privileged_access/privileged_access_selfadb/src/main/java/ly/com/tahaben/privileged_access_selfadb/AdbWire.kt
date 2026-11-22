// Adapted from the settings rescue helper's adb/AdbWire.kt (settings_rescue is an application module with internal classes,
// so it cannot be called from here). Keep the two in step until both move to one shared library.
package ly.com.tahaben.privileged_access_selfadb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * A small ADB client for exactly one job: run `exec:` services on a wireless-debugging daemon, one at a time.
 *
 * Why this exists instead of libadb's connection: libadb 3.1.1 never reports end of stream when the daemon closes a
 * stream while its output is still unread (`AdbStream.notifyClose` sets `mPendingClose`, and `read` only turns that
 * into end of stream before it waits, not after the queue drains). A fast command such as `run-as <pkg> id -u`
 * answers and closes in one burst, so the reader consumed the answer, called `read` again and blocked forever
 * (observed on an Android 15 emulator with a debugger attached: `mPendingClose=true, mIsClosed=false`, connection
 * established, TLS up). This client reads frames on the caller's thread, so there is no hand-off to lose, and a read
 * has a socket timeout. libadb is still used for pairing, which works.
 *
 * The wire sequence is the one the platform `adb` and Reverb's `AdbStlsUpgrade` use, checked against a real adbd:
 * 1. plain socket, send `CNXN`; a wireless-debugging port answers `STLS` (any other answer is not that port);
 * 2. send `STLS`, run a TLS 1.3 handshake in which the client presents the paired key's certificate;
 * 3. adbd then sends its own `CNXN` through TLS. This client never sends a second `CNXN`: that would reset the
 *    daemon's transport (adbd `handle_new_connection` goes offline and asks for TLS again);
 * 4. `OPEN` a service, answer every `WRTE` with `OKAY`, stop at `CLSE`.
 *
 * Protocol reference: AOSP `packages/modules/adb/protocol.txt`, `daemon/adb_wifi.cpp` (`adbd_wifi_secure_connect`
 * calls `handle_online` and `send_connect`).
 */
internal class AdbWireSession private constructor(
    private val socket: Socket,
    private var input: InputStream,
    private var output: OutputStream,
    private val maxPayload: Int,
    val banner: String,
) : Closeable {
    private var nextLocalId = 1
    private var open: ExecStream? = null
    private var broken = false

    /**
     * Opens a service. Only one stream can be open: a second call while the first is unfinished throws, because the
     * frames of the two could not be told apart by a reader that has no other thread.
     */
    fun open(destination: String): ExecStream {
        check(open == null) { "a stream is already open" }
        if (broken) throw IOException("the ADB connection is no longer usable")
        val localId = nextLocalId++
        try {
            write(Frame.OPEN, localId, 0, destination.toByteArray(Charsets.UTF_8) + 0)
            while (true) {
                val frame = read()
                when {
                    frame.command == Frame.OKAY && frame.arg1 == localId -> return ExecStream(localId, frame.arg0).also { open = it }
                    frame.command == Frame.CLSE && frame.arg1 == localId -> throw AdbWireException.ServiceRejected()
                    else -> Unit // a stale frame of an earlier stream
                }
            }
        } catch (rejected: AdbWireException.ServiceRejected) {
            throw rejected // the daemon is fine; only this service was refused
        } catch (failure: IOException) {
            broken = true
            throw failure
        }
    }

    override fun close() {
        broken = true
        try { socket.close() } catch (ignored: IOException) { }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY) {
        Frame.write(output, command, arg0, arg1, payload)
    }

    private fun read(): Frame = Frame.read(input, maxPayload)

    inner class ExecStream internal constructor(private val localId: Int, private val remoteId: Int) : InputStream() {
        private var pending: ByteArray = EMPTY
        private var position = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val count = read(one, 0, 1)
                if (count < 0) return -1
                if (count == 1) return one[0].toInt() and 0xff
            }
        }

        /** Returns the next bytes, or -1 once the daemon has closed the stream and everything it sent was read. */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= pending.size) {
                if (finished) return -1
                try {
                    val frame = this@AdbWireSession.read()
                    when {
                        frame.arg1 != localId -> Unit
                        frame.command == Frame.WRTE -> {
                            // Acknowledge first: adbd sends its next block only after this.
                            this@AdbWireSession.write(Frame.OKAY, localId, remoteId)
                            pending = frame.payload; position = 0
                        }
                        frame.command == Frame.CLSE -> { finished = true; open = null }
                        else -> Unit
                    }
                } catch (failure: IOException) {
                    broken = true; finished = true; open = null
                    throw failure
                }
            }
            val count = minOf(length, pending.size - position)
            System.arraycopy(pending, position, buffer, offset, count)
            position += count
            return count
        }

        /**
         * Ends the stream. If the daemon has not closed it, ask it to and discard what is still in flight, up to
         * [DRAIN_LIMIT_BYTES]; a stream that will not stop makes the whole connection unusable, so it is closed.
         */
        override fun close() {
            if (finished) return
            try {
                this@AdbWireSession.write(Frame.CLSE, localId, remoteId)
                var drained = 0L
                while (true) {
                    val frame = this@AdbWireSession.read()
                    if (frame.arg1 != localId) continue
                    if (frame.command == Frame.CLSE) break
                    if (frame.command == Frame.WRTE) {
                        drained += frame.payload.size
                        if (drained > DRAIN_LIMIT_BYTES) throw IOException("the daemon kept sending after the stream was closed")
                        this@AdbWireSession.write(Frame.OKAY, localId, remoteId)
                    }
                }
            } catch (failure: IOException) {
                broken = true
            } finally {
                finished = true; open = null
            }
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val DRAIN_LIMIT_BYTES = 1L shl 20
        /** Skip-checksum protocol version, the one every Android 9+ daemon speaks. */
        const val PROTOCOL_VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1 shl 20

        /**
         * Connects, upgrades to TLS and waits for the daemon's own `CNXN`.
         *
         * @throws AdbWireException.NotWirelessDebugging the port did not ask for TLS (not a wireless-debugging port)
         * @throws AdbWireException.PairingRequired the handshake or the daemon's first packet failed, which is how adbd
         *   rejects a key it has not paired (a timeout is rethrown as is)
         */
        fun connect(host: String, port: Int, tls: TlsUpgrade, connectTimeoutMillis: Int = 10_000, readTimeoutMillis: Int = 30_000): AdbWireSession {
            val socket = Socket()
            var done = false
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                socket.soTimeout = readTimeoutMillis
                val plainIn = socket.getInputStream()
                val plainOut = socket.getOutputStream()

                Frame.write(plainOut, Frame.CNXN, PROTOCOL_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.US_ASCII))
                val first = Frame.read(plainIn, MAX_PAYLOAD)
                if (first.command != Frame.STLS) throw AdbWireException.NotWirelessDebugging()
                Frame.write(plainOut, Frame.STLS, STLS_VERSION, 0)

                // adbd answers a key it has not paired with a TLS alert, a close or a reset, and TLS 1.3 may only show
                // it on the first read after the handshake. Every such failure here means "pair again"; only a
                // timeout is something else.
                val secure: SSLSocket
                val hello: Frame
                try {
                    secure = tls.upgrade(socket, host, port).also { it.startHandshake() }
                    hello = Frame.read(secure.inputStream, MAX_PAYLOAD)
                } catch (timeout: SocketTimeoutException) {
                    throw timeout
                } catch (failure: IOException) {
                    throw AdbWireException.PairingRequired(failure)
                }
                val secureIn = secure.inputStream
                val secureOut = secure.outputStream
                if (hello.command != Frame.CNXN) throw IOException("expected CNXN after TLS, got 0x${hello.command.toString(16)}")
                val session = AdbWireSession(secure, secureIn, secureOut, minOf(hello.arg1.coerceAtLeast(4096), MAX_PAYLOAD), String(hello.payload, Charsets.UTF_8))
                done = true
                return session
            } finally {
                if (!done) try { socket.close() } catch (ignored: IOException) { }
            }
        }
    }
}

/** Upgrades the plain socket to TLS. Production uses the bundled Conscrypt; tests use the JDK provider. */
internal fun interface TlsUpgrade {
    fun upgrade(plain: Socket, host: String, port: Int): SSLSocket
}

internal sealed class AdbWireException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class NotWirelessDebugging : AdbWireException("the port did not ask for TLS, so it is not a wireless-debugging port")
    class PairingRequired(cause: Throwable) : AdbWireException("the daemon refused the TLS handshake; this key is not paired", cause)
    class ServiceRejected : AdbWireException("the daemon refused to open the service")
}

/** One ADB packet: 24-byte little-endian header and a payload. */
internal class Frame(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
    companion object {
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
        private const val AUTH = 0x48545541
        private const val SYNC = 0x434e5953
        private const val HEADER = 24
        private val KNOWN = setOf(CNXN, STLS, OPEN, OKAY, WRTE, CLSE, AUTH, SYNC)

        fun write(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            var checksum = 0
            for (byte in payload) checksum += byte.toInt() and 0xff
            val packet = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum).putInt(command.inv())
                .put(payload)
            output.write(packet.array())
            output.flush()
        }

        /** Throws [IOException] on end of stream, a bad magic, an unknown command or a payload over [maxPayload]. */
        fun read(input: InputStream, maxPayload: Int): Frame {
            val header = ByteBuffer.wrap(readFully(input, HEADER)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.getInt()
            val arg0 = header.getInt()
            val arg1 = header.getInt()
            val length = header.getInt()
            header.getInt() // checksum: not verified, the skip-checksum version does not send a valid one
            val magic = header.getInt()
            if (command != magic.inv() || command !in KNOWN) throw IOException("corrupt ADB header")
            if (length < 0 || length > maxPayload) throw IOException("ADB payload length out of range")
            return Frame(command, arg0, arg1, if (length == 0) ByteArray(0) else readFully(input, length))
        }

        private fun readFully(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var done = 0
            while (done < count) {
                val n = input.read(bytes, done, count - done)
                if (n < 0) throw IOException("the ADB connection closed mid-packet")
                done += n
            }
            return bytes
        }
    }
}
