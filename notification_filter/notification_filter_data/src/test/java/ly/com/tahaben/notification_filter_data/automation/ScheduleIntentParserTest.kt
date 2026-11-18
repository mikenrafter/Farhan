package ly.com.tahaben.notification_filter_data.automation

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.notification_filter_data.automation.ScheduleIntentParser.Result
import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import ly.com.tahaben.notification_filter_domain.schedule.IntentWindow
import org.junit.Test

/** The documented payload (notification_filter/AUTOMATION.md) against the real org.json codec. */
class ScheduleIntentParserTest {
    private val window = """{"start":0,"end":1440,"budget":4}"""
    private fun payload(version: String = "1", extra: String = "", windows: String = "[$window]") =
        """{"version":$version$extra,"windows":$windows}"""

    // why: the documented example must parse to exactly the typed fields the domain validates.
    // catches: field renames between the doc and the parser; mode/date dropped.
    @Test
    fun `a dated payload parses to typed fields`() {
        val result = ScheduleIntentParser.parse(payload(extra = ""","mode":"dated","date":"2026-11-20""""))

        assertThat(result).isEqualTo(Result.Parsed(1, "dated", "2026-11-20", listOf(IntentWindow(0, 1440, 4))))
    }

    // why: CLAIM(strict-integers). org.json's getInt would turn 5.0, "5" and true into 5/1; the contract refuses them.
    // catches: lenient coercion. Each bad field must surface as a null field (MALFORMED_WINDOW or UNSUPPORTED_VERSION in the domain).
    @Test
    fun `non-integer numbers, strings and booleans never become integers`() {
        for (bad in listOf("5.0", "\"5\"", "true", "null", "1e3", "2147483648")) {
            val windows = """[{"start":$bad,"end":1440,"budget":4}]"""
            val parsed = ScheduleIntentParser.parse(payload(windows = windows)) as Result.Parsed
            assertThat(parsed.windows.single().startMinute).isNull()
        }
        for (bad in listOf("1.0", "\"1\"", "true")) {
            assertThat((ScheduleIntentParser.parse(payload(version = bad)) as Result.Parsed).version).isNull()
        }
    }

    // why: CLAIM(payload-bounded). Oversize is decided from the length, before parsing. Proof: the oversized text is
    //      not JSON at all, yet the answer is TOO_LARGE, not MALFORMED.
    // catches: parse-then-measure (an oversized payload costing a full parse).
    @Test
    fun `an oversized payload is refused before it is parsed`() {
        val huge = "{" + "x".repeat(ScheduleIntentParser.MAX_PAYLOAD_CHARS)

        assertThat(ScheduleIntentParser.parse(huge)).isEqualTo(Result.Failed(AutomationError.PAYLOAD_TOO_LARGE))
    }

    // why: at most 24 windows. The 25th is refused without building a list for it.
    @Test
    fun `more than 24 windows is too large`() {
        val windows = (1..25).joinToString(",", "[", "]") { window }

        assertThat(ScheduleIntentParser.parse(payload(windows = windows))).isEqualTo(Result.Failed(AutomationError.PAYLOAD_TOO_LARGE))
    }

    // why: a typo in a field name must not silently produce a different schedule; unknown fields are refused.
    // catches: ignoring unknown keys at either level, non-object windows, mode/date of the wrong type, not-JSON.
    @Test
    fun `unknown fields, wrong shapes and garbage are malformed`() {
        val malformed = listOf(
            payload(extra = ""","modee":"dated""""),
            payload(windows = """[{"start":0,"end":1440,"budget":4,"bonus":1}]"""),
            payload(windows = "[7]"),
            payload(extra = ""","mode":5"""),
            payload(extra = ""","date":["2026-11-20"]"""),
            """{"version":1}""",
            "not json",
            "",
        )
        for (raw in malformed) {
            assertThat(ScheduleIntentParser.parse(raw)).isEqualTo(Result.Failed(AutomationError.MALFORMED_PAYLOAD))
        }
    }
}
