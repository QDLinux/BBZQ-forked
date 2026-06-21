package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class FreeCopyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        ensureActivityTracking()
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        env.hookBefore(method) { param ->
            if (!isRemoveDirectCopyEnabled()) return@hookBefore
            if (allowOriginalCopy.get() == true) return@hookBefore

            val clip = param.args.firstOrNull() as? ClipData ?: return@hookBefore
            val text = extractText(clip) ?: return@hookBefore
            if (shouldBypassCopy(text)) return@hookBefore

            if (isEnhancedCopyEnabled()) {
                showCopyDialog(text, clip)
            }
            param.result = null
        }
        log("startHook: FreeCopy, methods=1")
    }

    private fun ensureActivityTracking() {
        val application = env.hostContext as? Application ?: return
        if (!callbacksRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    topActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (topActivity?.get() === activity) {
                        topActivity = null
                    }
                }
            },
        )
    }

    private fun isRemoveDirectCopyEnabled(): Boolean =
        ModuleSettings.isDisableLongPressCopyEnabled(prefs)

    private fun isEnhancedCopyEnabled(): Boolean =
        isRemoveDirectCopyEnabled() && ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)

    private fun extractText(clip: ClipData): CharSequence? =
        clip.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(env.hostContext)
            ?.takeIf { it.isNotBlank() }

    private fun shouldBypassCopy(text: CharSequence): Boolean {
        val value = text.toString().trim()
        if (value.matches(BV_ID_REGEX)) return true
        if (isLikelyShareText(value)) return true

        val stackTrace = Throwable().stackTrace
        return stackTrace.any { it.isShareCopyFrame() || it.isModuleOwnCopyFrame() }
    }

    private fun isLikelyShareText(value: String): Boolean =
        (value.startsWith("http://") || value.startsWith("https://")) &&
            SHARE_LINK_HOSTS.any { value.contains(it, ignoreCase = true) }

    private fun showCopyDialog(text: CharSequence, clip: ClipData) {
        val activity = topActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            log("FreeCopy skipped dialog because no foreground activity is available")
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val themeId = activity.resources.getIdentifier("AppTheme.Dialog.Alert", "style", activity.packageName)
            val builder = if (themeId != 0) {
                AlertDialog.Builder(activity, themeId)
            } else {
                AlertDialog.Builder(activity)
            }

            val dialog = builder
                .setTitle(R.string.free_copy_title)
                .setMessage(text)
                .setPositiveButton(R.string.free_copy_original_button) { _, _ ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    runWithOriginalCopy {
                        clipboard.setPrimaryClip(clip)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }

    private fun runWithOriginalCopy(block: () -> Unit) {
        allowOriginalCopy.set(true)
        try {
            block()
        } finally {
            allowOriginalCopy.remove()
        }
    }

    private companion object {
        private val callbacksRegistered = AtomicBoolean(false)
        private val allowOriginalCopy = ThreadLocal<Boolean>()
        private val BV_ID_REGEX = Regex("""BV[a-zA-Z0-9]{10}""")
        private val SHARE_LINK_HOSTS = listOf("bilibili.com", "b23.tv", "bili2233.cn")

        @Volatile
        private var topActivity: WeakReference<Activity>? = null

        private fun StackTraceElement.isShareCopyFrame(): Boolean =
            className.contains(".share.", ignoreCase = true) ||
                className.contains("sharewrapper", ignoreCase = true) ||
                className.contains("p7848copy", ignoreCase = true)

        private fun StackTraceElement.isModuleOwnCopyFrame(): Boolean =
            className.startsWith("io.github.bbzq.SettingsContentFactory") ||
                className.startsWith("io.github.bbzq.SettingsActivity")
    }
}

