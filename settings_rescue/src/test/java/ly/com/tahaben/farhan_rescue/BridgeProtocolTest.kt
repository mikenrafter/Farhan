package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.adb.PackageVersion
import ly.com.tahaben.farhan_rescue.bridge.*
import ly.com.tahaben.farhan_rescue.capture.*
import ly.com.tahaben.farhan_rescue.input.*
import org.junit.Test

class BridgeProtocolTest {
    private val port = TcpPort.parse("37123").orNull()!!
    private val job = JobId.parse("0123456789abcdef0123456789abcdef").orNull()!!

    // why: parity of the encoder with an independent literal (field names are the contract between two processes).
    // catches: renaming a key on one side only; the round trip alone would still pass.
    @Test fun requestWireMatchesTheLiteralFieldNames() {
        val check = BridgeRequest.Check(LoopbackHost.IPV4, port, target())
        assertThat(BridgeWire.encode(check, 5L)).containsExactly(
            "kind", "check", "host", "127.0.0.1", "port", "37123", "package", "ly.com.tahaben.farhan_alpha", "user", "0", "op", 5L,
        )
        val export = BridgeRequest.Export(LoopbackHost.IPV6, port, target(10), job)
        assertThat(BridgeWire.encode(export, 6L)).containsExactly(
            "kind", "export", "host", "::1", "port", "37123", "package", "ly.com.tahaben.farhan_alpha", "user", "10",
            "job", "0123456789abcdef0123456789abcdef", "op", 6L,
        )
        assertThat(BridgeWire.operationOf(BridgeWire.encode(check, 5L))).isEqualTo(5L)
    }

    @Test fun requestsRoundTrip() {
        val pair = BridgeRequest.Pair(LoopbackHost.IPV4, port, PairingCode.parse("654321").orNull()!!)
        val requests = listOf(pair, BridgeRequest.Check(LoopbackHost.IPV6, port, target(3)), BridgeRequest.Export(LoopbackHost.IPV4, port, target(), job))
        for (request in requests) assertThat(BridgeWire.decodeRequest(BridgeWire.encode(request, 1L))).isEqualTo(request)
        assertThat((BridgeWire.decodeRequest(BridgeWire.encode(pair, 1L)) as BridgeRequest.Pair).code.reveal()).isEqualTo("654321")
    }

    // why: CLAIM(bridge-revalidates): the bridge decodes with the same parsers, so a caller that skips the form still
    // cannot dial a non-loopback host or use a bad package/port/job. catches: trusting the wire; any missing field passing.
    @Test fun bridgeRejectsEveryBadRequest() {
        val good = BridgeWire.encode(BridgeRequest.Export(LoopbackHost.IPV4, port, target(), job), 1L)
        assertThat(BridgeWire.decodeRequest(good)).isNotNull()
        val mutations = listOf(
            good + ("host" to "192.168.0.7"), good + ("host" to "localhost"), good + ("port" to "0"), good + ("port" to "70000"),
            good + ("package" to "bad"), good + ("package" to "ly.com.tahaben.farhan_rescue"), good + ("user" to "-1"),
            good + ("job" to "../../x"), good + ("kind" to "shell"), good + ("kind" to null), good + ("port" to 37123),
            good - "host", good - "package", good - "job", good - "user", emptyMap<String, Any?>(),
        )
        for (wire in mutations) assertThat(BridgeWire.decodeRequest(wire)).isNull()
    }

    @Test fun eventsRoundTrip() {
        val facts = CaptureFacts(10234, PackageVersion("0.2.6-alpha", 11), listOf("shared_prefs/shared_pref.xml", "databases/farhan_db"), true, Integrity.SIZE_ONLY)
        val events = listOf(
            BridgeEvent.Hello(4242), BridgeEvent.Paired, BridgeEvent.Failed(FailureCode.PAIR_FAILED),
            BridgeEvent.ProgressUpdate(Progress(Stage.COPYING, 2, 5)), BridgeEvent.Captured(facts),
            BridgeEvent.Captured(facts.copy(version = null)),
            BridgeEvent.Checked(CheckFacts(10234, null, 5, true, false)),
            BridgeEvent.Checked(CheckFacts(1_010_234, PackageVersion("1.0", 2), 0, false, true)),
        )
        for (event in events) assertThat(BridgeWire.decodeEvent(BridgeWire.encode(event, 9L))).isEqualTo(event)
    }

    // why: a malformed event must be dropped, never guessed at. catches: lenient decoding into a partial result.
    @Test fun malformedEventsDecodeToNull() {
        val good = BridgeWire.encode(BridgeEvent.Captured(CaptureFacts(1, null, listOf("a"), false, Integrity.SHA256)))
        assertThat(BridgeWire.decodeEvent(good)).isNotNull()
        val bad = listOf(
            emptyMap<String, Any?>(), good + ("kind" to "mystery"), good + ("integrity" to "NONE"), good + ("paths" to "not a list"),
            good + ("paths" to List(BridgeWire.MAX_LOCAL_PATHS + 1) { "p" }), good + ("uid" to "1"),
            BridgeWire.encode(BridgeEvent.Hello(1)) + ("pid" to 0), BridgeWire.encode(BridgeEvent.Failed(FailureCode.BUSY)) + ("code" to "NOPE"),
        )
        for (wire in bad) assertThat(BridgeWire.decodeEvent(wire)).isNull()
    }

    // why: CLAIM(small-binder): the largest event stays far under Binder's 1 MiB. Worst case 300 paths of at most
    // 60 characters = 18,000 characters. catches: sending file contents through Binder.
    @Test fun largestEventIsSmall() {
        val longest = "datastore/appWidget-1234567890.preferences_pb"
        val facts = CaptureFacts(1, null, List(BridgeWire.MAX_LOCAL_PATHS) { longest }, false, Integrity.SHA256)
        val wire = BridgeWire.encode(BridgeEvent.Captured(facts))
        val characters = (wire["paths"] as List<*>).sumOf { (it as String).length }
        assertThat(characters).isLessThan(64 * 1024)
    }
}
