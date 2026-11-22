package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_selfadb.AdbWireException
import ly.com.tahaben.privileged_access_selfadb.AdbWireSession
import ly.com.tahaben.privileged_access_selfadb.Frame
import ly.com.tahaben.privileged_access_selfadb.IdentityFactory
import ly.com.tahaben.privileged_access_selfadb.SingleKeyManager
import ly.com.tahaben.privileged_access_selfadb.TlsUpgrade
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The wire client against a fake adbd that speaks the real sequence (plain CNXN, STLS, TLS 1.3 with a client
 * certificate, the daemon's own CNXN, then streams). The fake is JDK-only so it runs on the build machine; the same
 * sequence was checked against a real Android 15 adbd on an emulator [research/settings-rescue-device-report.md].
 */
class AdbWireTest {
    private val servers = mutableListOf<FakeAdbd>()

    @After fun stopServers() = servers.forEach { it.close() }

    private fun server(trusted: Boolean = true, plain: Boolean = false, handler: (Conn) -> Unit): FakeAdbd =
        FakeAdbd(trusted, plain, handler).also { servers += it; it.start() }

    private fun connect(server: FakeAdbd) =
        AdbWireSession.connect("127.0.0.1", server.port, ClientIdentity.PAIRED.tls, connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)

    private fun InputStream.text() = String(readBytes(), Charsets.UTF_8)

    // why: the regression behind this class. catches: libadb's lost end of stream, where the answer and the close
    // arrive together and the next read blocks forever. The 10 s limit turns a hang into a failure.
    @Test(timeout = 10_000) fun outputThatArrivesWithTheCloseIsReadAndThenEnds() {
        val server = server { conn ->
            val open = conn.read()
            conn.write(Frame.OKAY, 77, open.arg0)
            conn.write(Frame.WRTE, 77, open.arg0, "10148\n".toByteArray())
            conn.write(Frame.CLSE, 77, open.arg0)
            conn.drain()
        }
        connect(server).use { session ->
            val stream = session.open("exec:run-as pkg --user 0 id -u")
            assertThat(stream.text()).isEqualTo("10148\n")
            assertThat(stream.read(ByteArray(8), 0, 8)).isEqualTo(-1)
        }
    }

    // why: adbd sends the next block only after the previous one is answered. catches: a client that reads without
    // answering stalls a large copy after the first block; a wrong remote id in the answer is ignored by adbd.
    @Test(timeout = 10_000) fun everyBlockIsAnsweredWithTheDaemonsStreamId() {
        val blocks = List(5) { index -> ByteArray(40_000) { (index + 1).toByte() } }
        val answers = mutableListOf<Pair<Int, Int>>()
        val server = server { conn ->
            val open = conn.read()
            conn.write(Frame.OKAY, 91, open.arg0)
            for (block in blocks) {
                conn.write(Frame.WRTE, 91, open.arg0, block)
                val answer = conn.read()
                assertThat(answer.command).isEqualTo(Frame.OKAY)
                answers += answer.arg0 to answer.arg1
            }
            conn.write(Frame.CLSE, 91, open.arg0)
            conn.drain()
        }
        connect(server).use { session ->
            assertThat(session.open("exec:cat file").readBytes()).isEqualTo(blocks.reduce { a, b -> a + b })
        }
        assertThat(answers).containsExactlyElementsIn(List(5) { 1 to 91 })
    }

    // catches: state from one command leaking into the next, so a second command reads the first one's end.
    @Test(timeout = 10_000) fun severalCommandsShareOneConnection() {
        val server = server { conn ->
            repeat(3) { n ->
                val open = conn.readCommand()
                conn.write(Frame.OKAY, 10 + n, open.arg0)
                conn.write(Frame.WRTE, 10 + n, open.arg0, "answer $n".toByteArray())
                conn.write(Frame.CLSE, 10 + n, open.arg0)
            }
            conn.drain()
        }
        connect(server).use { session ->
            assertThat((0..2).map { session.open("exec:c$it").text() }).containsExactly("answer 0", "answer 1", "answer 2").inOrder()
        }
    }

    // why: ReadVersion stops reading early. catches: an early stop that leaves frames of the old stream in front of
    // the next command, or one that kills the connection.
    @Test(timeout = 10_000) fun stoppingEarlyClosesTheStreamAndKeepsTheConnection() {
        val server = server { conn ->
            val first = conn.read()
            conn.write(Frame.OKAY, 5, first.arg0)
            conn.write(Frame.WRTE, 5, first.arg0, "first line\nsecond line\n".toByteArray())
            val close = conn.readCommand()
            assertThat(close.command).isEqualTo(Frame.CLSE)
            conn.write(Frame.CLSE, 5, first.arg0)
            val second = conn.readCommand()
            conn.write(Frame.OKAY, 6, second.arg0)
            conn.write(Frame.WRTE, 6, second.arg0, "second".toByteArray())
            conn.write(Frame.CLSE, 6, second.arg0)
            conn.drain()
        }
        connect(server).use { session ->
            val early = session.open("exec:long")
            assertThat(early.read(ByteArray(5), 0, 5)).isEqualTo(5)
            early.close()
            assertThat(session.open("exec:next").text()).isEqualTo("second")
        }
    }

    // catches: a refused service marking the whole connection broken, or being mistaken for an empty success.
    @Test(timeout = 10_000) fun aRefusedServiceThrowsAndLaterCommandsStillWork() {
        val server = server { conn ->
            val refused = conn.read()
            conn.write(Frame.CLSE, 0, refused.arg0)
            val fine = conn.readCommand()
            conn.write(Frame.OKAY, 3, fine.arg0)
            conn.write(Frame.CLSE, 3, fine.arg0)
            conn.drain()
        }
        connect(server).use { session ->
            assertThrows(AdbWireException.ServiceRejected::class.java) { session.open("exec:nope") }
            assertThat(session.open("exec:ok").text()).isEmpty()
        }
    }

    // catches: treating the classic adb port (5555, no TLS, AUTH challenge) as if it were wireless debugging.
    @Test(timeout = 10_000) fun aPortThatDoesNotAskForTlsIsNotWirelessDebugging() {
        val server = server(plain = true) { }
        assertThrows(AdbWireException.NotWirelessDebugging::class.java) { connect(server) }
    }

    // why: adbd rejects a key it has not paired at the TLS handshake. catches: that surfacing as a generic connect
    // failure, so the user is never told to pair again.
    @Test(timeout = 10_000) fun anUnpairedKeyReportsPairingRequired() {
        val server = server(trusted = false) { conn -> conn.drain() }
        assertThrows(AdbWireException.PairingRequired::class.java) {
            connect(server).use { it.open("exec:id").text() }
        }
    }

    // catches: following a corrupt stream (bad magic) into garbage commands instead of stopping.
    @Test(timeout = 10_000) fun aCorruptHeaderStopsTheConnection() {
        val server = server { conn ->
            val open = conn.read()
            conn.write(Frame.OKAY, 8, open.arg0)
            conn.raw(ByteArray(24) { 0x41 })
            conn.drain()
        }
        connect(server).use { session ->
            val stream = session.open("exec:x")
            assertThrows(IOException::class.java) { stream.text() }
            assertThrows(IOException::class.java) { session.open("exec:y") }
        }
    }

    // catches: a daemon that vanishes mid-answer looking like a finished command (the digest check downstream
    // would catch the short file, but the right signal is an error, not an end).
    @Test(timeout = 10_000) fun aConnectionDroppedMidAnswerIsAnErrorNotAnEnd() {
        val server = server { conn ->
            val open = conn.read()
            conn.write(Frame.OKAY, 8, open.arg0)
            conn.write(Frame.WRTE, 8, open.arg0, "part".toByteArray())
            conn.hangUp()
        }
        connect(server).use { session ->
            assertThrows(IOException::class.java) { session.open("exec:x").text() }
        }
    }

    /** A client identity: the paired one the fake daemon trusts, and one it has never seen. */
    private class ClientIdentity(val tls: TlsUpgrade, val certificate: X509Certificate) {
        companion object {
            val PAIRED = make()

            private fun make(): ClientIdentity {
                val identity = IdentityFactory.generate(System.currentTimeMillis())
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(identity.privateKeyPkcs8))
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(identity.certificateDer)) as X509Certificate
                val context = SSLContext.getInstance("TLSv1.3").apply {
                    init(arrayOf(SingleKeyManager(key, certificate)), arrayOf<TrustManager>(AcceptAll), SecureRandom())
                }
                return ClientIdentity({ plain, host, port -> context.socketFactory.createSocket(plain, host, port, true) as SSLSocket }, certificate)
            }
        }
    }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** What the fake daemon's script sees after the handshake: frames in and out over TLS. */
    private class Conn(private val input: InputStream, private val output: OutputStream, private val socket: Socket) {
        fun read(): Frame = Frame.read(input, 1 shl 20)
        /** The next frame that is not an `OKAY` answer to one of this daemon's blocks. */
        fun readCommand(): Frame { while (true) read().let { if (it.command != Frame.OKAY) return it } }
        fun write(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) = Frame.write(output, command, arg0, arg1, payload)
        fun raw(bytes: ByteArray) { output.write(bytes); output.flush() }
        fun hangUp() = socket.close()
        /** Keeps the connection open until the client goes away. */
        fun drain() { try { while (input.read() >= 0) Unit } catch (ignored: IOException) { } }
    }

    /**
     * Fake adbd. [trusted] false makes it refuse any client certificate (an unpaired key); [plain] true answers the
     * first CNXN with a plain CNXN, as the classic port does.
     */
    private class FakeAdbd(private val trusted: Boolean, private val plain: Boolean, private val handler: (Conn) -> Unit) {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        private val thread = Thread({ serve() }, "fake-adbd").apply { isDaemon = true }

        fun start() = thread.start()
        fun close() { try { listener.close() } catch (ignored: IOException) { } }

        private fun serve() {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 8_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    Frame.read(input, 1 shl 20) // the client's CNXN
                    if (plain) {
                        Frame.write(output, Frame.CNXN, 0x01000001, 4096, "device::\u0000".toByteArray())
                        return
                    }
                    Frame.write(output, Frame.STLS, 0x01000000, 0)
                    Frame.read(input, 1 shl 20) // the client's STLS
                    val key = serverKey()
                    val context = SSLContext.getInstance("TLSv1.3").apply {
                        init(arrayOf(SingleKeyManager(key.first, key.second)), arrayOf<TrustManager>(if (trusted) AcceptAll else RejectAll), SecureRandom())
                    }
                    val tls = context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
                    tls.useClientMode = false
                    tls.needClientAuth = true
                    tls.startHandshake()
                    Frame.write(tls.outputStream, Frame.CNXN, 0x01000001, 1 shl 20, "device::ro.product.name=fake\u0000".toByteArray())
                    handler(Conn(tls.inputStream, tls.outputStream, tls))
                }
            } catch (ignored: Exception) {
                // The client may leave early; its assertions report the failure.
            }
        }

        private fun serverKey(): Pair<java.security.PrivateKey, X509Certificate> {
            val identity = IdentityFactory.generate(System.currentTimeMillis())
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(identity.privateKeyPkcs8)) to
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(identity.certificateDer)) as X509Certificate
        }
    }

    private object RejectAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("unpaired key")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
