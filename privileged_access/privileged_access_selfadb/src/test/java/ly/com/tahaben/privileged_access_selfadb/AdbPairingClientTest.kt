package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_domain.SelfAdbStep
import ly.com.tahaben.privileged_access_domain.StepFailure
import org.junit.After
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLHandshakeException

/**
 * The pairing client against a fake daemon that frames packets and seals PeerInfo the way adbd does. TLS and SPAKE2
 * are replaced (TLS by the plain socket and fixed keying material, SPAKE2 by a stand-in that agrees only when both
 * passwords are equal), so these tests cover framing, step naming and failure reporting. TLS, the native SPAKE2 and
 * adbd's acceptance are checked on an emulator and a phone.
 */
class AdbPairingClientTest {
    private val keying = ByteArray(64) { (it * 3).toByte() }
    private val publicKey = "AAAA publickey Farhan\u0000".toByteArray()
    private val servers = mutableListOf<FakeDaemon>()

    @After fun stop() = servers.forEach { it.close() }

    private class FakeSpake2(password: ByteArray) : PairingSpake2 {
        override val message: ByteArray = MessageDigest.getInstance("SHA-256").digest(password)
        override fun process(theirMessage: ByteArray): ByteArray =
            if (theirMessage.contentEquals(message)) MessageDigest.getInstance("SHA-256").digest("agreed".toByteArray())
            else ByteArray(32).also(SecureRandom()::nextBytes)
        override fun destroy() = Unit
    }

    private class RecordingLog : StepLog {
        val reached = mutableListOf<SelfAdbStep>()
        val failed = mutableListOf<StepFailure>()
        override fun reached(operation: String, step: SelfAdbStep) { reached += step }
        val notes = mutableListOf<String>()
        override fun failed(operation: String, failure: StepFailure, cause: Throwable?) { failed += failure }
        override fun note(operation: String, step: SelfAdbStep?, text: String) { notes += text }
    }

    private class FakeTls(private val socket: Socket, private val keying: () -> ByteArray) : PairingTls {
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override var readTimeoutMillis: Int
            get() = socket.soTimeout
            set(value) { socket.soTimeout = value }
        override fun exportKeyingMaterial(label: String, length: Int): ByteArray {
            assertThat(label).isEqualTo("adb-label\u0000")
            assertThat(length).isEqualTo(64)
            return keying()
        }
        override fun close() = socket.close()
    }

    private fun transport(
        readTimeoutMillis: Int = 5_000,
        onHandshake: (Socket) -> Unit = {},
        onExport: () -> ByteArray = { keying },
    ) = object : PairingTransport {
        override fun connect(host: String, port: Int): Socket =
            Socket().apply { soTimeout = readTimeoutMillis; connect(java.net.InetSocketAddress(host, port), 2_000) }

        override fun handshake(plain: Socket, host: String, port: Int): PairingTls {
            onHandshake(plain)
            return FakeTls(plain, onExport)
        }
    }

    /** Behaves like adbd's pairing port for [code]: SPAKE2 message, then PeerInfo, closing when the tag fails. */
    private inner class FakeDaemon(private val code: String, private val silent: Boolean = false, private val hangUp: Boolean = false) : Closeable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        @Volatile var peerInfo: ByteArray? = null
        private val thread = Thread {
            try {
                server.accept().use { socket ->
                    if (hangUp) return@use
                    if (silent) { Thread.sleep(3_000); return@use }
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val crypto = PairingCrypto(FakeSpake2(code.toByteArray() + keying))
                    val theirs = read(input, 0)
                    write(output, 0, crypto.message)
                    crypto.initCipher(theirs)
                    val sealed = read(input, 1)
                    val plain = crypto.decrypt(sealed) ?: return@use // wrong code: close without an answer
                    peerInfo = plain
                    write(output, 1, crypto.encrypt(ByteArray(8192) { 7 })!!)
                }
            } catch (ignored: Exception) { }
        }.also { it.isDaemon = true; it.start() }

        private fun read(input: DataInputStream, type: Int): ByteArray {
            val header = ByteBuffer.wrap(ByteArray(6).also(input::readFully))
            assertThat(header.get().toInt()).isEqualTo(1)
            assertThat(header.get().toInt()).isEqualTo(type)
            return ByteArray(header.getInt()).also(input::readFully)
        }

        private fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
            output.write(ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(payload.size).array())
            output.write(payload); output.flush()
        }

        override fun close() { server.close(); thread.interrupt() }
    }

    private fun daemon(code: String = "123456", silent: Boolean = false, hangUp: Boolean = false) = FakeDaemon(code, silent, hangUp).also { servers += it }

    private fun client(transport: PairingTransport, log: StepLog = NoStepLog, spake2: (ByteArray) -> PairingSpake2 = ::FakeSpake2) =
        AdbPairingClient(listOf(PairingVariant("test", transport)), spake2, log)

    // why: the happy path end to end over real framing. catches: a wrong header, order, sealing or padding.
    @Test(timeout = 10_000) fun `the right code pairs and the daemon receives the padded public key`() {
        val daemon = daemon()
        val log = RecordingLog()
        val outcome = client(transport(), log).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Paired)
        val received = daemon.peerInfo!!
        assertThat(received.size).isEqualTo(8192)
        assertThat(received[0].toInt()).isEqualTo(0)
        assertThat(received.copyOfRange(1, 1 + publicKey.size)).isEqualTo(publicKey)
        assertThat(received.drop(1 + publicKey.size).all { it == 0.toByte() }).isTrue()
        assertThat(log.reached).containsExactly(
            SelfAdbStep.CONNECT_SOCKET, SelfAdbStep.TLS_HANDSHAKE, SelfAdbStep.FIRST_READ, SelfAdbStep.EXPORT_KEYING_MATERIAL, SelfAdbStep.SPAKE2, SelfAdbStep.PEER_INFO,
        ).inOrder()
        assertThat(log.failed).isEmpty()
    }

    // why: adbd closes without an answer on a wrong code. catches: reporting that as a crash instead of a refusal.
    @Test(timeout = 10_000) fun `a wrong code is a refusal`() {
        val daemon = daemon(code = "654321")
        assertThat(client(transport()).pair("127.0.0.1", daemon.port, "123456", publicKey)).isEqualTo(PairingOutcome.Rejected)
    }

    // why: nothing listens on the pairing port when the dialog is closed. catches: a missing connect step name.
    @Test(timeout = 10_000) fun `a closed port breaks at connect-socket`() {
        val closed = ServerSocket(0).use { it.localPort }
        val log = RecordingLog()
        val outcome = client(transport(), log).pair("127.0.0.1", closed, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.CONNECT_SOCKET, "ConnectException")))
        assertThat(log.failed).containsExactly(StepFailure(SelfAdbStep.CONNECT_SOCKET, "ConnectException"))
    }

    // why: the failure the owner's log suggests. catches: a handshake error reported as a wrong code.
    @Test(timeout = 10_000) fun `a handshake error breaks at tls-handshake with its class`() {
        val daemon = daemon()
        val outcome = client(transport(onHandshake = { throw SSLHandshakeException("peer reset 127.0.0.1:${daemon.port}") }))
            .pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.TLS_HANDSHAKE, "SSLHandshakeException")))
    }

    @Test(timeout = 10_000) fun `a key export error breaks at export-keying-material`() {
        val daemon = daemon()
        val outcome = client(transport(onExport = { throw IllegalStateException("no export") })).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.EXPORT_KEYING_MATERIAL, "IllegalStateException")))
    }

    // why: a missing native library is an Error, not an Exception. catches: a catch of Exception only.
    @Test(timeout = 10_000) fun `a missing native library is reported at spake2`() {
        val daemon = daemon()
        val outcome = client(transport(), spake2 = { throw UnsatisfiedLinkError("libspake2.so") }).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.SPAKE2, "UnsatisfiedLinkError")))
    }

    // why: a daemon that stops answering must end the attempt. catches: a read with no timeout.
    @Test(timeout = 10_000) fun `a silent daemon times out at spake2`() {
        val daemon = daemon(silent = true)
        val outcome = client(transport(readTimeoutMillis = 300)).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.SPAKE2, "SocketTimeoutException")))
    }

    // why: exception messages carry addresses and ports. catches: a message or port reaching the report.
    @Test(timeout = 10_000) fun `a failure carries the class name and no message`() {
        val daemon = daemon()
        val log = RecordingLog()
        val outcome = client(transport(onHandshake = { throw SSLHandshakeException("secret-detail ${daemon.port}") }), log)
            .pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome.toString()).doesNotContain("secret-detail")
        assertThat(outcome.toString()).doesNotContain(daemon.port.toString())
        assertThat(log.failed.toString()).doesNotContain("secret-detail")
    }

    @Test fun `the class name is the simple name, with no package and no outer class`() {
        assertThat(StepFailure.of(SelfAdbStep.CONNECT, java.net.SocketTimeoutException("x")).exceptionClass).isEqualTo("SocketTimeoutException")
        assertThat(StepFailure.of(SelfAdbStep.CONNECT, object : RuntimeException() {}).exceptionClass).isNotEmpty()
    }

    // why: the typed port can be stale or the connect port. catches: retrying after the code was already used, which
    // would spend a one-time code on a second port, or never retrying a port that never answered.
    @Test fun `only a break before the code was used is worth another port`() {
        fun broke(step: SelfAdbStep) = PairingOutcome.Broke(StepFailure(step, "X"))
        assertThat(PairingRetry.worthRetrying(broke(SelfAdbStep.CONNECT_SOCKET))).isTrue()
        assertThat(PairingRetry.worthRetrying(broke(SelfAdbStep.TLS_HANDSHAKE))).isTrue()
        assertThat(PairingRetry.worthRetrying(broke(SelfAdbStep.FIRST_READ))).isTrue()
        listOf(SelfAdbStep.EXPORT_KEYING_MATERIAL, SelfAdbStep.SPAKE2, SelfAdbStep.PEER_INFO, SelfAdbStep.KEY_SETUP)
            .forEach { assertThat(PairingRetry.worthRetrying(broke(it))).isFalse() }
        assertThat(PairingRetry.worthRetrying(PairingOutcome.Rejected)).isFalse()
        assertThat(PairingRetry.worthRetrying(PairingOutcome.Paired)).isFalse()
    }

    // why: TLS 1.3 shows a refused client certificate on the first read, not in the handshake. catches: that reset being
    // filed under connect-socket or the code exchange, which is how the owner's Pixel report read.
    @Test(timeout = 10_000) fun `a daemon that hangs up after the handshake breaks at first-read`() {
        val daemon = daemon(hangUp = true)
        val outcome = client(transport()).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.FIRST_READ, "EOFException")))
    }

    // why: a refused connect must not be blamed on a later step, and an option call must not run after the connect.
    @Test(timeout = 10_000) fun `each step carries only its own call`() {
        val seen = mutableListOf<String>()
        val daemon = daemon()
        val recording = object : PairingTransport {
            override fun connect(host: String, port: Int): Socket { seen += "connect $host"; return transport().connect(host, port) }
            override fun handshake(plain: Socket, host: String, port: Int): PairingTls { seen += "handshake"; return FakeTls(plain) { keying } }
        }
        client(recording).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(seen).containsExactly("connect 127.0.0.1", "handshake").inOrder()
    }

    private fun failing(step: String, count: MutableList<String>, exception: () -> Exception) = object : PairingTransport {
        override fun connect(host: String, port: Int): Socket {
            count += "connect $host"
            if (step == "connect") throw exception()
            return transport().connect(host, port)
        }
        override fun handshake(plain: Socket, host: String, port: Int): PairingTls {
            count += "handshake"
            if (step == "handshake") throw exception()
            return FakeTls(plain) { keying }
        }
    }

    // why: the comparison the owner asked for. catches: variants that never run after a failure, or that use the same host.
    @Test(timeout = 10_000) fun `a failed connect is retried with the next variant and its own host`() {
        val daemon = daemon()
        val calls = mutableListOf<String>()
        val log = RecordingLog()
        val variants = listOf(
            PairingVariant("loopback", failing("connect", calls) { java.net.SocketException("Connection reset") }),
            PairingVariant("wifi", failing("none", calls) { IllegalStateException() }, host = "127.0.0.1"),
        )
        val outcome = AdbPairingClient(variants, ::FakeSpake2, log).pair("203.0.113.9", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Paired)
        assertThat(calls).containsExactly("connect 203.0.113.9", "connect 127.0.0.1", "handshake").inOrder()
        assertThat(log.notes.any { it.contains("variant 1 of 2: loopback") }).isTrue()
        assertThat(log.notes.any { it.contains("variant 2 of 2: wifi") }).isTrue()
    }

    @Test(timeout = 10_000) fun `when every variant breaks before the code the first failure is reported`() {
        val daemon = daemon()
        val calls = mutableListOf<String>()
        val variants = listOf(
            PairingVariant("a", failing("handshake", calls) { javax.net.ssl.SSLHandshakeException("a") }),
            PairingVariant("b", failing("handshake", calls) { java.net.SocketException("b") }),
        )
        val outcome = AdbPairingClient(variants, ::FakeSpake2, NoStepLog).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(outcome).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.TLS_HANDSHAKE, "SSLHandshakeException")))
    }

    // why: never spend a code twice. catches: a retry after the SPAKE2 message or the PeerInfo has been sent.
    @Test(timeout = 10_000) fun `no variant runs after the code was used`() {
        val daemon = daemon(code = "654321")
        val calls = mutableListOf<String>()
        val variants = listOf(
            PairingVariant("a", failing("none", calls) { IllegalStateException() }),
            PairingVariant("b", failing("none", calls) { IllegalStateException() }),
        )
        assertThat(AdbPairingClient(variants, ::FakeSpake2, NoStepLog).pair("127.0.0.1", daemon.port, "123456", publicKey))
            .isEqualTo(PairingOutcome.Rejected)
        assertThat(calls.count { it == "handshake" }).isEqualTo(1)
        val thrown = AdbPairingClient(variants, { throw UnsatisfiedLinkError() }, NoStepLog).pair("127.0.0.1", daemon.port, "123456", publicKey)
        assertThat(thrown).isEqualTo(PairingOutcome.Broke(StepFailure(SelfAdbStep.SPAKE2, "UnsatisfiedLinkError")))
        assertThat(calls.count { it == "handshake" }).isEqualTo(2)
    }

    // why: v5 showed the second attempt's SocketException and hid the first. catches: the typed port's failure being lost.
    @Test fun `when the retry also breaks before the code, the typed port's failure is the one reported`() {
        val first = PairingOutcome.Broke(StepFailure(SelfAdbStep.TLS_HANDSHAKE, "SSLHandshakeException"))
        val second = PairingOutcome.Broke(StepFailure(SelfAdbStep.CONNECT_SOCKET, "SocketException"))
        assertThat(PairingRetry.report(first, second)).isEqualTo(first)
        assertThat(PairingRetry.report(first, PairingOutcome.Paired)).isEqualTo(PairingOutcome.Paired)
        assertThat(PairingRetry.report(first, PairingOutcome.Rejected)).isEqualTo(PairingOutcome.Rejected)
        val later = PairingOutcome.Broke(StepFailure(SelfAdbStep.SPAKE2, "EOFException"))
        assertThat(PairingRetry.report(first, later)).isEqualTo(later)
    }

    // why: when the operating system refuses every socket, trying four more ways only repeats the refusal and the wait.
    @Test(timeout = 10_000) fun `an EPERM stops the variants at once and is reported as a denial`() {
        val calls = mutableListOf<String>()
        val log = RecordingLog()
        val denied = { java.net.SocketException("socket failed: EPERM (Operation not permitted)") }
        val variants = List(5) { PairingVariant("v$it", failing("connect", calls, denied)) }
        val outcome = AdbPairingClient(variants, ::FakeSpake2, log).pair("127.0.0.1", 4000, "123456", publicKey)
        outcome as PairingOutcome.Broke
        assertThat(outcome.at.networkDenied).isTrue()
        assertThat(outcome.at.detail).contains("EPERM")
        assertThat(calls).hasSize(1)
        assertThat(log.notes.any { it.contains("not trying the other 4 variants") }).isTrue()
        assertThat(PairingRetry.worthRetrying(outcome)).isFalse()
    }
}
