package ly.com.tahaben.settings_portability

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.nio.charset.CodingErrorAction

/**
 * Bounds applied while reading, before anything large is allocated. [maxEntries] counts every preference entry,
 * every set element, every row and every unsupported-key name across the document. [maxStringLength] bounds every
 * parsed JSON key and string, counted per token by the codec itself (Jackson's own constraint is only a backstop).
 */
data class DecodeLimits(
    val maxBytes: Int = 1 shl 20,
    val maxEntries: Int = 20_000,
    val maxDepth: Int = 8,
    val maxStringLength: Int = 262_144,
    /** Legacy SharedPreferences XML only. Counts characters of text that may include history that never reaches a file. */
    val maxLegacyXmlChars: Int = 4 shl 20,
)

sealed interface DecodeResult {
    data class Valid(val settings: PortableSettings) : DecodeResult
    data class Invalid(val error: PortableSettingsError) : DecodeResult
}

/**
 * Versioned UTF-8 JSON file format, version 1. Structural checks only: format name, version, types, ranges,
 * duplicates, limits. Whether a key is a known setting is checked against [SettingsCatalog] by the importer.
 *
 * Invariants:
 *  - CLAIM(lossless-long): a long is written and read as exact integer digits, never as a double.
 *  - CLAIM(deterministic-file): the same [PortableSettings] always encodes to the same bytes (compact JSON,
 *    fixed member order, keys sorted, set elements sorted, rows sorted by primary key, unsupportedKeys sorted by
 *    store name ("dataStore" before "sharedPreferences") then key).
 *  - CLAIM(strict-input): a duplicate member name anywhere, a key in two type groups, a duplicate set element or
 *    row, an unknown or missing member, an unknown version, a wrong type or range, trailing data, a byte-order mark,
 *    invalid UTF-8, or a limit breach yields Invalid and no value.
 *  - Decoding reads tokens with a streaming parser (Jackson core only). Member order is free at every level:
 *    only the encoder's order is fixed, so a reformatted or key-sorted copy (jq -S) decodes the same. Every
 *    member stays required, and `format` and `version` are checked as soon as they are read.
 *  - Error locations are `store/key` or a fixed name such as `document`; never a value.
 *
 * Shape: {"format":"farhan-settings","version":1,"source":{...},
 *   "sharedPreferences":{"booleans":{},"ints":{},"longs":{},"floats":{},"doubles":{},"strings":{},"stringSets":{}},
 *   "dataStore":{same groups},"configurationRows":{"apps":[],"timeLimits":[]},"unsupportedKeys":[]}
 * Every member is required.
 *
 * Mating parts: [encode] and [decode] are checked against one literal document in PortableSettingsCodecTest.
 */
object PortableSettingsCodec {
    private const val FORMAT = "farhan-settings"
    private const val MAX_NUMBER_CHARS = 64
    private val GROUPS = listOf("booleans", "ints", "longs", "floats", "doubles", "strings", "stringSets")

    /**
     * Throws [IllegalArgumentException] for settings JSON cannot carry (a NaN or infinite float, a lone surrogate).
     * The message never includes a value.
     */
    fun encode(settings: PortableSettings): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            JsonFactory().createGenerator(output, JsonEncoding.UTF8).use { generator -> writeDocument(generator, settings) }
        } catch (failure: IOException) {
            throw IllegalArgumentException("settings cannot be written as JSON")
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, limits: DecodeLimits = DecodeLimits()): DecodeResult {
        if (bytes.size > limits.maxBytes) return invalid(PortableSettingsErrorCode.TOO_LARGE, "document")
        return try {
            DecodeResult.Valid(Decoder(bytes, limits).document())
        } catch (failure: DecodeFailure) {
            DecodeResult.Invalid(failure.error)
        } catch (failure: StreamConstraintsException) {
            invalid(PortableSettingsErrorCode.TOO_LARGE, "document")
        } catch (failure: IOException) {
            // Jackson parse errors, end of input inside a value, and CharacterCodingException (not UTF-8).
            invalid(PortableSettingsErrorCode.MALFORMED_JSON, "document")
        }
    }

    private fun invalid(code: PortableSettingsErrorCode, location: String) =
        DecodeResult.Invalid(PortableSettingsError(code, location))

    // ---- writing ----------------------------------------------------------------------------------------

    private fun writeDocument(generator: JsonGenerator, settings: PortableSettings) {
        generator.writeStartObject()
        generator.writeStringField("format", FORMAT)
        generator.writeNumberField("version", 1)
        generator.writeObjectFieldStart("source")
        generator.writeStringField("packageName", settings.source.packageName)
        generator.writeStringField("versionName", settings.source.versionName)
        generator.writeNumberField("versionCode", settings.source.versionCode)
        generator.writeNumberField("userId", settings.source.userId)
        generator.writeEndObject()
        generator.writeFieldName("sharedPreferences")
        writeStore(generator, settings.configuration.sharedPreferences)
        generator.writeFieldName("dataStore")
        writeStore(generator, settings.configuration.dataStore)
        generator.writeObjectFieldStart("configurationRows")
        generator.writeArrayFieldStart("apps")
        for (row in settings.configuration.rows.apps.sortedWith(compareBy({ it.appName }, { it.packageName }))) {
            generator.writeStartObject()
            generator.writeStringField("appName", row.appName)
            generator.writeStringField("packageName", row.packageName)
            generator.writeStringField("activityName", row.activityName)
            generator.writeNumberField("userSerial", row.userSerial)
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeArrayFieldStart("timeLimits")
        for (row in settings.configuration.rows.timeLimits.sortedBy { it.packageName }) {
            generator.writeStartObject()
            generator.writeStringField("packageName", row.packageName)
            generator.writeNumberField("timeLimitInMilli", row.timeLimitInMilli)
            generator.writeNumberField("timeAtAddingInMilli", row.timeAtAddingInMilli)
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeEndObject()
        generator.writeArrayFieldStart("unsupportedKeys")
        for (unsupported in settings.unsupportedKeys.sortedWith(compareBy({ it.store.locationName }, { it.key }))) {
            generator.writeStartObject()
            generator.writeStringField("store", unsupported.store.locationName)
            generator.writeStringField("key", unsupported.key)
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeEndObject()
    }

    private fun writeStore(generator: JsonGenerator, values: Map<String, SettingValue>) {
        val sorted = values.entries.sortedBy { it.key }
        generator.writeStartObject()
        for (group in GROUPS) {
            generator.writeObjectFieldStart(group)
            for ((key, value) in sorted) {
                if (groupOf(value) != group) continue
                generator.writeFieldName(key)
                when (value) {
                    is SettingValue.Bool -> generator.writeBoolean(value.value)
                    is SettingValue.Int32 -> generator.writeNumber(value.value)
                    is SettingValue.Int64 -> generator.writeNumber(value.value)
                    is SettingValue.Float32 -> {
                        require(value.value.isFinite()) { "float is not finite" }
                        generator.writeNumber(value.value)
                    }
                    is SettingValue.Float64 -> {
                        require(value.value.isFinite()) { "double is not finite" }
                        generator.writeNumber(value.value)
                    }
                    is SettingValue.Text -> generator.writeString(value.value)
                    is SettingValue.TextSet -> {
                        generator.writeStartArray()
                        value.values.sorted().forEach { generator.writeString(it) }
                        generator.writeEndArray()
                    }
                }
            }
            generator.writeEndObject()
        }
        generator.writeEndObject()
    }

    private fun groupOf(value: SettingValue): String = when (value) {
        is SettingValue.Bool -> "booleans"
        is SettingValue.Int32 -> "ints"
        is SettingValue.Int64 -> "longs"
        is SettingValue.Float32 -> "floats"
        is SettingValue.Float64 -> "doubles"
        is SettingValue.Text -> "strings"
        is SettingValue.TextSet -> "stringSets"
    }

    // ---- reading ----------------------------------------------------------------------------------------

    /** Carries the error out of the token loop. No message: nothing here may echo input. */
    private class DecodeFailure(val error: PortableSettingsError) : RuntimeException(error.code.name, null, false, false)

    private fun fail(code: PortableSettingsErrorCode, location: String): Nothing =
        throw DecodeFailure(PortableSettingsError(code, location))

    private class Decoder(bytes: ByteArray, private val limits: DecodeLimits) {
        private val parser: JsonParser
        private var depth = 0
        private var entries = 0

        init {
            val factory = JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxStringLength(limits.maxStringLength.coerceAtLeast(1))
                        .maxNameLength(limits.maxStringLength.coerceAtLeast(1))
                        .maxNestingDepth(limits.maxDepth.coerceAtLeast(0) + 1)
                        .maxNumberLength(MAX_NUMBER_CHARS)
                        .build(),
                )
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build()
            // A strict UTF-8 reader: a parser given raw bytes would auto-detect UTF-16/32 and replace bad sequences.
            val utf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            parser = factory.createParser(InputStreamReader(ByteArrayInputStream(bytes), utf8))
        }

        // ---- token plumbing

        private fun next(): JsonToken {
            val token = parser.nextToken() ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "document")
            when (token) {
                JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                    depth++
                    if (depth > limits.maxDepth) fail(PortableSettingsErrorCode.TOO_DEEP, "document")
                }
                JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                else -> Unit
            }
            return token
        }

        /** The text of the current key or string token, length-checked before the string is materialized. */
        private fun text(): String {
            if (parser.textLength > limits.maxStringLength) fail(PortableSettingsErrorCode.TOO_LARGE, "document")
            return parser.text
        }

        private fun countEntry() {
            entries++
            if (entries > limits.maxEntries) fail(PortableSettingsErrorCode.TOO_MANY_ENTRIES, "document")
        }

        /** Reads members of an object whose START_OBJECT is already consumed. [onMember] must consume the value. */
        private inline fun members(onMember: (String) -> Unit) {
            while (true) {
                when (next()) {
                    JsonToken.END_OBJECT -> return
                    JsonToken.FIELD_NAME -> onMember(text())
                    else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, "document")
                }
            }
        }

        private fun expectObject(location: String) {
            if (next() != JsonToken.START_OBJECT) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
        }

        private fun expectArray(location: String) {
            if (next() != JsonToken.START_ARRAY) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
        }

        private fun stringValue(location: String): String {
            if (next() != JsonToken.VALUE_STRING) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
            return text()
        }

        private fun integerValue(location: String, min: BigInteger, max: BigInteger): BigInteger {
            if (next() != JsonToken.VALUE_NUMBER_INT) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
            val number = parser.bigIntegerValue
            if (number < min || number > max) fail(PortableSettingsErrorCode.OUT_OF_RANGE, location)
            return number
        }

        private fun longValue(location: String): Long = integerValue(location, LONG_MIN, LONG_MAX).toLong()

        private fun intValue(location: String): Int = integerValue(location, INT_MIN, INT_MAX).toInt()

        // ---- document

        fun document(): PortableSettings {
            if (next() != JsonToken.START_OBJECT) fail(PortableSettingsErrorCode.WRONG_FORMAT, "document")
            var formatSeen = false
            var versionSeen = false
            var source: PortableSource? = null
            var shared: Map<String, SettingValue>? = null
            var dataStore: Map<String, SettingValue>? = null
            var rows: ConfigurationRows? = null
            var unsupported: List<UnsupportedKey>? = null
            members { member ->
                when (member) {
                    // format and version are checked the moment they are read, so a foreign or newer file whose
                    // first members are these two (the canonical order) is named before its body is read.
                    "format" -> {
                        if (formatSeen) duplicateMember()
                        formatSeen = true
                        if (next() != JsonToken.VALUE_STRING || text() != FORMAT) fail(PortableSettingsErrorCode.WRONG_FORMAT, "document")
                    }
                    "version" -> {
                        if (versionSeen) duplicateMember()
                        versionSeen = true
                        if (next() != JsonToken.VALUE_NUMBER_INT || parser.text != "1") fail(PortableSettingsErrorCode.UNSUPPORTED_VERSION, "document")
                    }
                    "source" -> { if (source != null) duplicateMember(); source = readSource() }
                    "sharedPreferences" -> { if (shared != null) duplicateMember(); shared = readStore(SettingsStoreId.SHARED_PREFERENCES) }
                    "dataStore" -> { if (dataStore != null) duplicateMember(); dataStore = readStore(SettingsStoreId.DATA_STORE) }
                    "configurationRows" -> { if (rows != null) duplicateMember(); rows = readRows() }
                    "unsupportedKeys" -> { if (unsupported != null) duplicateMember(); unsupported = readUnsupportedKeys() }
                    else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, "document")
                }
            }
            if (!formatSeen) fail(PortableSettingsErrorCode.WRONG_FORMAT, "document")
            if (!versionSeen) fail(PortableSettingsErrorCode.UNSUPPORTED_VERSION, "document")
            if (parser.nextToken() != null) fail(PortableSettingsErrorCode.MALFORMED_JSON, "document")
            return PortableSettings(
                source = source ?: missing(),
                configuration = SettingsConfiguration(shared ?: missing(), dataStore ?: missing(), rows ?: missing()),
                unsupportedKeys = unsupported ?: missing(),
            )
        }

        private fun duplicateMember(): Nothing = fail(PortableSettingsErrorCode.DUPLICATE_KEY, "document")

        private fun missing(): Nothing = fail(PortableSettingsErrorCode.MALFORMED_JSON, "document")

        private fun readSource(): PortableSource {
            expectObject("source")
            var packageName: String? = null
            var versionName: String? = null
            var versionCode: Long? = null
            var userId: Int? = null
            members { member ->
                val location = "source/$member"
                when (member) {
                    "packageName" -> { if (packageName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); packageName = stringValue(location) }
                    "versionName" -> { if (versionName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); versionName = stringValue(location) }
                    "versionCode" -> { if (versionCode != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); versionCode = longValue(location) }
                    "userId" -> { if (userId != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); userId = intValue(location) }
                    else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, "source")
                }
            }
            return PortableSource(
                packageName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "source"),
                versionName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "source"),
                versionCode ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "source"),
                userId ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "source"),
            )
        }

        private fun readStore(store: SettingsStoreId): Map<String, SettingValue> {
            val storeName = store.locationName
            expectObject(storeName)
            val values = LinkedHashMap<String, SettingValue>()
            val groupsSeen = HashSet<String>()
            members { group ->
                if (group !in GROUPS) fail(PortableSettingsErrorCode.MALFORMED_JSON, storeName)
                if (!groupsSeen.add(group)) fail(PortableSettingsErrorCode.DUPLICATE_KEY, storeName)
                expectObject(storeName)
                members { key ->
                    countEntry()
                    val location = "$storeName/$key"
                    val value = value(group, location)
                    if (values.put(key, value) != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                }
            }
            if (groupsSeen.size != GROUPS.size) fail(PortableSettingsErrorCode.MALFORMED_JSON, storeName)
            return values
        }

        private fun value(group: String, location: String): SettingValue {
            val token = next()
            return when (group) {
                "booleans" -> when (token) {
                    JsonToken.VALUE_TRUE -> SettingValue.Bool(true)
                    JsonToken.VALUE_FALSE -> SettingValue.Bool(false)
                    else -> fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                }
                "ints" -> SettingValue.Int32(wholeNumber(token, location, INT_MIN, INT_MAX).toInt())
                "longs" -> SettingValue.Int64(wholeNumber(token, location, LONG_MIN, LONG_MAX).toLong())
                "floats" -> {
                    if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                    val number = parser.text.toFloat()
                    if (!number.isFinite()) fail(PortableSettingsErrorCode.OUT_OF_RANGE, location)
                    SettingValue.Float32(number)
                }
                "doubles" -> {
                    if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                    val number = parser.text.toDouble()
                    if (!number.isFinite()) fail(PortableSettingsErrorCode.OUT_OF_RANGE, location)
                    SettingValue.Float64(number)
                }
                "strings" -> {
                    if (token != JsonToken.VALUE_STRING) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                    SettingValue.Text(text())
                }
                else -> {
                    if (token != JsonToken.START_ARRAY) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                    val elements = LinkedHashSet<String>()
                    while (true) {
                        when (next()) {
                            JsonToken.END_ARRAY -> break
                            JsonToken.VALUE_STRING -> {
                                countEntry()
                                if (!elements.add(text())) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                            }
                            else -> fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                        }
                    }
                    SettingValue.TextSet(elements)
                }
            }
        }

        private fun wholeNumber(token: JsonToken, location: String, min: BigInteger, max: BigInteger): BigInteger {
            if (token != JsonToken.VALUE_NUMBER_INT) fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
            val number = parser.bigIntegerValue
            if (number < min || number > max) fail(PortableSettingsErrorCode.OUT_OF_RANGE, location)
            return number
        }

        private fun readRows(): ConfigurationRows {
            expectObject("configurationRows")
            var apps: List<AppActivityRow>? = null
            var timeLimits: List<TimeLimitRow>? = null
            members { member ->
                when (member) {
                    "apps" -> { if (apps != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, "configurationRows/apps"); apps = appRows() }
                    "timeLimits" -> { if (timeLimits != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, "configurationRows/timeLimits"); timeLimits = timeLimitRows() }
                    else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, "configurationRows")
                }
            }
            return ConfigurationRows(
                apps ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "configurationRows"),
                timeLimits ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, "configurationRows"),
            )
        }

        /** Calls [onElement] once per START_OBJECT element; the element's members are not yet read. */
        private inline fun objectElements(location: String, onElement: () -> Unit) {
            expectArray(location)
            while (true) {
                when (next()) {
                    JsonToken.END_ARRAY -> return
                    JsonToken.START_OBJECT -> { countEntry(); onElement() }
                    else -> fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                }
            }
        }

        private fun appRows(): List<AppActivityRow> {
            val location = "configurationRows/apps"
            val rows = ArrayList<AppActivityRow>()
            val primaryKeys = HashSet<Pair<String, String>>()
            objectElements(location) {
                var appName: String? = null
                var packageName: String? = null
                var activityName: String? = null
                var userSerial: Long? = null
                members { member ->
                    when (member) {
                        "appName" -> { if (appName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); appName = stringValue(location) }
                        "packageName" -> { if (packageName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); packageName = stringValue(location) }
                        "activityName" -> { if (activityName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); activityName = stringValue(location) }
                        "userSerial" -> { if (userSerial != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); userSerial = longValue(location) }
                        else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, location)
                    }
                }
                val row = AppActivityRow(
                    appName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    packageName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    activityName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    userSerial ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                )
                // The table's primary key is (app_name, package_name): a repeat would fail the SQL insert at apply time.
                if (!primaryKeys.add(row.appName to row.packageName)) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                rows += row
            }
            return rows
        }

        private fun timeLimitRows(): List<TimeLimitRow> {
            val location = "configurationRows/timeLimits"
            val rows = ArrayList<TimeLimitRow>()
            val primaryKeys = HashSet<String>()
            objectElements(location) {
                var packageName: String? = null
                var timeLimit: Long? = null
                var timeAdded: Long? = null
                members { member ->
                    when (member) {
                        "packageName" -> { if (packageName != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); packageName = stringValue(location) }
                        "timeLimitInMilli" -> { if (timeLimit != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); timeLimit = longValue(location) }
                        "timeAtAddingInMilli" -> { if (timeAdded != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); timeAdded = longValue(location) }
                        else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, location)
                    }
                }
                val row = TimeLimitRow(
                    packageName ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    timeLimit ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    timeAdded ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                )
                if (!primaryKeys.add(row.packageName)) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                rows += row
            }
            return rows
        }

        private fun readUnsupportedKeys(): List<UnsupportedKey> {
            val location = "unsupportedKeys"
            val names = ArrayList<UnsupportedKey>()
            val seen = HashSet<UnsupportedKey>()
            objectElements(location) {
                var store: SettingsStoreId? = null
                var key: String? = null
                members { member ->
                    when (member) {
                        "store" -> {
                            if (store != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                            store = when (stringValue(location)) {
                                SettingsStoreId.SHARED_PREFERENCES.locationName -> SettingsStoreId.SHARED_PREFERENCES
                                SettingsStoreId.DATA_STORE.locationName -> SettingsStoreId.DATA_STORE
                                else -> fail(PortableSettingsErrorCode.TYPE_MISMATCH, location)
                            }
                        }
                        "key" -> { if (key != null) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location); key = stringValue(location) }
                        else -> fail(PortableSettingsErrorCode.MALFORMED_JSON, location)
                    }
                }
                val name = UnsupportedKey(
                    store ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                    key ?: fail(PortableSettingsErrorCode.MALFORMED_JSON, location),
                )
                if (!seen.add(name)) fail(PortableSettingsErrorCode.DUPLICATE_KEY, location)
                names += name
            }
            return names
        }
    }

    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
}
