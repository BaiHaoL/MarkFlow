package com.markflow.editor.util

import com.markflow.editor.domain.model.CodeBlockInfo
import com.markflow.editor.domain.model.MarkdownSection

/**
 * Markdown 文本分段解析器
 *
 * 将原始 Markdown 文本拆分为 [MarkdownSection.Text] 和 [MarkdownSection.Code] 列表，
 * 用于 LazyColumn 分段渲染。
 *
 * 解析规则：
 * - 以 ``` 开头的行视为代码块围栏标记
 * - 围栏标记后的语言标识（如 kotlin, bash）作为代码块语言
 * - 两个围栏标记之间的内容为原始代码
 * - 非代码块内容为普通文本段
 * - 连续空行会被合并，避免产生空文本段
 * - 未闭合的代码块：从开始围栏到文档末尾视为代码
 */
object MarkdownParser {

    private val FENCE_PATTERN = Regex("^```\\s*([^`]*?)\\s*$")

    /**
     * 解析 Markdown 文本为分段列表
     *
     * @param markdown 原始 Markdown 文本
     * @return 分段列表，保证至少有一个元素（空文本时返回空 Text 段）
     */
    fun parse(markdown: String): List<MarkdownSection> {
        if (markdown.isEmpty()) {
            return listOf(
                MarkdownSection.Text(
                    id = "text_0",
                    content = "",
                    startLine = 0,
                    endLine = 0
                )
            )
        }

        val lines = markdown.lines()
        val sections = mutableListOf<MarkdownSection>()
        var i = 0
        var sectionIndex = 0

        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = FENCE_PATTERN.find(line)

            if (fenceMatch != null) {
                // 代码块开始
                val lang = (fenceMatch.groupValues.getOrNull(1) ?: "").trim()
                val codeStartLine = i + 1
                var j = i + 1
                while (j < lines.size) {
                    if (FENCE_PATTERN.matches(lines[j])) {
                        break
                    }
                    j++
                }
                val codeEndLine = j // 闭合围栏所在行（exclusive: 不包含闭合围栏）
                val rawCode = if (codeStartLine < codeEndLine) {
                    lines.subList(codeStartLine, codeEndLine).joinToString("\n")
                } else {
                    ""
                }

                sections.add(
                    MarkdownSection.Code(
                        id = "code_$sectionIndex",
                        info = CodeBlockInfo(language = lang, rawCode = rawCode),
                        startLine = i,
                        endLine = if (j < lines.size) j + 1 else lines.size
                    )
                )
                sectionIndex++
                i = if (j < lines.size) j + 1 else lines.size
            } else {
                // 文本段开始
                val textStart = i
                var j = i
                while (j < lines.size && FENCE_PATTERN.find(lines[j]) == null) {
                    j++
                }
                val textContent = lines.subList(textStart, j).joinToString("\n")
                if (textContent.isNotBlank()) {
                    sections.add(
                        MarkdownSection.Text(
                            id = "text_$sectionIndex",
                            content = textContent,
                            startLine = textStart,
                            endLine = j
                        )
                    )
                    sectionIndex++
                }
                i = j
            }
        }

        // 如果没有任何分段（全是空行），返回空文本段
        if (sections.isEmpty()) {
            sections.add(
                MarkdownSection.Text(
                    id = "text_0",
                    content = "",
                    startLine = 0,
                    endLine = lines.size
                )
            )
        }

        return sections
    }
}