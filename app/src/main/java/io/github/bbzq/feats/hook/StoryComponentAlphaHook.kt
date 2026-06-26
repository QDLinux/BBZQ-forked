package io.github.bbzq.feats.hook

import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.min

class StoryComponentAlphaHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        val alpha = ModuleSettings.getStoryVideoComponentAlpha(prefs)
        if (alpha >= 1f) {
            log("startHook: StoryComponentAlpha disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val symbols = env.symbols?.storyComponentAlpha?.restore(classLoader)
        if (symbols == null) {
            log("startHook: StoryComponentAlpha skipped because symbols are unavailable")
            return
        }

        var hookCount = 0
        hookCount += installModuleConstructorHooks(symbols.infoConstructors, alpha, "StoryInfoModule")
        hookCount += installModuleConstructorHooks(symbols.rightConstructors, alpha, "StoryRightModule")
        hookCount += installModuleConstructorHooks(symbols.bottomConstructors, alpha, "StoryBottomModule")
        hookCount += installTopControlsHook(symbols.fragmentOnCreateView, alpha)
        hookCount += installTopControlsAlphaWatcher(alpha)
        log("startHook: StoryComponentAlpha methods=$hookCount alpha=$alpha")
    }

    private fun installModuleConstructorHooks(
        constructors: List<Constructor<*>>,
        alpha: Float,
        label: String,
    ): Int {
        constructors.forEach { constructor ->
            env.hookAfter(constructor) { param ->
                (param.thisObject as? View)?.applyComponentAlpha(alpha)
            }
            log("startHook: StoryComponentAlpha at ${constructor.declaringClass.name}.$label")
        }
        return constructors.size
    }

    private fun installTopControlsHook(method: Method, alpha: Float): Int {
        env.hookAfter(method) { param ->
            val topControls = (param.result as? ViewGroup)?.findStoryTopControls() ?: return@hookAfter
            trackTopControls(topControls)
            topControls
                .applyComponentAlpha(alpha)
        }
        log("startHook: StoryComponentAlpha at ${method.declaringClass.name}.${method.name}")
        return 1
    }

    private fun installTopControlsAlphaWatcher(alpha: Float): Int {
        synchronized(StoryComponentAlphaHook::class.java) {
            if (topControlsAlphaWatcherInstalled) return 0
            topControlsAlphaWatcherInstalled = true
        }
        val method = View::class.java.getDeclaredMethod(
            "setAlpha",
            Float::class.javaPrimitiveType,
        )
        env.hookBefore(method) { param ->
            val view = param.thisObject as? View ?: return@hookBefore
            if (!isTrackedTopControls(view)) return@hookBefore
            val requested = param.args.firstOrNull() as? Float ?: return@hookBefore
            if (requested > alpha) {
                param.args[0] = alpha
            }
        }
        log("startHook: StoryComponentAlpha at android.view.View.setAlpha")
        return 1
    }

    private fun View.applyComponentAlpha(alpha: Float) {
        this.alpha = min(this.alpha, alpha)
    }

    private fun ViewGroup.findStoryTopControls(): View? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.javaClass.name == TOP_CONTROLS_CLASS_NAME) {
                return child
            }
        }
        return null
    }

    private companion object {
        private const val TOP_CONTROLS_CLASS_NAME = "androidx.constraintlayout.widget.ConstraintLayout"
        private val trackedTopControls = WeakHashMap<View, Boolean>()
        private var topControlsAlphaWatcherInstalled = false

        private fun trackTopControls(view: View) {
            synchronized(trackedTopControls) {
                trackedTopControls[view] = true
            }
        }

        private fun isTrackedTopControls(view: View): Boolean =
            synchronized(trackedTopControls) {
                trackedTopControls.containsKey(view)
            }
    }
}
