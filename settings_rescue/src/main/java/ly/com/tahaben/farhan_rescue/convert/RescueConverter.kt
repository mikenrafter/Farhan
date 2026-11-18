package ly.com.tahaben.farhan_rescue.convert

import ly.com.tahaben.farhan_rescue.adb.FileRole
import ly.com.tahaben.farhan_rescue.adb.RemoteFiles
import ly.com.tahaben.farhan_rescue.capture.CaptureFacts
import ly.com.tahaben.farhan_rescue.input.SourceTarget
import ly.com.tahaben.farhan_rescue.storage.JobDirectory
import ly.com.tahaben.settings_android.AndroidSettingsValidator
import ly.com.tahaben.settings_android.WidgetProfiles
import ly.com.tahaben.settings_portability.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Real widget-profile logic from the shared `:settings_android` library. */
object AndroidWidgetProfilePort : WidgetProfilePort {
    override fun encodedProfiles(widgetStates: List<Map<String, SettingValue>>, existingEncoded: String?): String? {
        val userStates = widgetStates.map { state -> state.filterKeys { it in WidgetProfiles.USER_KEYS } }
        val imported = if (existingEncoded != null) WidgetProfiles.decode(existingEncoded) else emptyList()
        val merged = WidgetProfiles.merge(WidgetProfiles.fromLegacy(userStates), imported)
        return if (merged.isEmpty()) null else WidgetProfiles.encode(merged)
    }

    /** Number of profiles in an encoded string, for the preview. */
    fun count(encoded: String?): Int = if (encoded == null) 0 else WidgetProfiles.decode(encoded).size
}

/**
 * Copied files of the old app -> a validated portable file. Runs in the UI process on a worker thread, after the
 * bridge has finished and gone quiet; it only reads the private job directory.
 *
 * Chain (CLAIM(shared-core-only): no conversion rule lives here, only decoding of copies):
 *   copies -> typed maps/rows -> [SnapshotAssembler] -> [LegacySettingsConverter] -> [PortableFileSealer].
 * Error text carries a reason and at most a `store/key` name, never a value.
 */
class RescueConverter(
    private val widgets: WidgetProfilePort = AndroidWidgetProfilePort,
    private val validator: SemanticValidator = AndroidSettingsValidator,
) {
    fun convert(job: JobDirectory, facts: CaptureFacts, target: SourceTarget): ConversionResult = try {
        convertUnchecked(job, facts, target)
    } catch (failure: CopiedStoreException) {
        ConversionResult.Failed(ConversionFailure.valueOf(failure.reason.name))
    } catch (failure: SnapshotAssembler.Rejected) {
        ConversionResult.Failed(ConversionFailure.WIDGET_PROFILE_INVALID)
    } catch (failure: IOException) {
        ConversionResult.Failed(ConversionFailure.COPY_UNREADABLE)
    }

    private fun convertUnchecked(job: JobDirectory, facts: CaptureFacts, target: SourceTarget): ConversionResult {
        val files = facts.localPaths.map { path ->
            val role = RemoteFiles.classifyLocal(path) ?: return ConversionResult.Failed(ConversionFailure.FILES_MISSING)
            Triple(path, role, job.file(path) ?: return ConversionResult.Failed(ConversionFailure.FILES_MISSING))
        }
        val xml = files.firstOrNull { it.second == FileRole.SHARED_PREFERENCES }?.let { readUtf8(it.third) } ?: EMPTY_PREFERENCES_XML
        val mainStore = files.firstOrNull { it.second == FileRole.MAIN_DATA_STORE }?.let { CopiedDataStoreReader.read(it.third) } ?: emptyMap()
        val widgetStates = files.filter { it.second == FileRole.WIDGET_STATE }.map { CopiedDataStoreReader.read(it.third) }
        val rows = files.firstOrNull { it.second == FileRole.DATABASE }?.let { CopiedDatabaseReader.read(it.third) } ?: ConfigurationRows()

        val source = PortableSource(target.packageName.value, facts.version?.name ?: "unknown", facts.version?.code ?: 0L, target.user.value)
        val snapshot = SnapshotAssembler(widgets, WidgetProfiles.KEY).assemble(source, xml, mainStore, widgetStates, rows)
        return when (val converted = LegacySettingsConverter.convert(snapshot, validator)) {
            is PortableSettingsResult.Failed -> ConversionResult.Failed(ConversionFailure.SETTINGS_REJECTED, "${converted.error.code} ${converted.error.location}")
            is PortableSettingsResult.Ready -> {
                val encoded = (snapshot.dataStore[WidgetProfiles.KEY] as? SettingValue.Text)?.value
                PortableFileSealer.seal(converted, validator, AndroidWidgetProfilePort.count(encoded), facts.version != null)
            }
        }
    }

    private fun readUtf8(file: java.io.File): String {
        if (file.length() > 8L shl 20) throw CopiedStoreException(CopiedStoreException.Reason.FILE_TOO_LARGE)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw CopiedStoreException(CopiedStoreException.Reason.NOT_UTF8)
        }
    }

    companion object {
        /** A fresh app that never wrote preferences: Android's own empty file. */
        const val EMPTY_PREFERENCES_XML = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n"
    }
}
