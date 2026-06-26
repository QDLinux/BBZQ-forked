package io.github.bbzq

import android.text.Html
import android.text.Spanned

/**
 * 将 GitHub Release 的轻量 Markdown 转换为可在 TextView / AlertDialog 中渲染的富文本。
 *
 * 支持：# / ## / ### 标题（映射为 h2/h3/h4，呈现字号层次）、有序与无序列表、
 * 引用、加粗、行内代码、链接。仅覆盖 Release Notes 常见语法，不追求完整 Markdown 兼容。
 */
object MarkdownFormatter {

    fun toSpanned(markdown: String): Spanned {
        val html = toHtml(markdown)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    private fun toHtml(markdown: String): String {
        val builder = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> builder.append("<br>")

                // 标题用 h 标签获得字号层次（h2>h3>h4，自带加粗与段落间距），不再额外加 <br>。
                line.startsWith("### ") ->
                    builder.append("<h4>").append(inline(line.removePrefix("### "))).append("</h4>")

                line.startsWith("## ") ->
                    builder.append("<h3>").append(inline(line.removePrefix("## "))).append("</h3>")

                line.startsWith("# ") ->
                    builder.append("<h2>").append(inline(line.removePrefix("# "))).append("</h2>")

                line.startsWith("> ") ->
                    builder.append("<i>").append(inline(line.removePrefix("> "))).append("</i><br>")

                line.startsWith("- ") || line.startsWith("* ") ->
                    builder.append("&nbsp;&nbsp;•&nbsp;").append(inline(line.substring(2))).append("<br>")

                ORDERED_LIST_REGEX.containsMatchIn(line) ->
                    builder.append("&nbsp;&nbsp;").append(inline(line)).append("<br>")

                else -> builder.append(inline(line)).append("<br>")
            }
        }
        return builder.toString()
    }

    /**
     * 处理行内语法。先把行内代码、链接抽成占位符（其原始内容只在抽取时转义一次，
     * 不再参与后续整体转义与加粗解析），再对剩余文本统一转义、处理加粗，最后回填占位符。
     * 这样可避免链接 URL 被二次转义（如 query string 中的 & 变成 &amp;amp;）。
     */
    private fun inline(text: String): String {
        // 先剥离正文里可能存在的占位符标记，防止被伪造（含字面 U+0001 的内容否则会被当作占位符匹配并注入）。
        val sanitized = text.replace(PLACEHOLDER_MARK, "")
        val tokens = ArrayList<String>()
        fun stash(html: String): String {
            val placeholder = "$PLACEHOLDER_MARK${tokens.size}$PLACEHOLDER_MARK"
            tokens += html
            return placeholder
        }

        // 1. 行内代码：内容原样转义，不再参与链接/加粗解析。
        var s = CODE_REGEX.replace(sanitized) { m -> stash("<tt>${escapeHtml(m.groupValues[1])}</tt>") }
        // 2. 链接：在整体转义前抽取，label 继续解析加粗、URL 与 label 各只转义一次；非 http(s) 链接降级为纯文本。
        s = LINK_REGEX.replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (isSafeUrl(url)) {
                stash("<a href=\"${escapeHtml(url)}\">${formatLabel(label)}</a>")
            } else {
                formatLabel(label)
            }
        }
        // 3. 其余文本统一转义。
        s = escapeHtml(s)
        // 4. **粗体**
        s = applyBold(s)
        // 5. 倒序回填：外层 token（如链接）的 html 可能内嵌更早抽取的占位符（如 label 里的行内代码），
        //    倒序保证外层先回填、把内嵌占位符暴露出来后再被替换，避免遗留无法回填的占位符。
        for (index in tokens.indices.reversed()) {
            s = s.replace("$PLACEHOLDER_MARK$index$PLACEHOLDER_MARK", tokens[index])
        }
        return s
    }

    /** 处理链接 label：整体转义后再解析其中的 **加粗**，使 label 内的加粗也能渲染。 */
    private fun formatLabel(label: String): String = applyBold(escapeHtml(label))

    private fun applyBold(text: String): String =
        BOLD_REGEX.replace(text) { m -> "<b>${m.groupValues[1]}</b>" }

    /** 仅允许 http/https 链接，其余（javascript:、data: 等）一律视为不安全。 */
    private fun isSafeUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    /** 行内代码占位符标记，使用控制字符，确保不与正文及 HTML 转义冲突。 */
    private const val PLACEHOLDER_MARK = "\u0001"

    private val LINK_REGEX = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val BOLD_REGEX = Regex("""\*\*([^*]+)\*\*""")
    private val CODE_REGEX = Regex("""`([^`]+)`""")
    private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s""")
}

