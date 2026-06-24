package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore

class TryFreeQualityHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        if (!ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) {
            log("startHook: TryFreeQuality disabled")
            return
        }
        val symbols = env.symbols?.tryFreeQuality?.restore(classLoader) ?: run {
            log("startHook: TryFreeQuality skipped because symbols are unavailable")
            return
        }

        var methods = 0
        symbols.getIsNeedTrialMethods.forEach { method ->
            env.hookBefore(method) { param ->
                param.result = true
            }
            methods++
        }
        symbols.setIsNeedTrialMethods.forEach { method ->
            env.hookBefore(method) { param ->
                if (param.args.isNotEmpty()) {
                    param.args[0] = true
                }
            }
            methods++
        }
        symbols.getVipFreeMethods.forEach { method ->
            env.hookAfter(method) { param ->
                val needVip = (param.thisObject?.getObjectField("needVip_") as? Boolean) ?: return@hookAfter
                param.result = needVip
            }
            methods++
        }
        symbols.getNeedVipMethods.forEach { method ->
            env.hookBefore(method) { param ->
                param.result = false
            }
            methods++
        }
        log("startHook: TryFreeQuality, methods=$methods")
    }
}
