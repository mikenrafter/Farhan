package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Format defects the main codec suite does not list: row primary keys, envelope shape, byte encoding. Same
 * literal-document approach: each defect is a hand-written text, and a control shows the body is otherwise valid.
 */
class PortableSettingsCodecStrictnessTest {

    private val appRow = """{"appName":"Chat","packageName":"com.example.chat","activityName":"com.example.chat.Main","userSerial":0}"""
    private val limitRow = """{"packageName":"com.example.chat","timeLimitInMilli":1,"timeAtAddingInMilli":2}"""

    private fun code(bytes: ByteArray): PortableSettingsErrorCode {
        val result = PortableSettingsCodec.decode(bytes)
        assertThat(result).isInstanceOf(DecodeResult.Invalid::class.java)
        return (result as DecodeResult.Invalid).error.code
    }

    // why: a repeated primary key would pass staging and then fail the SQL insert at apply time, forcing a rollback
    //      for a file that was never valid. It must be refused at decode time, before any journal is written.
    // proves: CLAIM(strict-input)
    // catches: accepting duplicate rows; keying duplicates on one column instead of the table's primary key.
    @Test
    fun `a repeated row primary key is rejected and a different second column is not a repeat`() {
        val control = Json.document(apps = "$appRow,${appRow.replace("Chat", "Chat2")}", timeLimits = limitRow)
        assertThat(PortableSettingsCodec.decode(control.toByteArray())).isInstanceOf(DecodeResult.Valid::class.java)

        val duplicateApp = Json.document(apps = "$appRow,${appRow.replace("Main", "Other")}")
        assertThat(code(duplicateApp.toByteArray())).isEqualTo(PortableSettingsErrorCode.DUPLICATE_KEY)
        val duplicateLimit = Json.document(timeLimits = "$limitRow,${limitRow.replace(":1,", ":9,")}")
        assertThat(code(duplicateLimit.toByteArray())).isEqualTo(PortableSettingsErrorCode.DUPLICATE_KEY)
    }

    // why: only the encoder's member order is fixed. A person or a tool (jq -S sorts keys, an editor reflows) may
    //      reorder members at every level, and the file is still the same valid document with the same values.
    //      Every member below is out of canonical order, including format and version last.
    // proves: CLAIM(strict-input)
    // catches: a decoder that requires the encoder's order (format first, groups in order, row fields in order);
    //          losing a value while reordering; dropping the format/version check when they come last.
    @Test
    fun `members in any order decode to the same values`() {
        val reordered = """{"unsupportedKeys":[{"key":"alpha","store":"dataStore"}],""" +
            """"configurationRows":{"timeLimits":[{"timeAtAddingInMilli":2,"timeLimitInMilli":1,"packageName":"com.example.b"}],""" +
            """"apps":[{"userSerial":10,"activityName":"com.example.a.Main","packageName":"com.example.a","appName":"Alpha"}]},""" +
            """"dataStore":{"stringSets":{},"strings":{"app_theme_colors":"Classic"},"doubles":{},"floats":{},"longs":{},"ints":{},""" +
            """"booleans":{"app_main_switch":false}},""" +
            """"sharedPreferences":{"stringSets":{"set":["b","a"]},"strings":{},"doubles":{},"floats":{},""" +
            """"longs":{"big":9223372036854775807},"ints":{"count":-7},"booleans":{"a_flag":true}},""" +
            """"source":{"userId":0,"versionCode":11,"versionName":"0.2.6-alpha","packageName":"ly.com.tahaben.farhan_alpha"},""" +
            """"version":1,"format":"farhan-settings"}"""

        val expected = PortableSettings(
            PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0),
            SettingsConfiguration(
                sharedPreferences = mapOf(
                    "a_flag" to SettingValue.Bool(true),
                    "count" to SettingValue.Int32(-7),
                    "big" to SettingValue.Int64(Long.MAX_VALUE),
                    "set" to SettingValue.TextSet(setOf("a", "b")),
                ),
                dataStore = mapOf("app_main_switch" to SettingValue.Bool(false), "app_theme_colors" to SettingValue.Text("Classic")),
                rows = ConfigurationRows(
                    apps = listOf(AppActivityRow("Alpha", "com.example.a", "com.example.a.Main", 10L)),
                    timeLimits = listOf(TimeLimitRow("com.example.b", 1L, 2L)),
                ),
            ),
            unsupportedKeys = listOf(UnsupportedKey(SettingsStoreId.DATA_STORE, "alpha")),
        )
        assertThat(PortableSettingsCodec.decode(reordered.toByteArray())).isEqualTo(DecodeResult.Valid(expected))

        // the format and version checks still fire when those members come last
        assertThat(code(reordered.replace(""""version":1""", """"version":2""").toByteArray())).isEqualTo(PortableSettingsErrorCode.UNSUPPORTED_VERSION)
        assertThat(code(reordered.replace("farhan-settings", "other").toByteArray())).isEqualTo(PortableSettingsErrorCode.WRONG_FORMAT)
    }

    // why: a file that is not exactly this format must not be half-read: a missing or extra member, a repeated
    //      envelope member, a non-object root and a bad row member each yield a named error.
    // proves: CLAIM(strict-input)
    // catches: defaulting a missing member to empty; ignoring unknown members; last-wins on a repeated "source".
    @Test
    fun `missing extra and repeated envelope members are rejected`() {
        val valid = Json.document()
        assertThat(PortableSettingsCodec.decode(valid.toByteArray())).isInstanceOf(DecodeResult.Valid::class.java)

        val cases = listOf(
            Triple("array root", "[]", PortableSettingsErrorCode.WRONG_FORMAT),
            Triple("empty object", "{}", PortableSettingsErrorCode.WRONG_FORMAT),
            Triple("missing format", valid.replace(""""format":"farhan-settings",""", ""), PortableSettingsErrorCode.WRONG_FORMAT),
            Triple("missing version", valid.replace(""""version":1,""", ""), PortableSettingsErrorCode.UNSUPPORTED_VERSION),
            Triple("repeated version", valid.dropLast(1) + ""","version":1}""", PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple("missing dataStore", valid.replace(""""dataStore":${Json.NO_GROUPS},""", ""), PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("extra member", valid.dropLast(1) + ""","extra":1}""", PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("repeated source", valid.dropLast(1) + ""","source":${Json.SOURCE}}""", PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple("missing group", valid.replaceFirst(""","stringSets":{}""", ""), PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("unknown row member", Json.document(timeLimits = limitRow.dropLast(1) + ""","x":1}"""), PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("unknown store name", Json.document(unsupported = """{"store":"cloud","key":"a"}"""), PortableSettingsErrorCode.TYPE_MISMATCH),
        )
        for ((name, text, expected) in cases) {
            assertWithMessage(name).that(code(text.toByteArray())).isEqualTo(expected)
        }
    }

    // why: the format is UTF-8. A byte-order mark, UTF-16 text, or a stray byte must be refused, not decoded by
    //      auto-detection or with replacement characters that would silently change a key or a value.
    // proves: CLAIM(strict-input)
    // catches: handing raw bytes to the parser (encoding auto-detect); a lenient decoder substituting U+FFFD.
    @Test
    fun `only strict UTF-8 is accepted`() {
        val text = Json.document()
        assertThat(PortableSettingsCodec.decode(text.toByteArray(Charsets.UTF_8))).isInstanceOf(DecodeResult.Valid::class.java)
        assertThat(code(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
            .isEqualTo(PortableSettingsErrorCode.MALFORMED_JSON)
        assertThat(code(text.toByteArray(Charsets.UTF_16LE))).isEqualTo(PortableSettingsErrorCode.MALFORMED_JSON)
        val withControlCharacter = Json.document(shared = Json.groups(strings = """"a":"@"""")).replace("@", "\u0001")
        assertThat(code(withControlCharacter.toByteArray())).isEqualTo(PortableSettingsErrorCode.MALFORMED_JSON) // raw control character
        val invalidUtf8 = Json.document(shared = Json.groups(strings = """"a":"@@"""")).toByteArray()
        invalidUtf8[invalidUtf8.indexOf('@'.code.toByte())] = 0xC3.toByte() // a lead byte followed by '@', not a continuation
        assertThat(code(invalidUtf8)).isEqualTo(PortableSettingsErrorCode.MALFORMED_JSON)
    }

    // why: JSON cannot carry NaN or an infinite float; writing one would produce text no decoder accepts. The
    //      encoder refuses instead of writing an unreadable backup.
    // proves: CLAIM(strict-input)
    // catches: an encoder that writes the string "NaN" or a bare token.
    @Test
    fun `encoding a non-finite float throws instead of writing an unreadable file`() {
        val settings = PortableSettings(
            PortableSource("ly.com.tahaben.farhan_alpha", "1", 1L, 0),
            SettingsConfiguration(sharedPreferences = mapOf("k" to SettingValue.Float32(Float.NaN))),
        )
        assertThrows(IllegalArgumentException::class.java) { PortableSettingsCodec.encode(settings) }
    }
}
