package io.github.bbzq

import android.text.Html
import android.text.Spanned

/**
 * 将 GitHub Release 的轻量 Markdown（标题、无序列表、加粗、行内代码、链接）
 * 转换为可在 TextView / AlertDialog 中渲染的富文本。
 *
 * 仅覆盖 Release Notes 常见语法，不追求完整 Markdown 兼容。
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
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> builder.append("<br>")

                trimmed.startsWith("### ") ->
                    builder.append("<b>").append(inline(trimmed.removePrefix("### "))).append("</b><br>")

                trimmed.startsWith("## ") ->
                    builder.append("<b>").append(inline(trimmed.removePrefix("## "))).append("</b><br>")

                trimmed.startsWith("# ") ->
                    builder.append("<b>").append(inline(trimmed.removePrefix("# "))).append("</b><br>")

                trimmed.startsWith("> ") ->
                    builder.append("<i>").append(inline(trimmed.removePrefix("> "))).append("</i><br>")

                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    builder.append("• ").append(inline(trimmed.substring(2))).append("<br>")

                else -> builder.append(inline(trimmed)).append("<br>")
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
        val tokens = ArrayList<String>()
        fun stash(html: String): String {
            val placeholder = "$PLACEHOLDER_MARK${tokens.size}$PLACEHOLDER_MARK"
            tokens += html
            return placeholder
        }

        // 1. 行内代码：内容原样转义，不再参与链接/加粗解析。
        var s = CODE_REGEX.replace(text) { m -> stash("<tt>${escapeHtml(m.groupValues[1])}</tt>") }
        // 2. 链接：在整体转义前抽取，label 与 URL 各只转义一次；非 http(s) 链接降级为纯文本。
        s = LINK_REGEX.replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (isSafeUrl(url)) {
                stash("<a href=\"${escapeHtml(url)}\">${escapeHtml(label)}</a>")
            } else {
                escapeHtml(label)
            }
        }
        // 3. 其余文本统一转义。
        s = escapeHtml(s)
        // 4. **粗体**
        s = BOLD_REGEX.replace(s) { m -> "<b>${m.groupValues[1]}</b>" }
        // 5. 回填占位符。
        tokens.forEachIndexed { index, html ->
            s = s.replace("$PLACEHOLDER_MARK$index$PLACEHOLDER_MARK", html)
        }
        return s
    }

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
}

