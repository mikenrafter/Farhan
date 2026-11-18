package ly.com.tahaben.settings_portability

import java.io.IOException

/** Stands in for the process dying. An Error, so a coordinator that catches Exception cannot swallow it. */
class SimulatedProcessDeath : Error("simulated process death")

/**
 * The shared "disk". Every durable mutation (journal write or clear, store apply or replace) calls
 * [beforeMutation] first, so one counter orders all of them across stores and journal.
 */
class Faults {
    var operations = 0
        private set
    var crashAtOperation: Int? = null
    var tearOnCrash = false

    fun beforeMutation(tear: () -> Unit) {
        operations++
        if (crashAtOperation == operations) {
            if (tearOnCrash) tear()
            throw SimulatedProcessDeath()
        }
    }
}

class FakePreferencesStore(
    private val faults: Faults,
    initial: Map<String, SettingValue>,
) : PreferencesStore {
    var contents: Map<String, SettingValue> = initial
        private set
    /** Fails an apply whose [apply] `put` matches. Keyed on the value so a replayed restore is not caught by it. */
    var failWhen: (Map<String, SettingValue>) -> Boolean = { false }
    var failReads = false

    override fun readAll(): Map<String, SettingValue> {
        if (failReads) throw IOException("injected read failure")
        return contents
    }

    override fun apply(removeKeys: Set<String>, put: Map<String, SettingValue>) {
        // A torn write: the process dies after the removals and half of the puts reached the file.
        faults.beforeMutation {
            contents = (contents - removeKeys) + put.entries.sortedBy { it.key }.take((put.size + 1) / 2).associate { it.key to it.value }
        }
        if (failWhen(put)) throw IOException("injected store failure")
        contents = (contents - removeKeys) + put
    }
}

class FakeRowsStore(
    private val faults: Faults,
    initial: ConfigurationRows,
) : ConfigurationRowsStore {
    var contents: ConfigurationRows = initial
        private set
    var failWhen: (ConfigurationRows) -> Boolean = { false }

    override fun read(): ConfigurationRows = contents

    override fun replace(rows: ConfigurationRows) {
        // A torn write: only the first table reached disk.
        faults.beforeMutation { contents = contents.copy(apps = rows.apps) }
        if (failWhen(rows)) throw IOException("injected store failure")
        contents = rows
    }
}

class FakeJournalStorage(private val faults: Faults) : ImportJournalStorage {
    var current: ImportJournal? = null
        private set
    var unreadable = false
    /** Fails a write of a record this matches, after counting it as an attempted mutation. */
    var failWriteWhen: (ImportJournal) -> Boolean = { false }

    override fun read(): ImportJournal? {
        if (unreadable) throw JournalUnreadableException()
        return current
    }

    override fun write(journal: ImportJournal) {
        faults.beforeMutation { }
        if (failWriteWhen(journal)) throw IOException("injected journal write failure")
        current = journal
    }

    override fun clear() {
        faults.beforeMutation { }
        current = null
    }
}

val ImportJournal?.phaseName: String get() = this?.let { it::class.simpleName } ?: "None"

/** Document builder for hand-written inputs. Content is literal; this only assembles the envelope. */
object Json {
    const val NO_GROUPS =
        """{"booleans":{},"ints":{},"longs":{},"floats":{},"doubles":{},"strings":{},"stringSets":{}}"""

    fun groups(
        booleans: String = "",
        ints: String = "",
        longs: String = "",
        floats: String = "",
        doubles: String = "",
        strings: String = "",
        stringSets: String = "",
    ) = """{"booleans":{$booleans},"ints":{$ints},"longs":{$longs},"floats":{$floats},"doubles":{$doubles},""" +
        """"strings":{$strings},"stringSets":{$stringSets}}"""

    const val SOURCE =
        """{"packageName":"ly.com.tahaben.farhan_alpha","versionName":"0.2.6-alpha","versionCode":11,"userId":0}"""

    fun document(
        version: String = "1",
        shared: String = NO_GROUPS,
        dataStore: String = NO_GROUPS,
        apps: String = "",
        timeLimits: String = "",
        unsupported: String = "",
        format: String = "farhan-settings",
    ) = """{"format":"$format","version":$version,"source":$SOURCE,"sharedPreferences":$shared,"dataStore":$dataStore,""" +
        """"configurationRows":{"apps":[$apps],"timeLimits":[$timeLimits]},"unsupportedKeys":[$unsupported]}"""
}
