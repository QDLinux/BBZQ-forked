package io.github.bbzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

class ModuleSettingsUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE) return
        if (!isAllowedCaller(context)) return

        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it in ALLOWED_KEYS } ?: return
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        runCatching {
            val editor = prefs.edit()
            if (intent.getBooleanExtra(EXTRA_REMOVE, false)) {
                editor.remove(key)
            } else {
                editor.applyIntentValue(key, intent)
            }
            editor.apply()
        }.onFailure {
            Log.w(LOG_TAG, "settings update broadcast failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun SharedPreferences.Editor.applyIntentValue(
        key: String,
        intent: Intent,
    ): SharedPreferences.Editor = apply {
        when (intent.getStringExtra(EXTRA_TYPE)) {
            TYPE_BOOLEAN -> putBoolean(key, intent.getBooleanExtra(EXTRA_BOOLEAN, false))
            TYPE_INT -> putInt(key, intent.getIntExtra(EXTRA_INT, 0))
            TYPE_LONG -> putLong(key, intent.getLongExtra(EXTRA_LONG, 0L))
            TYPE_FLOAT -> putFloat(key, intent.getFloatExtra(EXTRA_FLOAT, 0f))
            TYPE_STRING_SET -> putStringSet(
                key,
                safeStringSet(intent.getStringArrayListExtra(EXTRA_STRING_LIST).orEmpty()),
            )
            else -> putString(key, intent.getStringExtra(EXTRA_STRING))
        }
    }

    private fun isAllowedCaller(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val uid = sentFromUid
        if (uid < 0) return false
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        return packages.any { it == context.packageName || it in ALLOWED_CLIENT_PACKAGES }
    }

    companion object {
        const val ACTION_UPDATE = "io.github.bbzq.action.UPDATE_SETTINGS"
        const val EXTRA_KEY = "key"
        const val EXTRA_TYPE = "type"
        const val EXTRA_REMOVE = "remove"
        const val EXTRA_BOOLEAN = "boolean"
        const val EXTRA_INT = "int"
        const val EXTRA_LONG = "long"
        const val EXTRA_FLOAT = "float"
        const val EXTRA_STRING = "string"
        const val EXTRA_STRING_LIST = "string_list"

        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_STRING = "string"
        const val TYPE_STRING_SET = "string_set"

        private const val LOG_TAG = "BBZQ"

        private val ALLOWED_KEYS = setOf(
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_SUMMARY,
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_REPORT,
            ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT,
            ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID,
            ModuleSettings.KEY_KNOWN_BOTTOM_BAR_ITEMS,
            ModuleSettings.KEY_KNOWN_HOME_RECOMMEND_TABS,
            ModuleSettings.KEY_KNOWN_HOME_COMPONENTS,
        )

        private val ALLOWED_CLIENT_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.blue",
        )
    }
}
