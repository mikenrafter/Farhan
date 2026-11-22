package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.TimeZone

class ConnectionLogTest {
    private val zone = TimeZone.getTimeZone("America/Denver")
    private var now = 1_795_000_000_000L // 2026-11-18T09:46:40Z
    private fun log(capacity: Int = 200) = ConnectionLog(capacity, { now }, zone)

    // why: the owner reads these lines and pastes them. catches: a stamp without UTC or without local time.
    @Test fun `a line has UTC and local time, operation, step, outcome and detail`() {
        val log = log()
        log.add("pair", "tls-handshake", "FAIL", "boom")
        val line = log.lines().single()
        assertThat(line).startsWith("2026-")
        assertThat(line).contains("Z (local ")
        assertThat(line).contains("-07:00)")
        assertThat(line).endsWith("| pair | tls-handshake | FAIL | boom")
    }

    @Test fun `the oldest lines go first once the log is full`() {
        val log = log(capacity = 3)
        (1..5).forEach { log.add("pair", "s$it", "start") }
        assertThat(log.lines().map { it.substringAfter("| pair | ").substringBefore(" |") }).containsExactly("s3", "s4", "s5").inOrder()
        assertThat(log.size()).isEqualTo(3)
    }

    // why: the code must never be kept. catches: a detail, a note or an exception message that repeats the code.
    @Test fun `the pairing code is masked in every line while it is guarded`() {
        val log = log()
        log.guard(setOf("123456"))
        log.add("pair", "start", "info", "typed 123456 and port 4000")
        log.addFailure("pair", "spake2", IllegalStateException("bad code 123456"))
        log.guard(emptySet())
        val text = log.text()
        assertThat(text).doesNotContain("123456")
        assertThat(text).contains("typed ****** and port 4000")
        assertThat(text).contains(LogScrubber.WITHHELD)
    }

    // why: a key or the adb public key is a long base64 run. catches: one reaching the log through a message.
    @Test fun `a long base64 run is withheld`() {
        val log = log()
        log.add("pair", "peer-info", "info", "key " + "A".repeat(80) + " end")
        assertThat(log.text()).doesNotContain("AAAAAAAAAA")
        assertThat(log.text()).contains("<long text withheld>")
    }

    @Test fun `a failure prints the class, the message and every cause`() {
        val failure = java.io.IOException("outer", javax.net.ssl.SSLException("middle", java.net.SocketException("Connection reset")))
        val log = log()
        log.addFailure("pair", "tls-handshake", failure)
        assertThat(log.text()).contains("java.io.IOException: outer <- caused by javax.net.ssl.SSLException: middle <- caused by java.net.SocketException: Connection reset")
    }

    @Test fun `a cause cycle does not loop and a messageless exception shows its class`() {
        val a = RuntimeException("a"); val b = RuntimeException("b", a); a.initCause(b)
        assertThat(Trace.describe(a).split(" <- caused by ")).hasSize(2)
        assertThat(Trace.describe(java.net.SocketTimeoutException())).isEqualTo("java.net.SocketTimeoutException")
    }

    @Test fun `ports and hosts are allowed`() {
        val log = log()
        log.add("check", "tcp", "info", "TCP 192.168.1.5:41234 connected")
        assertThat(log.text()).contains("192.168.1.5:41234")
    }
}
