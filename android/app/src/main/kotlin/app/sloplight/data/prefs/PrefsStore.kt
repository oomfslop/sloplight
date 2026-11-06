package app.sloplight.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Where settings are read and written: the app's default SharedPreferences
 * (the settings screen) or a profile's options held in memory (the profile
 * editor). Keys and value types are the ones PreferenceConfiguration reads.
 */
interface PrefsStore {
    /** The keys that change (null: everything, after a clear) */
    val changes: Flow<String?>

    /** These prefs as a SharedPreferences, for code that reads them that way */
    val sharedPreferences: SharedPreferences

    fun all(): Map<String, Any?> = sharedPreferences.all

    fun contains(key: String): Boolean = sharedPreferences.contains(key)

    fun getString(key: String, default: String?): String? =
        try {
            sharedPreferences.getString(key, default)
        } catch (_: ClassCastException) {
            default
        }

    fun getBoolean(key: String, default: Boolean): Boolean =
        try {
            sharedPreferences.getBoolean(key, default)
        } catch (_: ClassCastException) {
            default
        }

    fun getInt(key: String, default: Int): Int =
        try {
            sharedPreferences.getInt(key, default)
        } catch (_: ClassCastException) {
            (all()[key] as? Number)?.toInt() ?: default
        }

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = sharedPreferences.edit()
        editor.block()
        editor.apply()
    }

    companion object {
        /** The app's default SharedPreferences */
        fun default(context: Context): PrefsStore =
            SharedPrefsStore(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

        /** A copy of [initial] edited in memory only */
        fun inMemory(initial: Map<String, *>?): InMemoryPrefsStore = InMemoryPrefsStore(initial)
    }
}

/** A store over any SharedPreferences; its changes come from the change listener */
open class SharedPrefsStore(final override val sharedPreferences: SharedPreferences) : PrefsStore {
    override val changes: Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        // (the listener is only weakly held by SharedPreferences; this block keeps it alive)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** A profile's options, edited in memory until the profile is saved */
class InMemoryPrefsStore(initial: Map<String, *>?) :
    SharedPrefsStore(InMemorySharedPreferences(initial)) {
    /** All the options as they are now (what a profile saves) */
    fun snapshot(): HashMap<String, Any?> = HashMap(all())
}

/**
 * SharedPreferences over a plain map. Numbers may come back from JSON as
 * doubles, so ints and longs are read from any Number.
 */
class InMemorySharedPreferences(initial: Map<String, *>?) : SharedPreferences {
    private val values = HashMap<String, Any?>(initial ?: emptyMap<String, Any?>())
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(values) { HashMap(values) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(values) { values[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        synchronized(values) { values[key] as? Set<String> ?: defValues }

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(values) { (values[key] as? Number)?.toInt() ?: defValue }

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(values) { (values[key] as? Number)?.toLong() ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(values) { (values[key] as? Number)?.toFloat() ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notify(keys: Collection<String?>) {
        val current = synchronized(listeners) { listeners.toList() }
        for (key in keys) for (l in current) l.onSharedPreferenceChanged(this, key)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { changes[key] = values?.toSet(); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { changes[key] = null; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changed = ArrayList<String?>()
            synchronized(values) {
                if (clear) {
                    values.clear()
                    changed.add(null)
                }
                for ((k, v) in changes) {
                    if (v == null) values.remove(k) else values[k] = v
                    changed.add(k)
                }
            }
            changes.clear()
            clear = false
            notify(changed)
        }
    }
}
