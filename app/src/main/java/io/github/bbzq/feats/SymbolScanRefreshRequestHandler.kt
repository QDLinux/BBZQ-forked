package io.github.bbzq.feats

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.symbol.BiliSymbolResolver
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object SymbolScanRefreshRequestHandler {
    private val installedProcesses = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicBoolean(false)
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(
        env: RoamingEnv,
        xposed: XposedInterface,
        classLoader: ClassLoader,
    ) {
        val processKey = "${env.packageName}/${env.processName}"
        if (!installedProcesses.add(processKey)) return

        val remotePrefs = runCatching {
            xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
        }.getOrElse {
            env.log("Symbol scan refresh request listener unavailable", it)
            return
        }
        val handledPrefs = env.hostContext.getSharedPreferences(
            ModuleSettingsBridge.HOST_SNAPSHOT_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val lastHandled = AtomicReference(
            runCatching {
                remotePrefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID, null)
            }.getOrNull()
                ?: handledPrefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID, null),
        )

        fun handlePendingRequest(trigger: String) {
            val requestId = runCatching {
                remotePrefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_REQUEST_ID, null)
            }.getOrElse {
                env.log("Symbol scan refresh request read failed trigger=$trigger", it)
                return
            }
                ?.takeIf { it.isNotBlank() }
                ?: return
            if (requestId == lastHandled.get()) return
            if (!running.compareAndSet(false, true)) {
                env.log("Symbol scan refresh request ignored while scan is running trigger=$trigger")
                return
            }
            handledPrefs.edit()
                .putString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID, requestId)
                .commit()
            lastHandled.set(requestId)
            thread(name = "BBZQ-SymbolRefresh", isDaemon = true) {
                try {
                    env.log("Symbol scan refresh request accepted id=$requestId trigger=$trigger")
                    ModuleSettingsBridge.attach(env.hostContext, xposed)
                    ModuleSettingsBridge.instance.edit()
                        .putString(ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_HANDLED_ID, requestId)
                        .commit()
                    env.symbols = BiliSymbolResolver.forceRefresh(
                        hostContext = env.hostContext,
                        classLoader = classLoader,
                        log = env::log,
                    )
                    env.log("Symbol scan refresh request completed id=$requestId")
                    restartHostApp(env, requestId)
                } catch (throwable: Throwable) {
                    env.log("Symbol scan refresh request failed id=$requestId", throwable)
                } finally {
                    running.set(false)
                }
            }
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ModuleSettings.KEY_SYMBOL_SCAN_REFRESH_REQUEST_ID) {
                handlePendingRequest("remote-pref")
            }
        }
        synchronized(listeners) {
            listeners += listener
        }
        remotePrefs.registerOnSharedPreferenceChangeListener(listener)
        handlePendingRequest("install")
        pollPendingRequests(::handlePendingRequest)
        env.log("Symbol scan refresh request listener installed")
    }

    private fun pollPendingRequests(handlePendingRequest: (String) -> Unit) {
        mainHandler.postDelayed(
            {
                handlePendingRequest("poll")
                pollPendingRequests(handlePendingRequest)
            },
            REQUEST_POLL_MS,
        )
    }

    private fun restartHostApp(env: RoamingEnv, requestId: String) {
        mainHandler.postDelayed(
            {
                try {
                    val restartIntent = env.hostContext.packageManager
                        .getLaunchIntentForPackage(env.packageName)
                        ?.apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    if (restartIntent == null) {
                        env.log("Symbol scan refresh restart skipped id=$requestId: launch intent unavailable")
                        return@postDelayed
                    }
                    env.log("Symbol scan refresh restarting host id=$requestId")
                    env.hostContext.startActivity(restartIntent)
                } catch (throwable: Throwable) {
                    env.log("Symbol scan refresh restart launch failed id=$requestId", throwable)
                    return@postDelayed
                }

                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (throwable: Throwable) {
                    env.log("Symbol scan refresh kill process failed id=$requestId", throwable)
                }
                try {
                    exitProcess(0)
                } catch (throwable: Throwable) {
                    env.log("Symbol scan refresh exit process failed id=$requestId", throwable)
                }
            },
            HOST_RESTART_DELAY_MS,
        )
    }

    private const val HOST_RESTART_DELAY_MS = 1_000L
    private const val REQUEST_POLL_MS = 1_500L
}
