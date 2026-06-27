package io.github.bbzq

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ModuleRemotePreferences : XposedServiceHelper.OnServiceListener {
    private const val TAG = "BBZQ"

    private val registered = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var service: XposedService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        if (registered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(this)
        }
    }

    fun attach(context: Context, prefs: SharedPreferences) {
        init(context)
        syncSnapshot(prefs)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        appContext?.let { context ->
            syncSnapshot(context.moduleSettingsPreferences())
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
    }

    fun syncSnapshot(prefs: SharedPreferences) {
        val values = prefs.all
        withRemoteEditor { editor ->
            editor.clear()
            values.forEach { (key, value) -> editor.putValue(key, value) }
        }
    }

    fun applyOperations(operations: List<PreferenceOperation>) {
        if (operations.isEmpty()) return
        withRemoteEditor { editor ->
            operations.forEach { operation ->
                when (operation) {
                    PreferenceOperation.Clear -> editor.clear()
                    is PreferenceOperation.Remove -> editor.remove(operation.key)
                    is PreferenceOperation.Put -> editor.putValue(operation.key, operation.value)
                }
            }
        }
    }

    fun requestSymbolCacheRefresh(callback: (String) -> Unit) {
        requestSymbolCacheRefresh(callback, serviceWaitAttempt = 0)
    }

    private fun requestSymbolCacheRefresh(
        callback: (String) -> Unit,
        serviceWaitAttempt: Int,
    ) {
        init(appContext ?: run {
            fail(callback, "Xposed 服务尚未连接")
            return
        })
        val currentService = service ?: run {
            if (serviceWaitAttempt < SERVICE_WAIT_MAX_ATTEMPTS) {
                mainHandler.postDelayed(
                    { requestSymbolCacheRefresh(callback, serviceWaitAttempt + 1) },
                    SERVICE_WAIT_RETRY_MS,
                )
            } else {
                fail(callback, "Xposed 服务尚未连接")
            }
            return
        }
        val context = appContext ?: run {
            fail(callback, "Xposed 服务尚未连接")
            return
        }
        thread(name = SYMBOL_REFRESH_THREAD_NAME, isDaemon = true) {
            runCatching {
                if (currentService.apiVersion < XposedService.API_102) {
                    fail(callback, "当前框架不支持 API102 远程配置")
                    return@thread
                }
                val requestId = UUID.randomUUID().toString()
                val beforeUpdatedAt = context.moduleSettingsPreferences()
                    .getString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT, null)
                val completed = AtomicBoolean(false)
                submitSymbolScanRequest(currentService, requestId)
                pollSymbolScanResult(
                    context = context,
                    beforeUpdatedAt = beforeUpdatedAt,
                    startedAt = System.currentTimeMillis(),
                    completed = completed,
                    callback = callback,
                    attempt = 0,
                )
                Log.i(TAG, "symbol cache refresh waits for runtime request id=$requestId")
            }.onFailure {
                fail(callback, "请求重扫失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun fail(
        callback: (String) -> Unit,
        message: String,
    ) {
        Log.w(TAG, message)
        dispatch(callback, message)
    }

    private fun submitSymbolScanRequest(
        service: XposedService,
        requestId: String,
    ) {
        val editor = service.getRemotePreferences(ModuleSettings.PREFS_NAME).edit()
        editor.putString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_REQUEST_ID, requestId)
        check(editor.commit()) { "remote preference commit failed" }
        Log.i(TAG, "symbol cache refresh request submitted id=$requestId")
    }

    private fun pollSymbolScanResult(
        context: Context,
        beforeUpdatedAt: String?,
        startedAt: Long,
        completed: AtomicBoolean,
        callback: (String) -> Unit,
        attempt: Int,
    ) {
        if (completed.get()) return
        val updatedAt = context.moduleSettingsPreferences()
            .getString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT, null)
        val updatedAtMillis = updatedAt?.toLongOrNull() ?: 0L
        if (updatedAt != null && updatedAt != beforeUpdatedAt && updatedAtMillis >= startedAt - 1_000L) {
            if (completed.compareAndSet(false, true)) {
                dispatch(callback, context.getString(R.string.symbol_cache_refresh_complete_restart_toast))
            }
            return
        }
        if (attempt >= SYMBOL_SCAN_RESULT_MAX_POLL_ATTEMPTS) {
            if (completed.compareAndSet(false, true)) {
                dispatch(callback, "已发送重扫请求，但等待扫描结果超时。请重新进入 BBZQ 设置查看最新状态")
            }
            return
        }
        mainHandler.postDelayed(
            {
                pollSymbolScanResult(
                    context = context,
                    beforeUpdatedAt = beforeUpdatedAt,
                    startedAt = startedAt,
                    completed = completed,
                    callback = callback,
                    attempt = attempt + 1,
                )
            },
            SYMBOL_SCAN_RESULT_POLL_MS,
        )
    }

    private fun dispatch(
        callback: (String) -> Unit,
        message: String,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(message)
        } else {
            mainHandler.post { callback(message) }
        }
    }

    private fun withRemoteEditor(block: (SharedPreferences.Editor) -> Unit) {
        val currentService = service ?: return
        runCatching {
            val editor = currentService.getRemotePreferences(ModuleSettings.PREFS_NAME).edit()
            block(editor)
            editor.commit()
        }.onFailure {
            Log.w(TAG, "sync remote preferences failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
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

    private fun Context.moduleSettingsPreferences(): SharedPreferences =
        getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)

    private const val SYMBOL_REFRESH_THREAD_NAME = "BBZQ-SymbolRefresh"
    private const val SERVICE_WAIT_RETRY_MS = 500L
    private const val SERVICE_WAIT_MAX_ATTEMPTS = 10
    private const val SYMBOL_SCAN_RESULT_POLL_MS = 500L
    private const val SYMBOL_SCAN_RESULT_MAX_POLL_ATTEMPTS = 120
}

sealed interface PreferenceOperation {
    data object Clear : PreferenceOperation
    data class Remove(val key: String) : PreferenceOperation
    data class Put(val key: String, val value: Any?) : PreferenceOperation
}
