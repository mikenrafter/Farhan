package ly.com.tahaben.settings_android

import android.util.JsonWriter
import ly.com.tahaben.core.model.ThemeColors
import ly.com.tahaben.core.model.UIModeAppearance
import ly.com.tahaben.settings_portability.SettingValue
import java.io.StringWriter

enum class WidgetStyle { HORIZONTAL, VERTICAL }

data class WidgetProfile(
    val themeColors: ThemeColors = ThemeColors.Classic,
    val uiModeAppearance: UIModeAppearance = UIModeAppearance.FOLLOW_SYSTEM,
    val style: WidgetStyle = WidgetStyle.VERTICAL,
    val compareWithYesterday: Boolean = false,
    val textSize: Int = 32,
)

/** Portable user choices only. No widget IDs, usage values, loading flags or WorkManager intervals. */
object WidgetProfiles {
    const val KEY = "imported_widget_profiles_v1"
    val USER_KEYS: Set<String> = setOf("widget_theme_colors", "widget_ui_mode", "widget_style", "widget_compare_yesterday", "text_size")
    private val fields = setOf("themeColors", "uiModeAppearance", "style", "compareWithYesterday", "textSize")

    fun validate(profile: WidgetProfile) {
        // The current configuration UI offers 8..48 in steps of two; retain odd legacy values in that range.
        require(profile.textSize in 8..48) { "Invalid widget text size" }
    }
    fun fromLegacy(states: List<Map<String, SettingValue>>): List<WidgetProfile> = merge(states.mapNotNull { state ->
        if (state.keys.none { it in USER_KEYS }) return@mapNotNull null
        fun text(key: String, default: String): String = state[key]?.let { (it as SettingValue.Text).value } ?: default
        WidgetProfile(
            ThemeColors.valueOf(text("widget_theme_colors", ThemeColors.Classic.name)),
            UIModeAppearance.valueOf(text("widget_ui_mode", UIModeAppearance.FOLLOW_SYSTEM.name)),
            WidgetStyle.valueOf(text("widget_style", WidgetStyle.VERTICAL.name)),
            state["widget_compare_yesterday"]?.let { (it as SettingValue.Bool).value } ?: false,
            state["text_size"]?.let { (it as SettingValue.Int32).value } ?: 32,
        ).also(::validate)
    })
    fun merge(vararg groups: List<WidgetProfile>): List<WidgetProfile> = groups.flatMap { it }.onEach(::validate).distinct()
        .sortedWith(compareBy<WidgetProfile> { it.themeColors.name }.thenBy { it.uiModeAppearance.name }
            .thenBy { it.style.name }.thenBy { it.compareWithYesterday }.thenBy { it.textSize })

    fun encode(profiles: List<WidgetProfile>): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray()
            merge(profiles).forEach { profile ->
                writer.beginObject()
                writer.name("themeColors").value(profile.themeColors.name)
                writer.name("uiModeAppearance").value(profile.uiModeAppearance.name)
                writer.name("style").value(profile.style.name)
                writer.name("compareWithYesterday").value(profile.compareWithYesterday)
                writer.name("textSize").value(profile.textSize)
                writer.endObject()
            }
            writer.endArray()
        }
        return output.toString().also { require(it.length <= 262_144) { "Widget profiles exceed size limit" } }
    }
    fun decode(text: String): List<WidgetProfile> = try {
        val array = StrictPreferenceJson.parse(text) as List<*>
        merge(array.map { raw ->
            val entry = raw as Map<*, *>
            require(entry.keys == fields)
            val size = entry["textSize"] as Long
            require(size in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            WidgetProfile(ThemeColors.valueOf(entry["themeColors"] as String),
                UIModeAppearance.valueOf(entry["uiModeAppearance"] as String),
                WidgetStyle.valueOf(entry["style"] as String), entry["compareWithYesterday"] as Boolean, size.toInt())
        })
    } catch (_: Exception) { throw IllegalArgumentException("Invalid widget profiles") }
}
