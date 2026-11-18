package ly.com.tahaben.notification_filter_data.automation

import ly.com.tahaben.notification_filter_domain.schedule.AutomationError
import ly.com.tahaben.notification_filter_domain.schedule.IntentWindow
import ly.com.tahaben.notification_filter_domain.schedule.ScheduleAutomation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the `payload` extra into typed fields. The established org.json codec reads the text; this object adds
 * the contract's strictness on top.
 *
 * CLAIM(payload-bounded): the raw text is measured before any parsing, so an oversized payload costs one length
 * check [ScheduleIntentParserTest].
 * CLAIM(strict-integers): version, start, end and budget must be JSON integers. `5.0`, `"5"`, `true` and `null`
 * never become 5 [ScheduleIntentParserTest].
 *
 * Errors: [Failed] carries the [AutomationError] to acknowledge. A window with a missing or non-integer field
 * is not a failure here: it becomes an [IntentWindow] with a null field, which the domain reports as MALFORMED_WINDOW.
 */
object ScheduleIntentParser {
    /**
     * Characters (UTF-16 units), not bytes: 4096 of them are at most 12 KiB as UTF-8. 24 windows fit in about
     * 1.3 KB, so this leaves room for the date and spacing.
     */
    const val MAX_PAYLOAD_CHARS = 4096

    private val TOP_LEVEL_KEYS = setOf("version", "mode", "date", "windows")
    private val WINDOW_KEYS = setOf("start", "end", "budget")

    sealed interface Result {
        data class Parsed(val version: Int?, val mode: String?, val localDate: String?, val windows: List<IntentWindow>) : Result
        data class Failed(val error: AutomationError) : Result
    }

    fun parse(raw: String): Result {
        if (raw.length > MAX_PAYLOAD_CHARS) return Result.Failed(AutomationError.PAYLOAD_TOO_LARGE)
        val root = try { JSONObject(raw) } catch (_: Exception) { return Result.Failed(AutomationError.MALFORMED_PAYLOAD) }
        if (root.keys().asSequence().any { it !in TOP_LEVEL_KEYS }) return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
        val mode = optionalString(root, "mode") ?: return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
        val date = optionalString(root, "date") ?: return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
        val array = root.opt("windows") as? JSONArray ?: return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
        if (array.length() > ScheduleAutomation.MAX_WINDOWS) return Result.Failed(AutomationError.PAYLOAD_TOO_LARGE)
        val windows = (0 until array.length()).map {
            val window = array.opt(it) as? JSONObject ?: return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
            if (window.keys().asSequence().any { key -> key !in WINDOW_KEYS }) return Result.Failed(AutomationError.MALFORMED_PAYLOAD)
            IntentWindow(strictInt(window.opt("start")), strictInt(window.opt("end")), strictInt(window.opt("budget")))
        }
        return Result.Parsed(strictInt(root.opt("version")), mode.value, date.value, windows)
    }

    /** Wraps a nullable string so "absent" (null value) differs from "present but the wrong type" (null wrapper). */
    private data class Optional(val value: String?)

    private fun optionalString(root: JSONObject, key: String): Optional? = when (val value = root.opt(key)) {
        null -> Optional(null)
        is String -> Optional(value)
        else -> null
    }

    private fun strictInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }
}
