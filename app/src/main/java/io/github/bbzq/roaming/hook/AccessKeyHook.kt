package io.github.bbzq.roaming.hook

import android.content.Context
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.callMethod
import io.github.bbzq.roaming.callStaticMethod
import io.github.bbzq.roaming.from

class AccessKeyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        // 仅在主进程中运行
        if (env.processName != env.packageName) return
        
        AccessKeyRepository.register {
            runCatching { getAccessKey() }.getOrNull()
        }
        log("AccessKeyHook installed")
    }

    private fun getAccessKey(): String? {
        val biliAccountsClass = findBiliAccountsClass() ?: return null
        val context = env.hostContext
        
        // 尝试获取当前账号实例
        val account = biliAccountsClass.callStaticMethod("get", context) 
            ?: biliAccountsClass.callStaticMethod("a", context)
            ?: return null
            
        // 尝试调用 getAccessKey 方法
        return (account.callMethod("getAccessKey") as? String)
            ?: (account.callMethod("a") as? String) // 常见混淆名
    }

    private fun findBiliAccountsClass(): Class<*>? {
        // 如果硬编码的类名无法找到，可能需要使用 dexHelper 搜索字符串 "initFacial enter" 来定位该类
        return "com.bilibili.lib.accounts.BiliAccounts".from(classLoader)
            ?: "com.bilibili.p4439app.accounts.BiliAccounts".from(classLoader)
            ?: "com.bilibili.app.accounts.BiliAccounts".from(classLoader)
    }
}
