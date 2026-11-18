package ly.com.tahaben.settings_portability

enum class SettingType { BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, STRING_SET }

/**
 * One typed preference value. Int and Long are separate cases so a stored long never passes through a double
 * or an int (CLAIM(lossless-long)). A set compares as a set; its order is fixed only when written to a file.
 */
sealed interface SettingValue {
    val type: SettingType

    data class Bool(val value: Boolean) : SettingValue { override val type get() = SettingType.BOOLEAN }
    data class Int32(val value: Int) : SettingValue { override val type get() = SettingType.INT }
    data class Int64(val value: Long) : SettingValue { override val type get() = SettingType.LONG }
    data class Float32(val value: Float) : SettingValue { override val type get() = SettingType.FLOAT }
    data class Float64(val value: Double) : SettingValue { override val type get() = SettingType.DOUBLE }
    data class Text(val value: String) : SettingValue { override val type get() = SettingType.STRING }
    data class TextSet(val values: Set<String>) : SettingValue { override val type get() = SettingType.STRING_SET }
}
