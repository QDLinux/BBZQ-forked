package io.github.bbzq.roaming.hook

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.BilibiliSponsorBlock
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.allMethods
import io.github.bbzq.roaming.callMethod
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.hookBeforeMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale

class SkipVideoAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    @Volatile private var lastSeekTime = 0L
    @Volatile private var duration = -1
    @Volatile private var segments: List<BilibiliSponsorBlock.Segment>? = null
    @Volatile private var segmentsKey = ""
    @Volatile private var loadingSegments = false
    @Volatile private var bvid = ""
    @Volatile private var cid = ""

    private var waitTime = 1000L
    private var playerRef: WeakReference<Any>? = null
    private val player: Any?
        get() = playerRef?.get()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun startHook() {
        if (!ModuleSettings.isSkipVideoAdEnabled(prefs)) return

        val count = hookPlayViewUnite() + hookPlayerCoreService()
        log("startHook: SkipVideoAd, methods=$count")
    }

    private fun hookPlayViewUnite(): Int {
        val playerMoss = PLAYER_MOSS.from(classLoader) ?: return 0
        val playViewUniteReq = PLAY_VIEW_UNITE_REQ.from(classLoader) ?: return 0
        val mossResponseHandler = MOSS_RESPONSE_HANDLER.from(classLoader)

        var count = runCatching {
            env.hookBeforeMethod(playerMoss, "executePlayViewUnite", playViewUniteReq) { param ->
                updateVideoIdentityFromRequest(param.args.firstOrNull())
            }
        }.getOrElse {
            log("SkipVideoAd failed to hook executePlayViewUnite", it)
            0
        }

        if (mossResponseHandler != null) {
            count += runCatching {
                env.hookBeforeMethod(playerMoss, "playViewUnite", playViewUniteReq, mossResponseHandler) { param ->
                    updateVideoIdentityFromRequest(param.args.firstOrNull())
                    val handler = param.args.getOrNull(1) ?: return@hookBeforeMethod
                    param.args[1] = wrapMossResponseHandler(handler, mossResponseHandler)
                }
            }.getOrElse {
                log("SkipVideoAd failed to hook playViewUnite", it)
                0
            }
        }

        return count
    }

    private fun wrapMossResponseHandler(handler: Any, handlerClass: Class<*>): Any {
        return Proxy.newProxyInstance(
            handler.javaClass.classLoader,
            arrayOf(handlerClass),
        ) { _, method, args ->
            if (method.name == "onNext") {
                updateVideoIdentityFromReply(args?.firstOrNull())
            }
            if (args == null) method.invoke(handler) else method.invoke(handler, *args)
        }
    }

    private fun updateVideoIdentityFromRequest(req: Any?) {
        if (req == null) return
        var nextBvid = req.callMethod("getBvid") as? String ?: ""
        val vod = req.callMethod("getVod") ?: return
        if (nextBvid.isEmpty()) {
            val aid = vod.callMethod("getAid").asLong() ?: return
            if (aid == -1L) return
            nextBvid = av2bv(aid)
        }
        val nextCid = vod.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(nextBvid, nextCid)
    }

    private fun updateVideoIdentityFromReply(reply: Any?) {
        val playArc = reply?.callMethod("getPlayArc") ?: return
        val aid = playArc.callMethod("getAid").asLong() ?: return
        if (aid == -1L) return
        val nextCid = playArc.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(av2bv(aid), nextCid)
    }

    private fun updateVideoIdentity(nextBvid: String, nextCid: String) {
        if (nextBvid.isBlank() || nextCid.isBlank()) return
        if (nextBvid == bvid && nextCid == cid) return

        bvid = nextBvid
        cid = nextCid
        duration = -1
        segments = null
        segmentsKey = ""
        loadingSegments = false
        waitTime = 1000L
        fetchSegmentsIfNeeded()
    }

    private fun hookPlayerCoreService(): Int {
        var count = 0
        findPlayerCoreServiceClasses().forEach { type ->
            count += hookCurrentPosition(type)
            count += hookPlayerState(type)
        }
        return count
    }

    private fun hookCurrentPosition(type: Class<*>): Int {
        val methods = type.allMethods()
            .filter {
                it.name == "getCurrentPosition" &&
                    it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    val service = param.thisObject ?: return@hookAfter
                    playerRef = WeakReference(service)
                    if (duration <= 0) {
                        duration = service.callMethod("getDuration").asInt() ?: -1
                    }
                    fetchSegmentsIfNeeded()
                    val position = param.result.asInt() ?: return@hookAfter
                    val now = System.currentTimeMillis()
                    if (now - lastSeekTime > waitTime) {
                        lastSeekTime = now
                        waitTime = if (seekTo(position)) 3000L else 1000L
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun hookPlayerState(type: Class<*>): Int {
        val methods = type.allMethods()
            .filter {
                it.name == "G1" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    val service = param.thisObject ?: return@hookAfter
                    playerRef = WeakReference(service)
                    val state = param.args.firstOrNull().asInt() ?: return@hookAfter
                    if (state in 3..5 && duration <= 0) {
                        duration = service.callMethod("getDuration").asInt() ?: -1
                    }
                    if (state == 2) {
                        duration = -1
                        segments = null
                        segmentsKey = ""
                        fetchSegmentsIfNeeded()
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun findPlayerCoreServiceClasses(): List<Class<*>> {
        val serviceInterface = PLAYER_CORE_SERVICE_INTERFACE.from(classLoader)
        val candidates = linkedSetOf<Class<*>>()

        serviceInterface?.let { candidates += it }
        PLAYER_CORE_SERVICE_CANDIDATES.mapNotNullTo(candidates) { it.from(classLoader) }

        dexClassNames()
            .filter(::mightBePlayerCoreServiceName)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .filter { it.isPlayerCoreService(serviceInterface) }
            .forEach { candidates += it }

        return candidates.distinctBy { it.name }
    }

    private fun dexClassNames(): Sequence<String> = sequence {
        val baseDexClassLoader = classLoader as? BaseDexClassLoader ?: return@sequence
        val pathList = baseDexClassLoader.getObjectField("pathList") ?: return@sequence
        val dexElements = pathList.getObjectField("dexElements") as? Array<*> ?: return@sequence
        dexElements.forEach { element ->
            val dexFile = element?.getObjectField("dexFile") as? DexFile ?: return@forEach
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        }
    }

    private fun mightBePlayerCoreServiceName(name: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        return "player" in lowerName &&
            "service" in lowerName &&
            ("core" in lowerName || "playerv2" in lowerName)
    }

    private fun Class<*>.isPlayerCoreService(serviceInterface: Class<*>?): Boolean {
        if (isInterface || Modifier.isAbstract(modifiers)) return false
        if (serviceInterface?.isAssignableFrom(this) == true) return true
        return hasNoArgIntMethod("getCurrentPosition") &&
            hasNoArgIntMethod("getDuration") &&
            allMethods().any { it.isSeekToMethod() }
    }

    private fun Class<*>.hasNoArgIntMethod(name: String): Boolean =
        allMethods().any {
            it.name == name &&
                it.parameterCount == 0 &&
                it.returnType == Int::class.javaPrimitiveType
        }

    private fun fetchSegmentsIfNeeded() {
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isBlank() || currentCid.isBlank()) return

        val key = "$currentBvid/$currentCid"
        if (loadingSegments || segmentsKey == key) return

        loadingSegments = true
        segmentsKey = key
        Thread {
            var loaded: List<BilibiliSponsorBlock.Segment>? = null
            for (attempt in 0 until 3) {
                loaded = BilibiliSponsorBlock(currentBvid, currentCid).getSegments()
                if (!loaded.isNullOrEmpty()) break
                if (attempt < 2) Thread.sleep(1000)
            }

            if (key == videoKey()) {
                segments = loaded
                if (loaded == null) {
                    toast("\u5e7f\u544a\u7247\u6bb5\u6570\u636e\u83b7\u53d6\u5931\u8d25")
                }
            }
            loadingSegments = false
        }.apply {
            name = "BBZQ-SkipVideoAd"
            isDaemon = true
            start()
        }
    }

    private fun videoKey(): String = "$bvid/$cid"

    private fun seekTo(position: Int): Boolean {
        val videoDuration = duration
        if (videoDuration > 0 && position > videoDuration) return false

        segments.orEmpty().forEach { segment ->
            val start = (segment.segment[0] * 1000).toInt()
            val end = (segment.segment[1] * 1000).toInt()
            if (position in start until end) {
                toast("\u5df2\u8df3\u8fc7\u5e7f\u544a\u7247\u6bb5")
                seekPlayerTo(end)
                return true
            }
        }
        return false
    }

    private fun seekPlayerTo(position: Int) {
        val service = player ?: return
        val method = service.javaClass.allMethods()
            .firstOrNull { it.isSeekToMethod() }
            ?: return
        val args = when (method.parameterCount) {
            1 -> arrayOf<Any>(position)
            2 -> arrayOf<Any>(position, true)
            else -> return
        }
        runCatching { method.invoke(service, *args) }
            .onFailure { log("SkipVideoAd seekTo failed", it) }
    }

    private fun Method.isSeekToMethod(): Boolean {
        if (name != "seekTo" || parameterCount !in 1..2) return false
        if (!parameterTypes[0].isIntType()) return false
        return parameterCount == 1 || parameterTypes[1].isBooleanType()
    }

    private fun Class<*>.isIntType(): Boolean =
        this == Int::class.javaPrimitiveType || this == Int::class.javaObjectType

    private fun Class<*>.isBooleanType(): Boolean =
        this == Boolean::class.javaPrimitiveType || this == Boolean::class.javaObjectType

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(env.hostContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun av2bv(aid: Long): String {
        val result = CharArray(12) { if (it < 3) "BV1"[it] else '0' }
        var value = ((1L shl 51) or aid) xor 23442827791579L
        var index = 11
        while (value > 0) {
            result[index--] = BV_TABLE[(value % 58).toInt()]
            value /= 58
        }
        result[3] = result[9].also { result[9] = result[3] }
        result[4] = result[7].also { result[7] = result[4] }
        return String(result)
    }

    private companion object {
        private const val PLAYER_MOSS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
        private const val PLAY_VIEW_UNITE_REQ = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq"
        private const val MOSS_RESPONSE_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private const val PLAYER_CORE_SERVICE_INTERFACE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
        private const val BV_TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

        private val PLAYER_CORE_SERVICE_CANDIDATES = arrayOf(
            "tv.danmaku.biliplayerv2.service.PlayerCoreService",
            "tv.danmaku.biliplayerimpl.core.PlayerCoreService",
            "com.bilibili.playerbizcommon.service.PlayerCoreService",
        )
    }
}
