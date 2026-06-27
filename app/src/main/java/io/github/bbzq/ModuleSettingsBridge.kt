package io.github.bbzq

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

class ModuleSettingsBridge private constructor() : SharedPreferences {
    private val cacheLock = Any()
    private var localCache: Map<String, Any> = emptyMap()
    private var lastLoadTime = 0L
    private var hasAuthoritativeSnapshot = false

    private fun ensureLoaded() {
        val now = System.currentTimeMillis()
        var snapshotToPersist: Map<String, Any>? = null
        synchronized(cacheLock) {
            if (localCache.isNotEmpty() && now - lastLoadTime < CACHE_EXPIRATION) return
            val source = getAllFromSources()
            val loaded = source.values
                .mapNotNull { (key, value) -> value?.let { key to it } }
                .toMap()
            if (loaded.isNotEmpty()) localCache = loaded
            hasAuthoritativeSnapshot = source.authoritative
            lastLoadTime = now
            lastProviderStatus = source.status
            if (source.authoritative && loaded.isNotEmpty() && source.persistSnapshot) {
                snapshotToPersist = loaded
            }
        }
        snapshotToPersist?.let(::persistHostSnapshot)
    }

    private fun getAllFromSources(): SettingsSource {
        val remote = getAllFromRemotePreferences()
        if (remote.isNotEmpty()) return SettingsSource(remote, authoritative = true, status = "remote ok")

        val failedStatus = lastProviderStatus
        val snapshot = getAllFromHostSnapshot()
        if (snapshot.isNotEmpty()) {
            return SettingsSource(
                snapshot,
                authoritative = true,
                status = "snapshot ok; live settings unavailable ($failedStatus)",
                persistSnapshot = false,
            )
        }

        return SettingsSource(
            fallbackDefaults(),
            authoritative = true,
            status = "defaults only; remote settings unavailable ($failedStatus)",
            persistSnapshot = false,
        )
    }

    private fun getAllFromRemotePreferences(): Map<String, Any?> {
        val remotePrefs = resolveRemotePreferences() ?: run {
            lastProviderStatus = "remote unavailable"
            return emptyMap()
        }
        return runCatching {
            remotePrefs.all.mapValues { it.value }
        }.getOrElse {
            lastProviderStatus = "remote ${it.javaClass.simpleName}: ${it.message}"
            emptyMap()
        }
    }

    private fun getAllFromHostSnapshot(): Map<String, Any?> =
        runCatching {
            resolveHostSnapshotPreferences()?.all.orEmpty().mapValues { it.value }
        }.getOrElse {
            lastProviderStatus = "snapshot ${it.javaClass.simpleName}: ${it.message}"
            emptyMap()
        }

    private fun fallbackDefaults(): Map<String, Any?> = mapOf(
        ModuleSettings.KEY_SKIP_SPLASH_AD_ENABLED to true,
        ModuleSettings.KEY_UNLOCK_VIDEO_FEATURES_ENABLED to false,
        ModuleSettings.KEY_FULL_NUMBER_FORMAT_ENABLED to false,
    )

    override fun getAll(): MutableMap<String, *> {
        ensureLoaded()
        return synchronized(cacheLock) { localCache.toMutableMap() }
    }

    override fun getString(key: String?, defValue: String?): String? {
        ensureLoaded()
        return synchronized(cacheLock) { localCache[key] as? String } ?: run {
            defValue
        }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        ensureLoaded()
        val cached = synchronized(cacheLock) { localCache[key] }
        val cachedSet = when (cached) {
            is Set<*> -> safeStringSet(cached)
            is List<*> -> safeStringSet(cached)
            else -> null
        }
        if (cachedSet != null) return cachedSet
        return defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        ensureLoaded()
        return (synchronized(cacheLock) { localCache[key] } as? Number)?.toInt() ?: run {
            defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        ensureLoaded()
        return when (val value = synchronized(cacheLock) { localCache[key] }) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        ensureLoaded()
        return when (val value = synchronized(cacheLock) { localCache[key] }) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        ensureLoaded()
        return (synchronized(cacheLock) { localCache[key] } as? Boolean) ?: run {
            defValue
        }
    }

    override fun contains(key: String?): Boolean {
        ensureLoaded()
        if (synchronized(cacheLock) { localCache.containsKey(key) }) return true
        return false
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private fun resolveContext(): Context? {
        cachedContext.get()?.let { return it }
        val application = runCatching {
            currentApplicationMethod.invoke(null) as? Application
        }.getOrNull() ?: return null
        cachedContext = WeakReference(application)
        return application
    }

    private fun resolveRemotePreferences(): SharedPreferences? {
        cachedRemotePrefs.get()?.let { return it }
        val xposed = cachedXposed ?: return null
        return runCatching {
            xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
        }.getOrNull()?.also {
            cachedRemotePrefs = WeakReference(it)
        }
    }

    private fun resolveHostSnapshotPreferences(): SharedPreferences? =
        resolveContext()?.getSharedPreferences(HOST_SNAPSHOT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistHostSnapshot(values: Map<String, Any>) {
        val prefs = resolveHostSnapshotPreferences() ?: return
        runCatching {
            prefs.edit()
                .clear()
                .applyValues(values)
                .apply()
        }.onFailure {
            lastProviderStatus = "snapshot write ${it.javaClass.simpleName}: ${it.message}"
            Log.w(LOG_TAG, "runtime settings snapshot write failed", it)
        }
    }

    private fun persistHostSnapshotUpdates(values: Map<String, Any?>) {
        if (values.isEmpty()) return
        val prefs = resolveHostSnapshotPreferences() ?: return
        runCatching {
            val editor = prefs.edit()
            values.forEach { (key, value) ->
                if (value == null) {
                    editor.remove(key)
                } else {
                    editor.applyValue(key, value)
                }
            }
            editor.apply()
        }.onFailure {
            lastProviderStatus = "snapshot update ${it.javaClass.simpleName}: ${it.message}"
            Log.w(LOG_TAG, "runtime settings snapshot update failed", it)
        }
    }

    private fun cacheRuntimeValue(key: String?, value: Any) {
        if (key == null) return
        var snapshotToPersist: Map<String, Any>? = null
        synchronized(cacheLock) {
            val updated = localCache.toMutableMap()
            updated[key] = value
            localCache = updated
            lastLoadTime = System.currentTimeMillis()
            snapshotToPersist = updated
        }
        snapshotToPersist?.let(::persistHostSnapshot)
    }

    private fun applyProviderOperations(operations: List<PreferenceOperation>) {
        if (operations.isEmpty()) return
        val context = resolveContext() ?: run {
            lastProviderStatus = "provider write context unavailable"
            return
        }
        runCatching {
            operations.forEach { operation ->
                when (operation) {
                    PreferenceOperation.Clear -> Unit
                    is PreferenceOperation.Remove -> {
                        context.contentResolver.call(
                            ModuleSettingsProvider.CONTENT_URI,
                            ModuleSettingsProvider.METHOD_REMOVE,
                            operation.key,
                            null,
                        )
                    }
                    is PreferenceOperation.Put -> {
                        if (operation.value == null) {
                            context.contentResolver.call(
                                ModuleSettingsProvider.CONTENT_URI,
                                ModuleSettingsProvider.METHOD_REMOVE,
                                operation.key,
                                null,
                            )
                        } else {
                            context.putProviderValue(operation.key, operation.value)
                        }
                    }
                }
            }
            lastProviderStatus = "remote+provider ok"
        }.onFailure {
            lastProviderStatus = "provider write ${it.javaClass.simpleName}: ${it.message}"
            if (!context.sendSettingsUpdateBroadcasts(operations)) {
                Log.w(LOG_TAG, "runtime settings provider write failed", it)
            }
        }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val providerOperations = mutableListOf<PreferenceOperation>()
        private val cacheUpdates = mutableListOf<(MutableMap<String, Any>) -> Unit>()
        private val hostSnapshotUpdateKeys = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Put(key, value)
            cacheUpdates += { cache ->
                if (key != null && value != null) cache[key] = value
                else if (key != null) cache.remove(key)
            }
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            val safeValues = safeStringSetOrNull(values)
            if (key != null) providerOperations += PreferenceOperation.Put(key, safeValues)
            cacheUpdates += { cache ->
                if (key != null && safeValues != null) cache[key] = safeValues
                else if (key != null) cache.remove(key)
            }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Put(key, value)
            cacheUpdates += { cache -> if (key != null) cache[key] = value }
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Put(key, value.toString())
            cacheUpdates += { cache -> if (key != null) cache[key] = value.toString() }
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Put(key, value.toString())
            cacheUpdates += { cache -> if (key != null) cache[key] = value.toString() }
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Put(key, value)
            cacheUpdates += { cache -> if (key != null) cache[key] = value }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            recordHostSnapshotUpdate(key)
            if (key != null) providerOperations += PreferenceOperation.Remove(key)
            cacheUpdates += { cache -> if (key != null) cache.remove(key) }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
            providerOperations += PreferenceOperation.Clear
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            ensureLoaded()
            applyProviderOperations(providerOperations)

            var snapshotToPersist: Map<String, Any>? = null
            var snapshotUpdatesToPersist: Map<String, Any?>? = null
            synchronized(cacheLock) {
                val updated = if (clearRequested) mutableMapOf() else localCache.toMutableMap()
                cacheUpdates.forEach { it(updated) }
                localCache = updated
                lastLoadTime = System.currentTimeMillis()
                if (hasAuthoritativeSnapshot) {
                    snapshotToPersist = updated
                } else if (!clearRequested && hostSnapshotUpdateKeys.isNotEmpty()) {
                    snapshotUpdatesToPersist = hostSnapshotUpdateKeys.associateWith { updated[it] }
                }
            }
            snapshotToPersist?.let(::persistHostSnapshot)
            snapshotUpdatesToPersist?.let(::persistHostSnapshotUpdates)

            providerOperations.clear()
            cacheUpdates.clear()
            hostSnapshotUpdateKeys.clear()
            clearRequested = false
        }

        private fun recordHostSnapshotUpdate(key: String?) {
            if (key != null && key in HOST_SNAPSHOT_UPDATE_KEYS) hostSnapshotUpdateKeys += key
        }
    }

    companion object {
        private const val CACHE_EXPIRATION = 5000L
        const val HOST_SNAPSHOT_PREFS_NAME = "bbzq_runtime_settings_snapshot"
        private const val LOG_TAG = "BBZQ"
        private val HOST_SNAPSHOT_UPDATE_KEYS = setOf(
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_SUMMARY,
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_REPORT,
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT,
            ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID,
        )
        private var cachedContext = WeakReference<Context>(null)
        private var cachedXposed: XposedInterface? = null
        private var cachedRemotePrefs = WeakReference<SharedPreferences>(null)
        private val currentApplicationMethod: Method by lazy(LazyThreadSafetyMode.NONE) {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
        }
        @Volatile var lastProviderStatus: String = "not called"
            private set

        fun attach(context: Context, xposed: XposedInterface? = null) {
            cachedContext = WeakReference(context.applicationContext ?: context)
            if (xposed != null) cachedXposed = xposed
            instance.resetTransientState()
        }

        val instance: ModuleSettingsBridge by lazy(LazyThreadSafetyMode.NONE) {
            ModuleSettingsBridge()
        }
    }

    private fun resetTransientState() {
        synchronized(cacheLock) {
            localCache = emptyMap()
            lastLoadTime = 0L
            hasAuthoritativeSnapshot = false
        }
        cachedRemotePrefs = WeakReference(null)
    }

    private data class SettingsSource(
        val values: Map<String, Any?>,
        val authoritative: Boolean,
        val status: String,
        val persistSnapshot: Boolean = true,
    )
}

private fun SharedPreferences.Editor.applyValues(values: Map<String, Any>): SharedPreferences.Editor = apply {
    values.forEach { (key, value) ->
        applyValue(key, value)
    }
}

private fun SharedPreferences.Editor.applyValue(key: String, value: Any): SharedPreferences.Editor = apply {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, safeStringSet(value))
        is List<*> -> putStringSet(key, safeStringSet(value))
        else -> putString(key, value.toString())
    }
}

private fun Context.putProviderValue(key: String, value: Any) {
    val extras = Bundle()
    val method = when (value) {
        is Boolean -> {
            extras.putBoolean(ModuleSettingsProvider.EXTRA_VALUE, value)
            ModuleSettingsProvider.METHOD_PUT_BOOLEAN
        }
        is Int -> {
            extras.putInt(ModuleSettingsProvider.EXTRA_VALUE, value)
            ModuleSettingsProvider.METHOD_PUT_INT
        }
        is String -> {
            extras.putString(ModuleSettingsProvider.EXTRA_VALUE, value)
            ModuleSettingsProvider.METHOD_PUT_STRING
        }
        is Set<*> -> {
            extras.putStringArrayList(ModuleSettingsProvider.EXTRA_VALUE, ArrayList(safeStringSet(value)))
            ModuleSettingsProvider.METHOD_PUT_STRING_SET
        }
        is List<*> -> {
            extras.putStringArrayList(ModuleSettingsProvider.EXTRA_VALUE, ArrayList(safeStringSet(value)))
            ModuleSettingsProvider.METHOD_PUT_STRING_SET
        }
        else -> {
            extras.putString(ModuleSettingsProvider.EXTRA_VALUE, value.toString())
            ModuleSettingsProvider.METHOD_PUT_STRING
        }
    }
    contentResolver.call(ModuleSettingsProvider.CONTENT_URI, method, key, extras)
}

private fun Context.sendSettingsUpdateBroadcasts(operations: List<PreferenceOperation>): Boolean {
    var sent = false
    operations.forEach { operation ->
        val intent = Intent(ModuleSettingsUpdateReceiver.ACTION_UPDATE).apply {
            component = ComponentName(MODULE_PACKAGE, SETTINGS_UPDATE_RECEIVER)
        }
        when (operation) {
            PreferenceOperation.Clear -> return@forEach
            is PreferenceOperation.Remove -> {
                intent.putExtra(ModuleSettingsUpdateReceiver.EXTRA_KEY, operation.key)
                intent.putExtra(ModuleSettingsUpdateReceiver.EXTRA_REMOVE, true)
            }
            is PreferenceOperation.Put -> {
                intent.putExtra(ModuleSettingsUpdateReceiver.EXTRA_KEY, operation.key)
                val value = operation.value
                if (value == null) {
                    intent.putExtra(ModuleSettingsUpdateReceiver.EXTRA_REMOVE, true)
                } else {
                    intent.putPreferenceValue(value)
                }
            }
        }
        runCatching {
            sendBroadcast(intent)
            sent = true
        }.onFailure {
            Log.w("BBZQ", "settings update broadcast send failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
    return sent
}

private fun Intent.putPreferenceValue(value: Any) {
    when (value) {
        is Boolean -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_BOOLEAN)
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_BOOLEAN, value)
        }
        is Int -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_INT)
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_INT, value)
        }
        is Long -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_LONG)
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_LONG, value)
        }
        is Float -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_FLOAT)
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_FLOAT, value)
        }
        is Set<*> -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_STRING_SET)
            putStringArrayListExtra(ModuleSettingsUpdateReceiver.EXTRA_STRING_LIST, ArrayList(safeStringSet(value)))
        }
        is List<*> -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_STRING_SET)
            putStringArrayListExtra(ModuleSettingsUpdateReceiver.EXTRA_STRING_LIST, ArrayList(safeStringSet(value)))
        }
        else -> {
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_TYPE, ModuleSettingsUpdateReceiver.TYPE_STRING)
            putExtra(ModuleSettingsUpdateReceiver.EXTRA_STRING, value.toString())
        }
    }
}

private const val MODULE_PACKAGE = "io.github.bbzq"
private const val SETTINGS_UPDATE_RECEIVER = "io.github.bbzq.ModuleSettingsUpdateReceiver"
