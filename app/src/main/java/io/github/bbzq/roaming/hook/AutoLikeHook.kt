package io.github.bbzq.roaming.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.hookAfterMethod

class AutoLikeHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (env.processName != env.packageName) return
        if (!ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
            log("AutoLike disabled")
            return
        }

        env.hookAfterMethod(Activity::class.java, "onResume") { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterMethod

            val name = activity.javaClass.name
            if (!name.contains("VideoDetail")) return@hookAfterMethod

            runCatching {
                autoLike(activity)
            }.onFailure {
                log("AutoLike failed", it)
            }
        }

        log("AutoLikeHook installed")
    }

    private fun autoLike(activity: Activity) {
        val root = activity.window?.decorView ?: return
        val likeView = findLikeView(root) ?: return

        if (likeView.isSelected) return

        likeView.performClick()
        log("AutoLiked video")
    }

    private fun findLikeView(view: View): View? {
        val desc = view.contentDescription?.toString()?.lowercase() ?: ""
        val tag = view.tag?.toString()?.lowercase() ?: ""

        if (
            desc.contains("like") ||
            desc.contains("赞") ||
            tag.contains("like") ||
            tag.contains("digg") ||
            view.javaClass.name.contains("ImageView")
        ) {
            if (view.isClickable) return view
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findLikeView(view.getChildAt(i))
                if (child != null) return child
            }
        }

        return null
    }
}
