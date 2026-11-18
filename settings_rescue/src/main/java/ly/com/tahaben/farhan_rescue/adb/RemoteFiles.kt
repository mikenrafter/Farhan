package ly.com.tahaben.farhan_rescue.adb

/** The three directories of the old app's private data the helper lists. Nothing else is ever listed. */
enum class RemoteDirectory(val path: String) {
    /** Structure only: the data directory itself and `files`, listed to tell "absent" from "listing failed". */
    ROOT("."),
    FILES("files"),
    SHARED_PREFS("shared_prefs"),
    DATA_STORE("files/datastore"),
    DATABASES("databases"),
}

enum class FileRole(val maxBytes: Long) {
    SHARED_PREFERENCES(8L shl 20),
    MAIN_DATA_STORE(8L shl 20),
    WIDGET_STATE(64L shl 10),
    DATABASE(128L shl 20),
    DATABASE_WAL(32L shl 20),
    DATABASE_JOURNAL(32L shl 20),
}

/**
 * One file the helper may copy. Only [RemoteFiles] can create one, from a name that matched the fixed allowlist, so
 * [remotePath] contains nothing but `[A-Za-z0-9_.-]` and `/`, and is safe inside a shell command line.
 */
class RemoteFile internal constructor(
    val directory: RemoteDirectory,
    val name: String,
    val role: FileRole,
    /** Path inside the private job directory, `/`-separated, no `..`. */
    val localPath: String,
) {
    val remotePath: String get() = "${directory.path}/$name"
    override fun equals(other: Any?) = other is RemoteFile && other.remotePath == remotePath && other.localPath == localPath
    override fun hashCode() = remotePath.hashCode()
    override fun toString() = "RemoteFile($remotePath)"
}

/**
 * The allowlist.
 *
 * CLAIM(allowlist-only): a name is accepted only when it equals one of the fixed names below or matches
 * `appWidget-[0-9]{1,10}.preferences_pb`; everything else (including names with spaces, newlines, `..`, a slash or a
 * shell metacharacter) is ignored and counted [RemoteFilesTest].
 */
object RemoteFiles {
    const val PREFERENCES_XML = "shared_pref.xml"
    const val PREFERENCES_BACKUP = "shared_pref.xml.bak"
    const val MAIN_DATA_STORE = "farhan_data_store.preferences_pb"
    const val DATABASE = "farhan_db"
    const val DATABASE_WAL = "farhan_db-wal"
    const val DATABASE_JOURNAL = "farhan_db-journal"
    const val MAX_WIDGET_FILES = 256
    /** CLAIM(bounded-capture): checked from `stat` sizes before the first byte is copied [CaptureEngineTest]. */
    const val MAX_TOTAL_BYTES = 192L shl 20
    const val MAX_WIDGET_TOTAL_BYTES = 4L shl 20

    private val WIDGET_NAME = Regex("""appWidget-[0-9]{1,10}\.preferences_pb""")

    /** Local names inside the job directory. */
    const val LOCAL_PREFERENCES = "shared_prefs/shared_pref.xml"
    const val LOCAL_DATABASE = "databases/farhan_db"

    fun classify(directory: RemoteDirectory, name: String): RemoteFile? {
        fun file(role: FileRole, local: String) = RemoteFile(directory, name, role, local)
        return when (directory) {
            RemoteDirectory.ROOT, RemoteDirectory.FILES -> null
            RemoteDirectory.SHARED_PREFS -> when (name) {
                PREFERENCES_XML, PREFERENCES_BACKUP -> file(FileRole.SHARED_PREFERENCES, LOCAL_PREFERENCES)
                else -> null
            }
            RemoteDirectory.DATA_STORE -> when {
                name == MAIN_DATA_STORE -> file(FileRole.MAIN_DATA_STORE, "datastore/$name")
                WIDGET_NAME.matches(name) -> file(FileRole.WIDGET_STATE, "datastore/$name")
                else -> null
            }
            RemoteDirectory.DATABASES -> when (name) {
                DATABASE -> file(FileRole.DATABASE, LOCAL_DATABASE)
                DATABASE_WAL -> file(FileRole.DATABASE_WAL, "databases/$name")
                DATABASE_JOURNAL -> file(FileRole.DATABASE_JOURNAL, "databases/$name")
                else -> null
            }
        }
    }

    /** Reverse of [classify] for a path the bridge reports back: the UI re-checks it before opening anything. */
    fun classifyLocal(localPath: String): FileRole? = when {
        localPath == LOCAL_PREFERENCES -> FileRole.SHARED_PREFERENCES
        localPath == "datastore/$MAIN_DATA_STORE" -> FileRole.MAIN_DATA_STORE
        localPath.startsWith("datastore/") && WIDGET_NAME.matches(localPath.removePrefix("datastore/")) -> FileRole.WIDGET_STATE
        localPath == LOCAL_DATABASE -> FileRole.DATABASE
        localPath == "databases/$DATABASE_WAL" -> FileRole.DATABASE_WAL
        localPath == "databases/$DATABASE_JOURNAL" -> FileRole.DATABASE_JOURNAL
        else -> null
    }
}

enum class PlanProblem {
    /** A -wal or -journal exists with no database to apply it to. */
    SIDECAR_WITHOUT_DATABASE,
    TOO_MANY_WIDGET_FILES,
    /** Nothing from the allowlist exists: wrong package or user, or an app that never ran. */
    NOTHING_TO_EXPORT,
}

sealed interface CapturePlan {
    data class Planned(
        val files: List<RemoteFile>,
        /** True when `shared_pref.xml.bak` was chosen over the main XML (Android's own rule). */
        val usedPreferencesBackup: Boolean,
        /** Names in the listings that matched nothing. Their names are never shown or used. */
        val ignoredNameCount: Int,
    ) : CapturePlan

    data class Refused(val problem: PlanProblem) : CapturePlan
}

object CapturePlanner {
    /** The directories that can hold allowlisted files. */
    val DATA_DIRECTORIES = listOf(RemoteDirectory.SHARED_PREFS, RemoteDirectory.DATA_STORE, RemoteDirectory.DATABASES)

    /**
     * Picks the files to copy from directory listings.
     *
     * CLAIM(bak-preferred): when `shared_pref.xml.bak` exists the backup is copied and the main XML is not,
     * because Android discards the main file in that case, even when it is truncated.
     * CLAIM(fresh-app-ok): a missing DataStore or database is allowed (an app that never wrote one); a database
     * sidecar without its database is refused.
     */
    fun plan(listings: Map<RemoteDirectory, List<String>>): CapturePlan {
        var ignored = 0
        val byDirectory = DATA_DIRECTORIES.associateWith { directory ->
            listings[directory].orEmpty().distinct().mapNotNull { name ->
                RemoteFiles.classify(directory, name).also { if (it == null) ignored++ }
            }
        }
        val prefs = byDirectory.getValue(RemoteDirectory.SHARED_PREFS)
        val chosenPrefs = prefs.firstOrNull { it.name == RemoteFiles.PREFERENCES_BACKUP } ?: prefs.firstOrNull()
        val dataStore = byDirectory.getValue(RemoteDirectory.DATA_STORE).sortedBy { it.name }
        val widgets = dataStore.filter { it.role == FileRole.WIDGET_STATE }
        if (widgets.size > RemoteFiles.MAX_WIDGET_FILES) return CapturePlan.Refused(PlanProblem.TOO_MANY_WIDGET_FILES)
        val database = byDirectory.getValue(RemoteDirectory.DATABASES).sortedBy { it.name }
        val hasMainDatabase = database.any { it.role == FileRole.DATABASE }
        if (!hasMainDatabase && database.isNotEmpty()) return CapturePlan.Refused(PlanProblem.SIDECAR_WITHOUT_DATABASE)
        val files = listOfNotNull(chosenPrefs) + dataStore + database
        if (files.isEmpty()) return CapturePlan.Refused(PlanProblem.NOTHING_TO_EXPORT)
        return CapturePlan.Planned(files, chosenPrefs?.name == RemoteFiles.PREFERENCES_BACKUP, ignored)
    }
}
