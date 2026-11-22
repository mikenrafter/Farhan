package ly.com.tahaben.farhan.shell

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSwitchStoreTest {

    @Test
    fun `a fresh install has every feature on`() {
        // why: the owner asked for default all on, so no tab is missing before they touch Settings.
        // catches: a getBoolean default of false for any switch.
        assertEquals(FeatureSwitches(usage = true, launcher = true, focus = true), FeatureSwitchStore(MapPrefs()).switches.value)
    }

    @Test
    fun `a switch is written to shared preferences under its own key`() {
        // why: the portable backup lists these three keys, so the stored name is part of the contract.
        // catches: renaming a key without the settings catalog, or writing one switch under another's key.
        val prefs = MapPrefs()
        val store = FeatureSwitchStore(prefs)
        store.setLauncher(false)
        assertEquals(false, prefs.values["feature_switch_launcher"])
        assertFalse(store.switches.value.launcher)
        assertTrue(store.switches.value.focus)
        assertTrue(store.switches.value.usage)
        assertEquals(setOf("feature_switch_launcher"), prefs.values.keys)
    }

    @Test
    fun `a new store reads what an earlier one saved`() {
        // why: the bar must come back as the owner left it after the app restarts.
        // catches: holding the switches only in memory.
        val prefs = MapPrefs()
        FeatureSwitchStore(prefs).apply { setFocus(false); setUsage(false) }
        assertEquals(FeatureSwitches(usage = false, launcher = true, focus = false), FeatureSwitchStore(prefs).switches.value)
    }

    @Test
    fun `a change made to the file elsewhere shows up without a new store`() {
        // why: a restore writes these keys behind the store's back and the bar should follow.
        // catches: a flow that is read once at construction.
        val prefs = MapPrefs()
        val store = FeatureSwitchStore(prefs)
        prefs.edit().putBoolean("feature_switch_focus", false).apply()
        assertFalse(store.switches.value.focus)
    }

    /** Just enough SharedPreferences for the store: booleans, a commit and change notifications. */
    private class MapPrefs : SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                values.putAll(pending)
                pending.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MapPrefs, key) } }
            }
        }
    }
}
