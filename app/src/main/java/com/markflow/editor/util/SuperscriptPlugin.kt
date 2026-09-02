package com.markflow.editor.util

import io.noties.markwon.AbstractMarkwonPlugin

/**
 * Markwon 上标插件
 *
 * 支持 `^superscript^` 语法，在 Markdown 解析前将 ^text^ 替换为 <sup>text</sup>，
 * 利用 HtmlPlugin 渲染上标效果。
 * 与 Markor 的 Flexmark superscript 扩展对应。
 *
 * 采用手动逐字符解析而非正则，避免惰性匹配的回溯问题。
 *
 * 限制：
 * - 不跨行匹配（^ 之间不能有换行符）
 * - 不匹配 ^^ 双插入符
 * - 内容不能为空或首尾含空格
 * - 跳过 LaTeX 公式区间（$...$ / $$...$$），避免公式内的 ^ 被误识别
 */
class SuperscriptPlugin : AbstractMarkwonPlugin() {

    override fun processMarkdown(markdown: String): String {
        return preprocessSuperscript(markdown)
    }

    /**
     * 手动逐字符解析 ^text^ 模式
     *
     * 算法：
     * 1. 扫描到 ^ 且前一个字符不是 ^ → 视为候选开定界符
     * 2. 向后查找配对的 ^（后一个字符不是 ^）
     * 3. 验证内容有效性（非空、不含换行、首尾非空格）
     * 4. 替换为 <sup>content</sup>
     */
    private fun preprocessSuperscript(markdown: String): String {
        if (!markdown.contains('^')) return markdown

        val result = StringBuilder(markdown.length + 32)
        var i = 0

        while (i < markdown.length) {
            val ch = markdown[i]

            // 跳过 LaTeX 公式区间（$...$ / $$...$$）：公式内的 ^ 是上标运算符，
            // 若按 ^text^ 上标语法处理会破坏 LaTeX 内容（如 $a^2 + b^2 = c^2$）
            if (ch == '$') {
                val latexEnd = findLatexRegionEnd(markdown, i)
                if (latexEnd > i) {
                    result.append(markdown, i, latexEnd)
                    i = latexEnd
                    continue
                }
            }

            // 候选开定界符：^ 且前后都不是 ^（避免与 ^^ 混淆）
            if (ch == '^'
                && (i == 0 || markdown[i - 1] != '^')
                && !(i + 1 < markdown.length && markdown[i + 1] == '^')
            ) {
                val contentStart = i + 1
                val closingIdx = findClosingCaret(markdown, contentStart)

                if (closingIdx > contentStart) {
                    val content = markdown.substring(contentStart, closingIdx)
                    if (isValidSuperscriptContent(content)) {
                        result.append("<sup>").append(content).append("</sup>")
                        i = closingIdx + 1
                        continue
                    }
                }
            }

            result.append(ch)
            i++
        }

        return result.toString()
    }

    /**
     * 查找配对的闭定界符 ^
     * 要求闭定界符后一个字符不是 ^（避免匹配 ^^）
     */
    private fun findClosingCaret(text: String, startFrom: Int): Int {
        var idx = startFrom
        while (idx < text.length) {
            if (text[idx] == '^') {
                // 确保后面不是 ^（避免 ^^ 双插入符）
                if (idx + 1 >= text.length || text[idx + 1] != '^') {
                    return idx
                }
                // 跳过 ^^
                idx += 2
                continue
            }
            // 不跨行
            if (text[idx] == '\n') return -1
            idx++
        }
        return -1
    }

    /**
     * 验证上标内容有效性
     */
    private fun isValidSuperscriptContent(content: String): Boolean {
        if (content.isEmpty()) return false
        if (content[0] == ' ' || content.last() == ' ') return false
        if (content.contains('\n')) return false
        return true
    }

    /**
     * 查找 LaTeX 公式区间结束位置（指向最后一个 '$' 之后）
     *
     * 支持 $$...$$（双美元）和 $...$（单美元）两种形式。
     * 找不到配对定界符时返回 -1（调用方按普通文本继续处理）。
     */
    private fun findLatexRegionEnd(text: String, start: Int): Int {
        if (start + 1 < text.length && text[start + 1] == '$') {
            var idx = start + 2
            while (idx < text.length) {
                if (text[idx] == '$' && idx + 1 < text.length && text[idx + 1] == '$') {
                    return idx + 2
                }
                idx++
            }
            return -1
        }
        var idx = start + 1
        while (idx < text.length) {
            if (text[idx] == '$') return idx + 1
            idx++
        }
        return -1
    }
}