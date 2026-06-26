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
     * 处理行内语法。顺序：先抽出行内代码为占位符（其内容不再参与链接/加粗解析），
     * 再依次处理链接、加粗，最后回填代码占位符，避免代码内的标记被二次渲染。
     */
    private fun inline(text: String): String {
        val codeSpans = ArrayList<String>()
        // 先抽取 `行内代码`，用控制字符包裹的占位符替换（正文不会出现该字符，也不被转义/正则触碰）。
        var s = CODE_REGEX.replace(text) { m ->
            val placeholder = "$PLACEHOLDER_MARK${codeSpans.size}$PLACEHOLDER_MARK"
            codeSpans += "<tt>${escapeHtml(m.groupValues[1])}</tt>"
            placeholder
        }
        s = escapeHtml(s)
        // [文本](链接) → <a href>，过滤非 http(s) 链接，URL 一并转义。
        s = LINK_REGEX.replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (isSafeUrl(url)) {
                "<a href=\"${escapeHtml(url)}\">$label</a>"
            } else {
                label
            }
        }
        // **粗体**
        s = BOLD_REGEX.replace(s) { m -> "<b>${m.groupValues[1]}</b>" }
        // 回填行内代码占位符。
        codeSpans.forEachIndexed { index, html ->
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

