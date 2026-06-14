package io.github.bbzq.roaming.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.allMethods
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.hookAfterMethod
import io.github.bbzq.roaming.hookBefore
import io.github.bbzq.roaming.hookBeforeAllConstructors
import io.github.bbzq.roaming.setObjectField
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL

class ShareHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        var count = 0
        count += hookLegacyShareClickResult()
        count += hookModernShareContent()
        count += hookModernCopyContent()
        count += hookCopyToClipboardUtility()
        count += hookClipboardFallback()

        log("startHook: Share, methods=$count")
    }

    private fun hookLegacyShareClickResult(): Int {
        val shareClickResult = "com.bilibili.lib.sharewrapper.online.api.ShareClickResult".from(classLoader)
            ?: return 0
        var count = 0
        count += env.hookAfterMethod(shareClickResult, "getLink") { param ->
            val link = param.result as? String ?: return@hookAfterMethod
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookAfterMethod

            val purified = purifyLink(link, transformAv)
            if (purified == link) return@hookAfterMethod
            param.thisObject?.setObjectField("link", purified)
            param.result = purified
        }
        count += env.hookAfterMethod(shareClickResult, "getContent") { param ->
            val content = param.result as? String ?: return@hookAfterMethod
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookAfterMethod

            val transformed = purifyText(content, transformAv)
            if (transformed == content) return@hookAfterMethod
            param.thisObject?.setObjectField("content", transformed)
            param.result = transformed
        }
        count += env.hookAfterMethod(shareClickResult, "getShareMode") { param ->
            if (!isMiniProgramEnabled()) return@hookAfterMethod
            if (param.result != 6 && param.result != 7) return@hookAfterMethod
            param.result = 0
            val target = param.thisObject ?: return@hookAfterMethod
            if (target.getObjectField("title") == BILI_TITLE) {
                target.setObjectField("title", target.getObjectField("content"))
                target.setObjectField("content", BBZQ_SHARE_TEXT)
            }
            (target.getObjectField("content") as? String)
                ?.takeIf { it.startsWith(WATCHED_PREFIX) }
                ?.let { target.setObjectField("content", "$it\n$BBZQ_SHARE_TEXT") }
        }
        return count
    }

    private fun hookModernShareContent(): Int {
        var count = 0
        "p7645kntr.common.share.domain.p7866v1.ShareContent".from(classLoader)?.let { type ->
            count += env.hookBeforeAllConstructors(type) { param ->
                rewriteShareContentArgs(param.args)
            }
            count += hookCopyMethod(type, ::rewriteShareContentArgs)
            count += hookPurifiedStringGetter(type, "getLink", ::purifyLink)
            count += hookPurifiedStringGetter(type, "getContent", ::purifyText)
            count += env.hookAfterMethod(type, "getMode") { param ->
                if (!isMiniProgramEnabled()) return@hookAfterMethod
                param.result = normalizeShareMode(param.result)
            }
        }
        "p7645kntr.common.share.domain.p7866v1.ShareBiliContent".from(classLoader)?.let { type ->
            count += env.hookBeforeAllConstructors(type) { param ->
                rewriteShareBiliContentArgs(param.args)
            }
            count += hookCopyMethod(type, ::rewriteShareBiliContentArgs)
            count += hookPurifiedStringGetter(type, "getDescription", ::purifyText)
            count += hookPurifiedStringGetter(type, "getContentUrl", ::purifyLink)
        }
        return count
    }

    private fun hookModernCopyContent(): Int {
        val type = "p7645kntr.common.share.common.handler.p7848copy.C134543b".from(classLoader)
            ?: return 0
        var count = env.hookBeforeAllConstructors(type) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookBeforeAllConstructors
            val content = param.args.firstOrNull() as? String ?: return@hookBeforeAllConstructors
            param.args[0] = purifyText(content, transformAv)
        }
        count += hookPurifiedStringGetter(type, "mo127c", ::purifyText)
        return count
    }

    private fun hookCopyToClipboardUtility(): Int {
        val type = "p7645kntr.common.share.common.handler.p7848copy.C134542a".from(classLoader)
            ?: return 0
        val methods = type.allMethods()
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    it.returnType == Void.TYPE
            }
            .toList()
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                val transformAv = isMiniProgramEnabled()
                if (!isShareTransformEnabled(transformAv)) return@hookBefore
                val content = param.args.firstOrNull() as? String ?: return@hookBefore
                param.args[0] = purifyText(content, transformAv)
            }
        }
        return methods.size
    }

    private fun hookCopyMethod(type: Class<*>, rewriter: (MutableList<Any?>) -> Unit): Int {
        val methods = type.allMethods()
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.returnType == type &&
                    it.parameterCount >= 2
            }
            .toList()
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                rewriter(param.args)
            }
        }
        return methods.size
    }

    private fun hookPurifiedStringGetter(
        type: Class<*>,
        methodName: String,
        purifier: (String, Boolean) -> String,
    ): Int {
        val method = type.allMethods()
            .firstOrNull {
                it.name == methodName &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java
            } ?: return 0
        env.hookAfter(method) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookAfter
            val value = param.result as? String ?: return@hookAfter
            val purified = purifier(value, transformAv)
            if (purified != value) param.result = purified
        }
        return 1
    }

    private fun hookClipboardFallback(): Int {
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        env.hookBefore(method) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isShareTransformEnabled(transformAv)) return@hookBefore

            val clip = param.args.firstOrNull() as? ClipData ?: return@hookBefore
            val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?: return@hookBefore
            val purified = purifyText(text, transformAv)
            if (purified != text) {
                param.args[0] = ClipData.newPlainText(clip.description.label, purified)
            }
        }
        return 1
    }

    private fun rewriteShareContentArgs(args: MutableList<Any?>) {
        val transformAv = isMiniProgramEnabled()
        if (!isShareTransformEnabled(transformAv)) return
        if (args.isNotEmpty()) args[0] = normalizeShareMode(args[0])
        rewriteStringArg(args, 2, transformAv, ::purifyText)
        rewriteStringArg(args, 3, transformAv, ::purifyLink)

        if (transformAv && args.size > 2) {
            if (args.getOrNull(1) == BILI_TITLE) {
                args[1] = args[2]
                args[2] = BBZQ_SHARE_TEXT
            }
            (args.getOrNull(2) as? String)
                ?.takeIf { it.startsWith(WATCHED_PREFIX) && !it.contains(BBZQ_SHARE_TEXT) }
                ?.let { args[2] = "$it\n$BBZQ_SHARE_TEXT" }
        }
    }

    private fun rewriteShareBiliContentArgs(args: MutableList<Any?>) {
        val transformAv = isMiniProgramEnabled()
        if (!isShareTransformEnabled(transformAv)) return
        rewriteStringArg(args, 1, transformAv, ::purifyText)
        rewriteStringArg(args, 3, transformAv, ::purifyLink)
    }

    private fun rewriteStringArg(
        args: MutableList<Any?>,
        index: Int,
        transformAv: Boolean,
        purifier: (String, Boolean) -> String,
    ) {
        val value = args.getOrNull(index) as? String ?: return
        args[index] = purifier(value, transformAv)
    }

    private fun normalizeShareMode(mode: Any?): Any? {
        if (!isMiniProgramEnabled() || mode == null) return mode
        if (!mode.toString().contains("MiniProgram", ignoreCase = true)) return mode
        return mode.javaClass.enumConstants
            ?.firstOrNull { it.toString() == "Link" }
            ?: mode
    }

    private fun purifyText(text: String, transformAv: Boolean): String =
        URL_REGEX.replace(text) { match ->
            val raw = match.value
            val suffix = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
            val url = raw.dropLast(suffix.length)
            purifyLink(url, transformAv) + suffix
        }

    private fun purifyLink(url: String, transformAv: Boolean): String =
        transformUrl(resolveShortLink(url), transformAv)

    private fun isMiniProgramEnabled(): Boolean =
        prefs.getBoolean(ModuleSettings.KEY_MINI_PROGRAM_ENABLED, false)

    private fun isPurifyShareEnabled(): Boolean =
        ModuleSettings.isPurifyShareEnabled(prefs)

    private fun isShareTransformEnabled(transformAv: Boolean): Boolean =
        isPurifyShareEnabled() || transformAv

    private fun String.isShortLink(): Boolean =
        startsWith("https://bili2233.cn") ||
            startsWith("http://bili2233.cn") ||
            startsWith("https://b23.tv") ||
            startsWith("http://b23.tv")

    private fun resolveShortLink(url: String): String {
        if (!url.isShortLink()) return url
        val requestUrl = runCatching {
            Uri.parse(url).buildUpon().query(null).fragment(null).build().toString()
        }.getOrDefault(url)
        return runCatching {
            val conn = URL(requestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_MOVED_PERM,
                307,
                308,
                -> conn.getHeaderField("Location") ?: url

                else -> url
            }
        }.getOrDefault(url)
    }

    private fun transformUrl(url: String, transformAv: Boolean): String {
        val target = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = target.host.orEmpty()
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return url

        val bv = if (transformAv) {
            target.path?.split("/")?.firstOrNull { it.startsWith("BV") && it.length == 12 }
        } else {
            null
        }
        val av = bv?.let { "av${bv2av(it)}" }
        val newUrl = target.buildUpon().clearQuery().fragment(null)
        if (av != null) {
            newUrl.path(target.path!!.replace(bv, av))
        }

        target.encodedQuery
            ?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.filter { it.size == 2 }
            ?.forEach {
                when (it[0]) {
                    "p", "t" -> newUrl.appendQueryParameter(it[0], it[1])
                    "start_progress" -> {
                        newUrl.appendQueryParameter("start_progress", it[1])
                        newUrl.appendQueryParameter("t", (it[1].toLongOrNull()?.div(1000) ?: 0).toString())
                    }
                }
            }
        newUrl.appendQueryParameter("unique_k", "2333")
        return newUrl.build().toString()
    }

    private fun bv2av(bv: String): Long {
        val table = HashMap<Char, Int>()
        "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf".forEachIndexed { index, char ->
            table[char] = index
        }
        val positions = intArrayOf(11, 10, 3, 8, 4, 6, 5, 7, 9)
        var result = 0L
        positions.forEachIndexed { index, position ->
            result += (table[bv[position]] ?: 0) * pow58(index)
        }
        return result.and(2251799813685247L).xor(23442827791579L)
    }

    private fun pow58(index: Int): Long {
        var result = 1L
        repeat(index) { result *= 58L }
        return result
    }

    private companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
        private const val BILI_TITLE = "\u54d4\u54e9\u54d4\u54e9"
        private const val BBZQ_SHARE_TEXT = "\u7531 BBZQ \u5206\u4eab"
        private const val WATCHED_PREFIX = "\u5df2\u89c2\u770b"
        private val TRAILING_PUNCTUATION = setOf(
            ')',
            ']',
            '>',
            ',',
            '.',
            ';',
            '!',
            '?',
            '\u3002',
            '\uff0c',
            '\uff1b',
            '\uff01',
            '\uff1f',
        )
    }
}
