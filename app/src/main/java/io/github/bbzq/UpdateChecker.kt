package io.github.bbzq

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通过 LSPosed 模块仓库的 GitHub Release API 检查模组是否有新版本。
 *
 * 先按发布通道过滤（默认仅正式版；接收测试版时纳入 prerelease），再取最新 Release
 * 与当前版本比较，判定优先级：
 * 1. versionCode（两端均可解析时）：最新版 > 当前版 → 有更新；
 * 2. 版本号（versionName）：最新版版本号更高 → 有更新；
 * 3. 其余情况（版本号相同或更低）→ 已是最新。
 *
 * versionCode 取自 tag_name 开头的数字（如 133-v1.0.3-133 → 133），versionName 取 name 字段，
 * 更新日志取 body，安装包大小取 APK 资产的 size。
 *
 * 结果回调统一切回主线程，便于直接更新 UI。
 */
object UpdateChecker {

    data class Result(
        val status: Status,
        val latestVersion: String? = null,
        val currentVersion: String? = null,
        val releaseUrl: String? = null,
        /** 最新 Release 的更新日志（release body），无内容时为 null */
        val releaseNotes: String? = null,
        /** 最新 Release 的 versionCode，无法解析时为 0 */
        val latestVersionCode: Int = 0,
        /** 最新 Release 的 APK 安装包大小（字节），无则为 0 */
        val apkSizeBytes: Long = 0,
    )

    enum class Status {
        /** 已是最新版本 */
        UP_TO_DATE,
        /** 发现新版本 */
        UPDATE_AVAILABLE,
        /** 网络或解析失败 */
        FAILED,
    }

    /** 单个 Release 的精简信息。 */
    private data class ReleaseInfo(
        /** 版本名（来自 name 字段，如 v1.0.3） */
        val versionName: String,
        val htmlUrl: String,
        val body: String,
        /** 从 tag_name 开头数字解析出的 versionCode，无则为 0 */
        val versionCode: Int,
        /** APK 安装包大小（字节），无则为 0 */
        val apkSizeBytes: Long,
        /** 是否为预发布（测试）版本 */
        val prerelease: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param currentVersion 本地版本号（BuildConfig.RELEASE_NAME）
     * @param currentVersionCode 本地 versionCode（BuildConfig.VERSION_CODE），0 或负值表示不参与比较
     * @param acceptPrerelease 是否接收预发布（测试）版本更新
     * @return 本次请求的 [Call]，调用方可在界面销毁时 cancel 以释放回调、避免泄漏；构造失败时为 null
     */
    fun check(
        currentVersion: String,
        currentVersionCode: Int,
        acceptPrerelease: Boolean,
        onResult: (Result) -> Unit,
    ): Call? {
        // 同步兜底：构造请求或入队若抛出异常（如客户端初始化失败、调度器拒绝），
        // 也要回调一次失败，避免调用方状态永久卡住。
        return try {
            val request = Request.Builder()
                .url(RELEASE_API_URL)
                .header("accept", "application/vnd.github+json")
                .header("user-agent", USER_AGENT)
                .build()

            val call = httpClient.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    postResult(onResult, Result(Status.FAILED, currentVersion = currentVersion))
                }

                override fun onResponse(call: Call, response: Response) {
                    // 整体兜底：读取 body 或解析期间的任何异常都不应使回调丢失，
                    // 否则调用方的 updateChecking 状态会永久卡住。
                    val result = try {
                        response.use { parseResponse(it, currentVersion, currentVersionCode, acceptPrerelease) }
                    } catch (_: Throwable) {
                        Result(Status.FAILED, currentVersion = currentVersion)
                    }
                    postResult(onResult, result)
                }
            })
            call
        } catch (_: Throwable) {
            postResult(onResult, Result(Status.FAILED, currentVersion = currentVersion))
            null
        }
    }

    private fun parseResponse(
        response: Response,
        currentVersion: String,
        currentVersionCode: Int,
        acceptPrerelease: Boolean,
    ): Result {
        if (!response.isSuccessful) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        val body = response.body.string()
        if (body.isBlank()) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        val allReleases = parseReleases(body)
        if (allReleases.isEmpty()) {
            return Result(Status.FAILED, currentVersion = currentVersion)
        }
        // 发布通道过滤：未接收测试版时仅保留正式版。
        val releases = if (acceptPrerelease) allReleases else allReleases.filterNot { it.prerelease }
        if (releases.isEmpty()) {
            return Result(Status.UP_TO_DATE, currentVersion = currentVersion)
        }

        // 优先按 versionCode、其次按版本号选出最新 Release。
        val latest = releases.maxWithOrNull { a, b -> compareRelease(a, b) }
            ?: return Result(Status.FAILED, currentVersion = currentVersion)

        val hasUpdate = hasUpdate(latest, currentVersion, currentVersionCode)
        val status = if (hasUpdate) Status.UPDATE_AVAILABLE else Status.UP_TO_DATE
        return Result(
            status = status,
            latestVersion = normalizeVersion(latest.versionName),
            currentVersion = currentVersion,
            releaseUrl = latest.htmlUrl,
            releaseNotes = latest.body.trim().takeIf { it.isNotEmpty() },
            latestVersionCode = latest.versionCode,
            apkSizeBytes = latest.apkSizeBytes,
        )
    }

    /**
     * 判断是否有更新。
     *
     * - 两端 versionCode 均有效：直接以 versionCode 高低为准；
     * - 否则按版本号：最新版版本号更高 → 有更新；
     * - 其余情况（版本号相同或更低）→ 已是最新。
     */
    private fun hasUpdate(
        latest: ReleaseInfo,
        currentVersion: String,
        currentVersionCode: Int,
    ): Boolean {
        if (latest.versionCode > 0 && currentVersionCode > 0) {
            return latest.versionCode > currentVersionCode
        }
        return compareVersion(latest.versionName, currentVersion) > 0
    }

    /** 列表内排序用：先比 versionCode，缺失则比版本号。 */
    private fun compareRelease(a: ReleaseInfo, b: ReleaseInfo): Int {
        if (a.versionCode > 0 && b.versionCode > 0) {
            return a.versionCode.compareTo(b.versionCode)
        }
        return compareVersion(a.versionName, b.versionName)
    }

    /** 解析 Release 列表，丢弃 draft 与无版本名的条目。 */
    private fun parseReleases(body: String): List<ReleaseInfo> {
        val array = JSONArray(body)
        val releases = ArrayList<ReleaseInfo>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val tagName = jsonString(obj, "tag_name")
            val versionCode = parseVersionCode(tagName)
            // 版本名优先取 name，缺失时退回 tag_name 并剥离首尾 versionCode 前后缀，
            // 避免 `133-v1.0.3-133` 的数字前缀被当成主版本号。
            val versionName = jsonString(obj, "name").ifBlank { versionNameFromTag(tagName, versionCode) }
            if (versionName.isBlank()) continue
            val htmlUrl = jsonString(obj, "html_url").takeIf { it.isNotBlank() } ?: RELEASE_PAGE_URL
            releases += ReleaseInfo(
                versionName = versionName,
                htmlUrl = htmlUrl,
                body = jsonString(obj, "body"),
                versionCode = versionCode,
                apkSizeBytes = parseApkSize(obj),
                prerelease = obj.optBoolean("prerelease", false),
            )
        }
        return releases
    }

    /**
     * 从 tag_name 开头的数字解析 versionCode。
     *
     * LSPosed 仓库 tag 形如 `133-v1.0.3-133`，开头第一段即 versionCode；无则返回 0。
     */
    private fun parseVersionCode(tagName: String): Int =
        tagName.substringBefore('-', "").toIntOrNull() ?: 0

    /**
     * 由 tag_name 推导可读版本名（仅在 name 字段缺失时使用）。
     *
     * LSPosed tag 形如 `133-v1.0.3-133`：先剥离开头的 versionCode 前缀，再剥离结尾的 `-133` 后缀，
     * 得到 `v1.0.3`，避免数字前缀被 compareVersion 误当成主版本号。无法识别时退回原 tag。
     */
    private fun versionNameFromTag(tagName: String, versionCode: Int): String {
        if (tagName.isBlank()) return ""
        if (versionCode <= 0) return tagName
        val codeText = versionCode.toString()
        return tagName
            .removePrefix("$codeText-")
            .removeSuffix("-$codeText")
            .ifBlank { tagName }
    }

    /** 读取字符串字段，JSON null 视为空串（org.json 的 optString 对 null 会返回字面 "null"）。 */
    private fun jsonString(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key)

    /** 取 Release 中 APK 资产的大小（字节），多个时取最大值，无则返回 0。 */
    private fun parseApkSize(release: JSONObject): Long {
        val assets = release.optJSONArray("assets") ?: return 0
        var size = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val s = asset.optLong("size", 0)
            if (s > size) size = s
        }
        return size
    }

    private fun postResult(onResult: (Result) -> Unit, result: Result) {
        mainHandler.post { onResult(result) }
    }

    /** 比较语义化版本号：remote>local 返回 1，remote<local 返回 -1，相等返回 0。 */
    private fun compareVersion(remote: String, local: String): Int {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)
        val size = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until size) {
            val r = remoteParts.getOrElse(index) { 0 }
            val l = localParts.getOrElse(index) { 0 }
            if (r != l) return if (r > l) 1 else -1
        }
        return 0
    }

    private fun parseVersionParts(version: String): List<Int> =
        normalizeVersion(version)
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** 去掉前缀 v / V 与首尾空白（如 v1.0.3 → 1.0.3）。 */
    private fun normalizeVersion(version: String): String =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .trim()

    private const val RELEASE_API_URL =
        "https://api.github.com/repos/Xposed-Modules-Repo/io.github.bbzq/releases?per_page=30"
    private const val RELEASE_PAGE_URL =
        "https://github.com/Xposed-Modules-Repo/io.github.bbzq/releases/latest"
    private const val USER_AGENT = "BBZQ-UpdateChecker"
}
