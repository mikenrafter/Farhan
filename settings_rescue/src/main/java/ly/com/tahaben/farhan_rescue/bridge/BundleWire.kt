package ly.com.tahaben.farhan_rescue.bridge

import android.os.Bundle

/** Copies a [Wire] map into a Bundle and back. Only String, Int, Long and string lists are carried. */
internal object BundleWire {
    fun toBundle(wire: Wire): Bundle = Bundle().also { bundle ->
        for ((key, value) in wire) {
            when (value) {
                null -> Unit
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is List<*> -> bundle.putStringArrayList(key, ArrayList(value.map { it as String }))
                else -> error("unsupported wire value")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun fromBundle(bundle: Bundle?): Wire {
        if (bundle == null) return emptyMap()
        return bundle.keySet().associateWith { key -> bundle.get(key) }
    }
}
