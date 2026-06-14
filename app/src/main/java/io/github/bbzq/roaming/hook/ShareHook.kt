package io.github.bbzq.roaming.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfterMethod
import io.github.bbzq.roaming.hookBefore
import io.github.bbzq.roaming.setObjectField
import java.net.HttpURLConnection
import java.net.URL

class ShareHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val shareClickResult = "com.bilibili.lib.sharewrapper.online.api.ShareClickResult".from(classLoader)
        var count = 0

        if (shareClickResult != null) {
            count += env.hookAfterMethod(shareClickResult, "getLink") { param ->
                val link = param.result as? String ?: return@hookAfterMethod
                val transformAv = isMiniProgramEnabled()
                if (!isPurifyShareEnabled() && !transformAv) return@hookAfterMethod

                val purified = purifyLink(link, transformAv)
                if (purified == link) return@hookAfterMethod
                param.thisObject?.setObjectField("link", purified)
                param.result = purified
            }
            count += env.hookAfterMethod(shareClickResult, "getContent") { param ->
                val content = param.result as? String ?: return@hookAfterMethod
                val transformAv = isMiniProgramEnabled()
                if (!isPurifyShareEnabled() && !transformAv) return@hookAfterMethod

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
                if (target.getObjectField("title") == "哔哩哔哩") {
                    target.setObjectField("title", target.getObjectField("content"))
                    target.setObjectField("content", "由 BBZQ 分享")
                }
                (target.getObjectField("content") as? String)
                    ?.takeIf { it.startsWith("已观看") }
                    ?.let { target.setObjectField("content", "$it\n由 BBZQ 分享") }
            }
        }
        count += hookClipboardFallback()

        log("startHook: Share, methods=$count")
    }

    private fun hookClipboardFallback(): Int {
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        env.hookBefore(method) { param ->
            val transformAv = isMiniProgramEnabled()
            if (!isPurifyShareEnabled() && !transformAv) return@hookBefore

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
        if (!host.endsWith("bilibili.com")) return url

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
        private val TRAILING_PUNCTUATION = setOf(')', ']', '>', ',', '.', ';', '!', '?', '。', '，', '；', '！', '？')
    }
}
