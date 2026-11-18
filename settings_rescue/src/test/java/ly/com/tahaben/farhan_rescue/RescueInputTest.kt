package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.bridge.BridgeRequest
import ly.com.tahaben.farhan_rescue.input.*
import org.junit.Test

class RescueInputTest {
    // why: only well-formed application ids may reach a shell line (CLAIM(package-shape)).
    // catches: a looser regex, `$` matching before a trailing newline, a missing length cap, a missing helper check.
    @Test fun packageNamesAreShapeChecked() {
        assertThat(PackageName.parse("ly.com.tahaben.farhan_alpha").orNull()?.value).isEqualTo("ly.com.tahaben.farhan_alpha")
        val bad = listOf("", "single", "a..b", "a.", ".a.b", "1a.b", "a.b-c", "a.b c", "a.b\n", "a.b;reboot", "a.b`id`", "a.$(id).b", "ly.com.tahaben.farhan_alpha ", "a." + "b".repeat(254))
        for (text in bad) assertThat(PackageName.parse(text)).isEqualTo(Checked.Rejected(InputError.PACKAGE_EMPTY_OR_MALFORMED))
        assertThat(PackageName.parse("ly.com.tahaben.farhan_rescue")).isEqualTo(Checked.Rejected(InputError.PACKAGE_IS_HELPER))
        assertThat(PackageName.parse("a." + "b".repeat(253)).orNull()).isNotNull() // 255 characters exactly
    }

    // why: user is a nonnegative int (default 0); anything else is refused before it reaches `--user`.
    // catches: accepting "-1", whitespace, decimals, or ten digits that overflow Int.
    @Test fun userIdsAreNonNegativeIntegers() {
        assertThat(AndroidUserId.parse("0").orNull()?.value).isEqualTo(0)
        assertThat(AndroidUserId.parse("10").orNull()?.value).isEqualTo(10)
        assertThat(AndroidUserId.parse("999999999").orNull()?.value).isEqualTo(999_999_999)
        for (text in listOf("", "-1", " 1", "1 ", "1.5", "+1", "1234567890", "a")) {
            assertThat(AndroidUserId.parse(text)).isEqualTo(Checked.Rejected(InputError.USER_MALFORMED))
        }
    }

    // why: ports are 1..65535 digits only. catches: 0, 65536, signs, trailing junk.
    @Test fun portsAreInRange() {
        assertThat(TcpPort.parse("1").orNull()?.value).isEqualTo(1)
        assertThat(TcpPort.parse("65535").orNull()?.value).isEqualTo(65535)
        for (text in listOf("", "0", "65536", "99999", "-5", "80a", "123456", " 80")) {
            assertThat(TcpPort.parse(text)).isEqualTo(Checked.Rejected(InputError.PORT_MALFORMED))
        }
    }

    // why: the code must never show in logs (CLAIM(code-redacted)): toString of the code and of a request holding it.
    // catches: a data class exposing the digits, or a code of the wrong length being accepted.
    @Test fun pairingCodeIsSixDigitsAndRedacted() {
        val code = PairingCode.parse("123456").orNull()!!
        assertThat(code.toString()).doesNotContain("123456")
        val request = BridgeRequest.Pair(LoopbackHost.IPV4, TcpPort.parse("40123").orNull()!!, code)
        assertThat(request.toString()).doesNotContain("123456")
        for (text in listOf("", "12345", "1234567", "12345a", "12 456", "١٢٣٤٥٦")) {
            assertThat(PairingCode.parse(text)).isEqualTo(Checked.Rejected(InputError.PAIRING_CODE_MALFORMED))
        }
    }

    // why: CLAIM(loopback-only). catches: accepting the wifi address, localhost, 0.0.0.0, ::, another 127.x address.
    @Test fun onlyTheTwoLoopbackAddressesAreAccepted() {
        assertThat(LoopbackHost.parse("127.0.0.1").orNull()).isEqualTo(LoopbackHost.IPV4)
        assertThat(LoopbackHost.parse("::1").orNull()).isEqualTo(LoopbackHost.IPV6)
        for (text in listOf("", "localhost", "127.0.0.2", "192.168.1.5", "0.0.0.0", "::", "::2", "127.0.0.1 ", "[::1]")) {
            assertThat(LoopbackHost.parse(text)).isEqualTo(Checked.Rejected(InputError.HOST_NOT_LOOPBACK))
        }
    }

    // why: a job id becomes a directory name, so it must not carry a path. catches: accepting "../x" or uppercase hex.
    @Test fun jobIdsAreLowercaseHex() {
        assertThat(JobId.random().value).matches("[0-9a-f]{32}")
        assertThat(JobId.random()).isNotEqualTo(JobId.random())
        for (text in listOf("", "../../etc", "ABCDEF0123456789ABCDEF0123456789", "0123456789abcdef", "0".repeat(33))) {
            assertThat(JobId.parse(text)).isEqualTo(Checked.Rejected(InputError.JOB_ID_MALFORMED))
        }
    }
}
