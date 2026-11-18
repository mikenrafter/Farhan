package ly.com.tahaben.farhan.settings

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import ly.com.tahaben.settings_portability.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private, bounded string records. Both journal and component state snapshot use the same strict reader. */
internal class PrivateSettingsFile(file: File) {
    private val atomic = AtomicFile(file)
    fun read(): Map<String, String>? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                check(output.size() + count <= MAX_BYTES)
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val fields = linkedMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                check(fields.size < 20_000)
                val name = reader.nextName()
                check(name.length <= 262_144 && name !in fields)
                check(reader.peek() == JsonToken.STRING)
                fields[name] = reader.nextString().also { check(it.length <= MAX_BYTES) }
            }
            reader.endObject()
            check(reader.peek() == JsonToken.END_DOCUMENT)
            fields
        }
    }
    fun write(fields: Map<String, String>) {
        val writer = StringWriter()
        JsonWriter(writer).use { json ->
            json.beginObject()
            fields.forEach { (key, value) -> json.name(key).value(value) }
            json.endObject()
        }
        val bytes = writer.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        atomic.finishWrite(output)
        // AtomicFile reports some rename failures only in platform logs; require the new record to be visible.
        check(read() == fields) { "Private settings record was not replaced" }
    }
    fun clear() {
        atomic.delete()
        check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists())
    }
    companion object { const val MAX_BYTES = 8 shl 20 }
}

/** no_backup only. Raw previous values, including credentials, must never reach a document or error text. */
internal class AndroidImportJournal(directory: File) : ImportJournalStorage {
    private val file = PrivateSettingsFile(File(directory, "settings_import_v1"))
    private val internalSource = PortableSource("private.journal", "internal", 1, 0)
    private val limits = DecodeLimits(maxBytes = PrivateSettingsFile.MAX_BYTES, maxEntries = 100_000,
        maxStringLength = PrivateSettingsFile.MAX_BYTES)
    private fun encode(configuration: SettingsConfiguration, source: PortableSource = internalSource,
                       unsupported: List<UnsupportedKey> = emptyList()) =
        PortableSettingsCodec.encode(PortableSettings(source, configuration, unsupported)).toString(Charsets.UTF_8)
    private fun decode(text: String): PortableSettings = when (val decoded = PortableSettingsCodec.decode(text.toByteArray(Charsets.UTF_8), limits)) {
        is DecodeResult.Valid -> decoded.settings
        is DecodeResult.Invalid -> throw JournalUnreadableException()
    }
    override fun write(journal: ImportJournal) {
        val fields = linkedMapOf("version" to "1")
        fun preview(preview: ImportPreview) {
            fields["preview"] = encode(SettingsConfiguration(), preview.source, preview.unsupportedKeys)
            fields["sharedCount"] = preview.sharedPreferenceCount.toString()
            fields["dataCount"] = preview.dataStoreCount.toString()
            fields["appCount"] = preview.appActivityRowCount.toString()
            fields["limitCount"] = preview.timeLimitRowCount.toString()
        }
        fun failure(failure: ImportFailure) {
            fields["failure"] = failure.reason.name
            fields["component"] = failure.component?.name ?: ""
        }
        when (journal) {
            is ImportJournal.Staged -> { fields["phase"] = "staged"; fields["desired"] = encode(journal.desired); preview(journal.preview) }
            is ImportJournal.Applying -> { fields["phase"] = "applying"; fields["previous"] = encode(journal.previous); fields["desired"] = encode(journal.desired); preview(journal.preview) }
            is ImportJournal.RollingBack -> { fields["phase"] = "rollingBack"; fields["previous"] = encode(journal.previous); failure(journal.failure) }
            is ImportJournal.Applied -> { fields["phase"] = "applied"; preview(journal.preview) }
            is ImportJournal.RolledBack -> { fields["phase"] = "rolledBack"; failure(journal.failure) }
        }
        file.write(fields)
    }
    override fun read(): ImportJournal? = try {
        file.read()?.let { fields ->
            check(fields["version"] == "1")
            val consumed = mutableSetOf("version", "phase")
            fun field(name: String): String { consumed += name; return fields.getValue(name) }
            fun preview(): ImportPreview {
                val decoded = decode(field("preview"))
                fun count(name: String) = field(name).toInt().also { check(it >= 0) }
                return ImportPreview(decoded.source, count("sharedCount"), count("dataCount"), count("appCount"), count("limitCount"), decoded.unsupportedKeys)
            }
            fun failure(): ImportFailure {
                val reason = FailureReason.valueOf(field("failure"))
                val component = field("component").takeIf { it.isNotEmpty() }?.let(SettingsComponent::valueOf)
                check((reason == FailureReason.STORE_FAILED) == (component != null))
                return ImportFailure(reason, component)
            }
            val journal = when (fields.getValue("phase")) {
                "staged" -> ImportJournal.Staged(decode(field("desired")).configuration, preview())
                "applying" -> ImportJournal.Applying(decode(field("previous")).configuration, decode(field("desired")).configuration, preview())
                "rollingBack" -> ImportJournal.RollingBack(decode(field("previous")).configuration, failure())
                "applied" -> ImportJournal.Applied(preview())
                "rolledBack" -> ImportJournal.RolledBack(failure())
                else -> throw JournalUnreadableException()
            }
            check(fields.keys == consumed)
            journal
        }
    } catch (failure: Exception) { throw JournalUnreadableException() }
    override fun clear() = file.clear()
}
