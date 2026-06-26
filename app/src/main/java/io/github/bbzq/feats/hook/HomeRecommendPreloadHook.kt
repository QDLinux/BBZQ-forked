package io.github.bbzq.feats.hook

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

class HomeRecommendPreloadHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val homeRecyclerViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val baseDistanceByListener = WeakHashMap<Any, Int>()
    private val nextDistanceCheckAt = WeakHashMap<Any, Long>()
    private var fieldFailureLogged = false
    private var scrollStateFailureLogged = false

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isHomeRecommendPreloadEnabled(prefs)
        if (!enabled) {
            log("startHook: HomeRecommendPreload disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val symbols = env.symbols?.homeRecommendPreload?.restore(classLoader)
        if (symbols == null) {
            log("startHook: HomeRecommendPreload skipped because symbols are unavailable")
            return
        }
        val getScrollStateMethod = symbols.recyclerViewClass.methods.firstOrNull {
            it.name == "getScrollState" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }

        env.hookAfter(symbols.fragmentOnViewCreated) { param ->
            val root = param.args.firstOrNull() as? View ?: return@hookAfter
            trackHomeRecyclerViews(root, symbols.recyclerViewClass)
        }
        env.hookBefore(symbols.loadMoreCheckMethod) { param ->
            val recyclerView = param.args.firstOrNull() as? View ?: return@hookBefore
            if (!homeRecyclerViews.contains(recyclerView)) return@hookBefore
            val listener = param.thisObject ?: return@hookBefore
            adjustPreloadDistance(
                listener = listener,
                field = symbols.prefetchDistanceField,
                settling = isSettling(recyclerView, getScrollStateMethod),
            )
        }
        log(
            "startHook: HomeRecommendPreload at " +
                "${symbols.fragmentOnViewCreated.declaringClass.name}.${symbols.fragmentOnViewCreated.name}, " +
                "${symbols.loadMoreCheckMethod.declaringClass.name}.${symbols.loadMoreCheckMethod.name}",
        )
    }

    private fun trackHomeRecyclerViews(view: View, recyclerViewClass: Class<*>) {
        if (recyclerViewClass.isInstance(view)) {
            homeRecyclerViews.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                trackHomeRecyclerViews(view.getChildAt(i), recyclerViewClass)
            }
        }
    }

    private fun isSettling(recyclerView: View, getScrollStateMethod: Method?): Boolean {
        val method = getScrollStateMethod ?: return false
        return try {
            method.invoke(recyclerView) == SCROLL_STATE_SETTLING
        } catch (t: Throwable) {
            if (!scrollStateFailureLogged) {
                scrollStateFailureLogged = true
                log("HomeRecommendPreload failed to read RecyclerView scroll state", t)
            }
            false
        }
    }

    private fun adjustPreloadDistance(listener: Any, field: Field, settling: Boolean) {
        val now = SystemClock.uptimeMillis()
        val nextCheckAt = nextDistanceCheckAt[listener] ?: 0L
        if (!settling && now < nextCheckAt) return

        try {
            val current = field.getInt(listener)
            if (current in 1 until PRELOAD_DISTANCE_ROWS) {
                baseDistanceByListener[listener] = current
            }

            if (settling) {
                val baseDistance = baseDistanceByListener[listener] ?: HOST_DISTANCE_ROWS
                if (current > baseDistance) {
                    field.setInt(listener, baseDistance)
                }
                nextDistanceCheckAt.remove(listener)
            } else {
                if (current < PRELOAD_DISTANCE_ROWS) {
                    field.setInt(listener, PRELOAD_DISTANCE_ROWS)
                }
                nextDistanceCheckAt[listener] = now + DISTANCE_CHECK_INTERVAL_MS
            }
        } catch (t: Throwable) {
            if (!fieldFailureLogged) {
                fieldFailureLogged = true
                log("HomeRecommendPreload failed to update preload distance", t)
            }
        }
    }

    private companion object {
        private const val PRELOAD_DISTANCE_ROWS = 8
        private const val HOST_DISTANCE_ROWS = 1
        private const val SCROLL_STATE_SETTLING = 2
        private const val DISTANCE_CHECK_INTERVAL_MS = 1_000L
    }
}
