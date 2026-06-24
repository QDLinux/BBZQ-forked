package io.github.bbzq.feats.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredRewardAdSymbols
import java.lang.reflect.Field
import java.util.Locale

class RewardAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isSkipRewardAdEnabled(prefs)) return
        val symbols = env.symbols?.rewardAd?.restore(classLoader) ?: run {
            log("startHook: RewardAd skipped because symbols are unavailable")
            return
        }

        var count = 0
        count += hookRewardActivity(symbols)
        count += hookRewardHeaderTimer(symbols)
        count += hookCountDownTextView(symbols)
        count += hookActivitySweeper()
        log("startHook: RewardAd, methods=$count")
    }

    private fun hookRewardActivity(symbols: RestoredRewardAdSymbols): Int {
        var count = 0
        symbols.activityOnCreate?.let { method ->
            env.hookAfter(method) { param ->
                scheduleSkipSweep(param.thisObject)
            }
            count++
        }
        symbols.activityOnResume?.let { method ->
            env.hookBefore(method) {
                backdateJumpClock(symbols.jumpClockField)
            }
            count++
        }
        symbols.activityOnResume?.let { method ->
            env.hookAfter(method) { param ->
                scheduleSkipSweep(param.thisObject)
            }
            count++
        }
        symbols.activityOnStop?.let { method ->
            env.hookAfter(method) {
                backdateJumpClock(symbols.jumpClockField)
            }
            count++
        }
        return count
    }

    private fun hookRewardHeaderTimer(symbols: RestoredRewardAdSymbols): Int {
        var count = 0
        symbols.headerSetTotalTime?.let { method ->
            env.hookBefore(method) { param ->
                val total = (param.args.firstOrNull() as? Number)?.toInt() ?: return@hookBefore
                if (total > 1) param.args[0] = 1
            }
            count++
        }
        symbols.headerSetElapsedTime?.let { method ->
            env.hookBefore(method) { param ->
                val elapsed = (param.args.firstOrNull() as? Number)?.toLong() ?: return@hookBefore
                if (elapsed < REWARD_FAST_FORWARD_MS) param.args[0] = REWARD_FAST_FORWARD_MS
            }
            count++
        }
        symbols.headerStartTimer?.let { method ->
            env.hookBefore(method) { param ->
                invokeSetElapsedTime(param.thisObject, REWARD_FAST_FORWARD_MS)
            }
            count++
        }
        return count
    }

    private fun hookCountDownTextView(symbols: RestoredRewardAdSymbols): Int {
        var count = 0
        symbols.countDownSetTotalTime?.let { method ->
            env.hookBefore(method) { param ->
                val total = (param.args.firstOrNull() as? Number)?.toInt() ?: return@hookBefore
                if (total > 1) param.args[0] = 1
            }
            count++
        }
        symbols.countDownSetElapsedTime?.let { method ->
            env.hookBefore(method) { param ->
                val elapsed = (param.args.firstOrNull() as? Number)?.toLong() ?: return@hookBefore
                if (elapsed < REWARD_FAST_FORWARD_MS) param.args[0] = REWARD_FAST_FORWARD_MS
            }
            count++
        }
        return count
    }

    private fun hookActivitySweeper(): Int {
        val onResume = runCatching {
            Activity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
        }.getOrNull() ?: return 0
        env.hookAfter(onResume) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfter
            if (shouldSweepActivity(activity.javaClass.name)) {
                scheduleSkipSweep(activity)
            }
        }
        return 1
    }

    private fun backdateJumpClock(jumpClockField: Field?) {
        if (jumpClockField == null) return
        runCatching {
            jumpClockField.set(null, System.currentTimeMillis() - JUMP_FAST_FORWARD_MS)
        }
    }

    private fun invokeSetElapsedTime(target: Any?, elapsedMs: Long) {
        if (target == null) return
        runCatching {
            target.javaClass.getMethod("setElapsedTime", Long::class.javaPrimitiveType!!).invoke(target, elapsedMs)
        }
    }

    private fun scheduleSkipSweep(target: Any?) {
        val activity = target as? Activity ?: return
        val decor = activity.window?.decorView ?: return
        SWEEP_DELAYS_MS.forEach { delay ->
            decor.postDelayed({ clickFirstCandidate(activity, decor, intArrayOf(0)) }, delay)
        }
    }

    private fun clickFirstCandidate(activity: Activity, view: View?, count: IntArray): Boolean {
        if (view == null || count[0]++ > MAX_VIEW_SCAN_NODES || !view.isShown) return false

        if (isClickCandidate(activity, view) && performClick(view)) return true
        if (view !is ViewGroup) return false

        for (index in view.childCount - 1 downTo 0) {
            if (clickFirstCandidate(activity, view.getChildAt(index), count)) return true
        }
        return false
    }

    private fun isClickCandidate(activity: Activity, view: View): Boolean {
        val text = (view as? TextView)?.text
        return shouldClickText(activity, text) || shouldClickText(activity, view.contentDescription)
    }

    private fun shouldClickText(activity: Activity, rawText: CharSequence?): Boolean {
        val compact = rawText?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replace(" ", "")
            ?.replace("\n", "")
            ?: return false
        if (compact.contains("跳过") ||
            compact.contains("领取奖励") ||
            compact.contains("立即领取") ||
            compact.contains("已获得奖励")
        ) {
            return true
        }
        if (isRewardActivity(activity)) return false
        return compact.contains("关闭广告") || compact == "关闭"
    }

    private fun performClick(view: View): Boolean {
        if (!view.isEnabled) return false
        if (view.isClickable || view.hasOnClickListeners()) return view.performClick()
        val parent = view.parent as? View ?: return false
        return parent.isShown &&
            parent.isEnabled &&
            (parent.isClickable || parent.hasOnClickListeners()) &&
            parent.performClick()
    }

    private fun shouldSweepActivity(className: String): Boolean {
        val lower = className.lowercase(Locale.ROOT)
        return lower.contains(".ad.") ||
            lower.contains(".adview.") ||
            lower.contains(".reward.") ||
            lower.contains(".splash.") ||
            lower.contains("adactivity") ||
            lower.contains("splash")
    }

    private fun isRewardActivity(activity: Activity): Boolean =
        activity.javaClass.name.startsWith("com.bilibili.ad.reward.")

    private companion object {
        private const val REWARD_FAST_FORWARD_MS = 60_000L
        private const val JUMP_FAST_FORWARD_MS = 60_000L
        private const val MAX_VIEW_SCAN_NODES = 320
        private val SWEEP_DELAYS_MS = longArrayOf(0L, 250L, 800L, 1_500L)
    }
}
