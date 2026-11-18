package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingValue.Bool
import ly.com.tahaben.settings_portability.SettingValue.Float32
import ly.com.tahaben.settings_portability.SettingValue.Float64
import ly.com.tahaben.settings_portability.SettingValue.Int32
import ly.com.tahaben.settings_portability.SettingValue.Int64
import ly.com.tahaben.settings_portability.SettingValue.Text
import ly.com.tahaben.settings_portability.SettingValue.TextSet
import org.junit.Test

/**
 * The file format against literal text. Encoder and decoder are mated parts, so each is checked against the same
 * hand-written document rather than only against each other (technical-excellence: parity tests).
 * The keys here are arbitrary on purpose: the codec checks structure, the catalog checks meaning.
 */
class PortableSettingsCodecTest {

    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0)

    /** The canonical text version 1 produces. Compact, fixed member order, sorted keys. Reviewed by hand. */
    private val canonicalFile =
        """{"format":"farhan-settings","version":1,""" +
            """"source":{"packageName":"ly.com.tahaben.farhan_alpha","versionName":"0.2.6-alpha","versionCode":11,"userId":0},""" +
            """"sharedPreferences":{"booleans":{"a_flag":true},"ints":{"count":-7},""" +
            """"longs":{"beyond_double":9007199254740993,"big":9223372036854775807,"low":-9223372036854775808},""" +
            """"floats":{"ratio":1.5},"doubles":{"precise":0.1},"strings":{"quote":"say \"hi\" é"},""" +
            """"stringSets":{"set":["a","b","c"]}},""" +
            """"dataStore":{"booleans":{"app_main_switch":false},"ints":{"key_delayed_launch_duration":5},"longs":{},""" +
            """"floats":{},"doubles":{},"strings":{"app_theme_colors":"Classic"},""" +
            """"stringSets":{"key_delayed_launch_messages":["one","two"]}},""" +
            """"configurationRows":{"apps":[""" +
            """{"appName":"Alpha","packageName":"com.example.a","activityName":"com.example.a.Main","userSerial":0},""" +
            """{"appName":"Beta","packageName":"com.example.b","activityName":"com.example.b.Main","userSerial":10}],""" +
            """"timeLimits":[{"packageName":"com.example.a","timeLimitInMilli":3600000,"timeAtAddingInMilli":1700000000000}]},""" +
            """"unsupportedKeys":[{"store":"dataStore","key":"alpha"},{"store":"sharedPreferences","key":"zeta"}]}"""

    private val alpha = AppActivityRow("Alpha", "com.example.a", "com.example.a.Main", 0L)
    private val beta = AppActivityRow("Beta", "com.example.b", "com.example.b.Main", 10L)
    private val timeLimit = TimeLimitRow("com.example.a", 3_600_000L, 1_700_000_000_000L)

    private val sharedValues = listOf(
        "a_flag" to Bool(true),
        "count" to Int32(-7),
        "big" to Int64(Long.MAX_VALUE),
        "low" to Int64(Long.MIN_VALUE),
        "beyond_double" to Int64(9007199254740993L), // 2^53 + 1: a double would store 9007199254740992
        "ratio" to Float32(1.5f),
        "precise" to Float64(0.1),
        "quote" to Text("say \"hi\" é"),
        "set" to TextSet(setOf("c", "a", "b")),
    )
    private val dataStoreValues = listOf(
        "key_delayed_launch_messages" to TextSet(setOf("two", "one")),
        "app_theme_colors" to Text("Classic"),
        "key_delayed_launch_duration" to Int32(5),
        "app_main_switch" to Bool(false),
    )

    /** Built in scrambled order so only the encoder can produce the canonical order. */
    private val scrambled = PortableSettings(
        source,
        SettingsConfiguration(
            sharedPreferences = linkedMapOf<String, SettingValue>().apply { sharedValues.reversed().forEach { put(it.first, it.second) } },
            dataStore = linkedMapOf<String, SettingValue>().apply { dataStoreValues.forEach { put(it.first, it.second) } },
            rows = ConfigurationRows(apps = listOf(beta, alpha), timeLimits = listOf(timeLimit)),
        ),
        unsupportedKeys = listOf(UnsupportedKey(SettingsStoreId.SHARED_PREFERENCES, "zeta"), UnsupportedKey(SettingsStoreId.DATA_STORE, "alpha")),
    )

    private val decodedCanonical = PortableSettings(
        source,
        SettingsConfiguration(
            sharedPreferences = sharedValues.toMap(),
            dataStore = dataStoreValues.toMap(),
            rows = ConfigurationRows(apps = listOf(alpha, beta), timeLimits = listOf(timeLimit)),
        ),
        unsupportedKeys = listOf(UnsupportedKey(SettingsStoreId.DATA_STORE, "alpha"), UnsupportedKey(SettingsStoreId.SHARED_PREFERENCES, "zeta")),
    )

    private fun DecodeResult.valid(): PortableSettings {
        assertThat(this).isInstanceOf(DecodeResult.Valid::class.java)
        return (this as DecodeResult.Valid).settings
    }

    private fun DecodeResult.errorCode(): PortableSettingsErrorCode {
        assertThat(this).isInstanceOf(DecodeResult.Invalid::class.java)
        return (this as DecodeResult.Invalid).error.code
    }

    // why: backups must be byte-stable and exact. Longs beyond 2^53, the extremes, string-set order and escapes
    //      are where a JSON library or a hash-ordered set silently changes data.
    // proves: CLAIM(lossless-long), CLAIM(deterministic-file)
    // catches: writing longs as doubles (...992); Long.MIN_VALUE overflow; set or map order following insertion
    //          or hash order; rows left unsorted; non-UTF-8 output; "1.5" written as "1.5E0".
    @Test
    fun `the encoder writes the canonical file exactly`() {
        assertThat(String(PortableSettingsCodec.encode(scrambled), Charsets.UTF_8)).isEqualTo(canonicalFile)
    }

    // why: the decoder must read the same literal back to exact typed values, and must not depend on whitespace
    //      (a person may pretty-print or edit the file). Checked against text, not against the encoder.
    // proves: CLAIM(lossless-long)
    // catches: parsing numbers through double; confusing the int and long groups; losing the unsupported list.
    @Test
    fun `the decoder reads the literal file and a reformatted copy to the same typed values`() {
        assertThat(PortableSettingsCodec.decode(canonicalFile.toByteArray(Charsets.UTF_8)).valid()).isEqualTo(decodedCanonical)

        val reformatted = canonicalFile.replace(",", ",\n  ").replace("{", "{\n    ").replace(":", " : ")
        assertThat(reformatted).isNotEqualTo(canonicalFile) // the probe changed the text
        assertThat(PortableSettingsCodec.decode(reformatted.toByteArray(Charsets.UTF_8)).valid()).isEqualTo(decodedCanonical)
    }

    // why: a file that is damaged, from a newer format, ambiguous or oversized must yield a named error and no
    //      partial value. A key present twice would otherwise be resolved silently by whichever parser runs.
    // proves: CLAIM(strict-input)
    // catches: last-wins duplicate keys (platform JSONObject behavior); accepting version 2; "5" accepted as an int;
    //          1.5 truncated to an int; a long above Long.MAX_VALUE wrapped; trailing data ignored; limits checked
    //          after allocation or off by one. The accepted-at-limit controls show each limit is a boundary.
    @Test
    fun `defective files are rejected with the specific error`() {
        fun shared(groups: String) = Json.document(shared = groups)
        val cases = listOf(
            Triple("truncated", canonicalFile.dropLast(30), PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("not json", "hello", PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("trailing data", canonicalFile + "{}", PortableSettingsErrorCode.MALFORMED_JSON),
            Triple("other format", Json.document(format = "something-else"), PortableSettingsErrorCode.WRONG_FORMAT),
            Triple("version 2", Json.document(version = "2"), PortableSettingsErrorCode.UNSUPPORTED_VERSION),
            Triple("version 0", Json.document(version = "0"), PortableSettingsErrorCode.UNSUPPORTED_VERSION),
            Triple("duplicate member", shared(Json.groups(booleans = """"a":true,"a":false""")), PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple(
                "same key in two type groups",
                shared(Json.groups(ints = """"a":1""", longs = """"a":1""")),
                PortableSettingsErrorCode.DUPLICATE_KEY,
            ),
            Triple("duplicate set element", shared(Json.groups(stringSets = """"a":["x","x"]""")), PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple("string in ints", shared(Json.groups(ints = """"a":"5"""")), PortableSettingsErrorCode.TYPE_MISMATCH),
            Triple("fraction in ints", shared(Json.groups(ints = """"a":1.5""")), PortableSettingsErrorCode.TYPE_MISMATCH),
            Triple("exponent in longs", shared(Json.groups(longs = """"a":1e3""")), PortableSettingsErrorCode.TYPE_MISMATCH),
            Triple("string in booleans", shared(Json.groups(booleans = """"a":"true"""")), PortableSettingsErrorCode.TYPE_MISMATCH),
            Triple("int above Int.MAX_VALUE", shared(Json.groups(ints = """"a":2147483648""")), PortableSettingsErrorCode.OUT_OF_RANGE),
            Triple("long above Long.MAX_VALUE", shared(Json.groups(longs = """"a":9223372036854775808""")), PortableSettingsErrorCode.OUT_OF_RANGE),
        )
        assertThat(cases.map { it.first }).containsAtLeast(
            "truncated", "trailing data", "version 2", "duplicate member", "same key in two type groups", "long above Long.MAX_VALUE",
        )
        for ((name, text, code) in cases) {
            assertWithMessage(name).that(PortableSettingsCodec.decode(text.toByteArray(Charsets.UTF_8)).errorCode()).isEqualTo(code)
        }

        // limits: each accepted exactly at the limit and rejected one past it.
        val threeInts = shared(Json.groups(ints = """"a":1,"b":2,"c":3"""))
        val bytes = threeInts.toByteArray(Charsets.UTF_8)
        assertThat(PortableSettingsCodec.decode(bytes, DecodeLimits(maxEntries = 3)).valid().configuration.sharedPreferences).hasSize(3)
        assertThat(PortableSettingsCodec.decode(bytes, DecodeLimits(maxEntries = 2)).errorCode()).isEqualTo(PortableSettingsErrorCode.TOO_MANY_ENTRIES)
        assertThat(PortableSettingsCodec.decode(bytes, DecodeLimits(maxBytes = bytes.size)).valid()).isNotNull()
        assertThat(PortableSettingsCodec.decode(bytes, DecodeLimits(maxBytes = bytes.size - 1)).errorCode()).isEqualTo(PortableSettingsErrorCode.TOO_LARGE)

        val nestedSet = shared(Json.groups(stringSets = """"a":[["x"]]"""))
        assertThat(PortableSettingsCodec.decode(nestedSet.toByteArray(), DecodeLimits(maxDepth = 4)).errorCode()).isEqualTo(PortableSettingsErrorCode.TOO_DEEP)
        // All JSON keys and strings share the bound. Mandatory envelope tokens are shorter than 256.
        val longString = shared(Json.groups(strings = """"a":"${"x".repeat(256)}""""))
        assertThat(PortableSettingsCodec.decode(longString.toByteArray(), DecodeLimits(maxStringLength = 255)).errorCode()).isEqualTo(PortableSettingsErrorCode.TOO_LARGE)
        assertThat(PortableSettingsCodec.decode(longString.toByteArray(), DecodeLimits(maxStringLength = 256)).valid()).isNotNull()
    }
}
