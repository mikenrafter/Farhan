package ly.com.tahaben.settings_android

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** Shared bounded parser for embedded settings JSON; duplicates and nonintegral numbers are refused. */
internal object StrictPreferenceJson {
    fun parse(text: String): Any? = JsonReader(StringReader(text)).use { reader ->
        require(text.length <= 262_144)
        reader.isLenient = false
        var entries = 0
        fun read(depth: Int): Any? {
            require(depth <= 8 && ++entries <= 20_000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val fields = linkedMapOf<String, Any?>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key !in fields)
                        fields[key] = read(depth + 1)
                    }
                    reader.endObject()
                    fields
                }
                JsonToken.BEGIN_ARRAY -> {
                    val elements = mutableListOf<Any?>()
                    reader.beginArray()
                    while (reader.hasNext()) elements += read(depth + 1)
                    reader.endArray()
                    elements
                }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString().also { require(it.matches(Regex("-?(0|[1-9][0-9]*)"))) }.toLong()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> error("Invalid preference JSON")
            }
        }
        val root = read(0)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        root
    }
}
