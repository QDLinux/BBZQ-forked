package io.github.bbzq.roaming.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.methodsNamed
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

class VideoDetailBannerAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val videoDetailProxies = IdentityHashMap<Any, Any>()
    private val underPlayerProxies = IdentityHashMap<Any, Any>()
    private var blockedCount = 0

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isBlockVideoDetailBannerAdEnabled(prefs)
        if (!enabled) {
            log("startHook: VideoDetailBannerAd disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val bizKt = G_AD_BIZ_KT.from(classLoader)
        val videoDetailType = G_AD_VIDEO_DETAIL.from(classLoader)
        val underPlayerType = I_AD_UNDER_PLAYER.from(classLoader)
        if (bizKt == null || videoDetailType == null || underPlayerType == null) {
            log(
                "startHook: VideoDetailBannerAd missing " +
                    "bizKt=$bizKt videoDetail=$videoDetailType underPlayer=$underPlayerType",
            )
            return
        }

        val getVideoDetail = bizKt.methodsNamed("getGAdVideoDetail")
            .firstOrNull {
                it.parameterCount == 0 &&
                    Modifier.isStatic(it.modifiers) &&
                    videoDetailType.isAssignableFrom(it.returnType)
            }
        if (getVideoDetail == null) {
            log("startHook: VideoDetailBannerAd no hook point found")
            return
        }

        env.hookAfter(getVideoDetail) { param ->
            val original = param.result ?: return@hookAfter
            if (!videoDetailType.isInstance(original)) return@hookAfter
            param.result = videoDetailProxy(original, videoDetailType, underPlayerType)
        }
        log("startHook: VideoDetailBannerAd at ${getVideoDetail.declaringClass.name}.${getVideoDetail.name}")
    }

    private fun videoDetailProxy(
        original: Any,
        videoDetailType: Class<*>,
        underPlayerType: Class<*>,
    ): Any = synchronized(videoDetailProxies) {
        videoDetailProxies.getOrPut(original) {
            Proxy.newProxyInstance(
                classLoader,
                arrayOf(videoDetailType),
                InvocationHandler { proxy, method, args ->
                    when {
                        method.isObjectMethod("toString", 0) ->
                            "BBZQVideoDetailProxy(${original.javaClass.name})"
                        method.isObjectMethod("hashCode", 0) ->
                            System.identityHashCode(proxy)
                        method.isObjectMethod("equals", 1) ->
                            proxy === args?.firstOrNull()
                        method.name == "getUnderPlayer" && method.parameterCount == 0 -> {
                            val underPlayer = invokeOriginal(original, method, args) ?: return@InvocationHandler null
                            underPlayerProxy(underPlayer, underPlayerType)
                        }
                        else ->
                            invokeOriginal(original, method, args)
                    }
                },
            )
        }
    }

    private fun underPlayerProxy(original: Any, underPlayerType: Class<*>): Any =
        synchronized(underPlayerProxies) {
            underPlayerProxies.getOrPut(original) {
                Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(underPlayerType),
                    InvocationHandler { proxy, method, args ->
                        when {
                            method.isObjectMethod("toString", 0) ->
                                "BBZQUnderPlayerProxy(${original.javaClass.name})"
                            method.isObjectMethod("hashCode", 0) ->
                                System.identityHashCode(proxy)
                            method.isObjectMethod("equals", 1) ->
                                proxy === args?.firstOrNull()
                            method.name in BLOCKED_METHODS -> {
                                logBlocked(method.name)
                                null
                            }
                            else ->
                                invokeOriginal(original, method, args)
                        }
                    },
                )
            }
        }

    private fun invokeOriginal(target: Any, method: Method, args: Array<Any?>?): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (throwable: InvocationTargetException) {
            throw throwable.targetException ?: throwable
        }

    private fun Method.isObjectMethod(name: String, parameterCount: Int): Boolean =
        declaringClass == Any::class.java && this.name == name && this.parameterCount == parameterCount

    private fun logBlocked(methodName: String) {
        val count = ++blockedCount
        if (count <= 20 || count % 20 == 0) {
            log("VideoDetailBannerAd blocked $methodName count=$count")
        }
    }

    private companion object {
        private const val G_AD_BIZ_KT = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
        private const val G_AD_VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
        private const val I_AD_UNDER_PLAYER =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.IAdUnderPlayer"
        private val BLOCKED_METHODS = setOf("getUpperAdView", "getUpperHDView", "getUpperNestView")
    }
}
