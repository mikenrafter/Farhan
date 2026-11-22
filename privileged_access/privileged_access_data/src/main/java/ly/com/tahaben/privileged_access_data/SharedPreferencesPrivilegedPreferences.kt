package ly.com.tahaben.privileged_access_data

import android.content.SharedPreferences
import ly.com.tahaben.privileged_access_domain.PrivilegedPreferences
import ly.com.tahaben.privileged_access_domain.RoutePreference

/**
 * The route choice is a setting and joins the settings backup (`SettingsCatalog`). [linkDropPending] is bookkeeping:
 * it is excluded from the backup and written with `commit()` because the process may be killed right after.
 */
class SharedPreferencesPrivilegedPreferences(private val preferences: SharedPreferences) : PrivilegedPreferences {
    override var route: RoutePreference
        get() = RoutePreference.fromStored(preferences.getString(KEY_ROUTE, null))
        set(value) { preferences.edit().putString(KEY_ROUTE, value.name).apply() }

    override var linkDropPending: Boolean
        get() = preferences.getBoolean(KEY_LINK_DROP_PENDING, false)
        set(value) { preferences.edit().putBoolean(KEY_LINK_DROP_PENDING, value).commit() }

    companion object {
        const val KEY_ROUTE = "privileged_access_route"
        const val KEY_LINK_DROP_PENDING = "privileged_access_link_drop_pending"
    }
}
