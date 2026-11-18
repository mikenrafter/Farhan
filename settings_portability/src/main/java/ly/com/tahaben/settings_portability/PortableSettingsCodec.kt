package ly.com.tahaben.settings_portability

/**
 * Bounds applied while reading, before anything large is allocated. [maxEntries] counts every preference entry,
 * every set element and every row across the document. [maxStringLength] bounds every parsed JSON key and string.
 */
data class DecodeLimits(
    val maxBytes: Int = 1 shl 20,
    val maxEntries: Int = 20_000,
    val maxDepth: Int = 8,
    val maxStringLength: Int = 262_144,
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
 *  - CLAIM(strict-input): a duplicate member name anywhere, a key in two type groups, a duplicate set element,
 *    an unknown version, a wrong type or range, trailing data, or a limit breach yields Invalid and no value.
 *
 * Shape: {"format":"farhan-settings","version":1,"source":{...},
 *   "sharedPreferences":{"booleans":{},"ints":{},"longs":{},"floats":{},"doubles":{},"strings":{},"stringSets":{}},
 *   "dataStore":{same groups},"configurationRows":{"apps":[],"timeLimits":[]},"unsupportedKeys":[]}
 * Every member is required.
 *
 * STUB: red phase. No behavior yet.
 */
object PortableSettingsCodec {
    fun encode(settings: PortableSettings): ByteArray = byteArrayOf()

    fun decode(bytes: ByteArray, limits: DecodeLimits = DecodeLimits()): DecodeResult =
        DecodeResult.Invalid(PortableSettingsError(PortableSettingsErrorCode.MALFORMED_JSON, "document"))
}
