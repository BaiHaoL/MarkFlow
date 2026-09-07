package com.markflow.editor.util

/**
 * Markdown 文档大纲解析器
 *
 * 从 Markdown 原文中提取所有标题（H1~H6），
 * 用于生成可点击的目录大纲（Table of Contents）。
 */
object TocParser {

    /** 标题匹配正则（提为常量，避免 parse 循环内每行重复编译） */
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")

    /**
     * 目录条目
     *
     * @param level 标题层级（1~6）
     * @param text 标题文本（不含 # 前缀）
     * @param lineIndex 在原文中的行号（0-based）
     */
    data class TocEntry(
        val level: Int,
        val text: String,
        val lineIndex: Int
    )

    /**
     * 解析 Markdown 文本，提取所有标题
     *
     * 匹配规则：行首 1~6 个 # 后跟空格，再跟标题文本
     * 例如：
     *   "# 一级标题"     → level=1, text="一级标题"
     *   "## 二级标题"    → level=2, text="二级标题"
     *   "### 三级标题"   → level=3, text="三级标题"
     *
     * 忽略代码块内的 #（以 ``` 包裹的内容）
     *
     * @param markdown 原始 Markdown 文本
     * @return 标题条目列表，按出现顺序排列
     */
    fun parse(markdown: String): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        var inCodeBlock = false

        for ((lineIdx, line) in markdown.lines().withIndex()) {
            val trimmed = line.trim()

            // 跟踪代码块状态
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            // 跳过代码块内的行
            if (inCodeBlock) continue

            // 匹配标题：1~6 个 # + 空格 + 文本
            val match = HEADING_REGEX.find(trimmed)
            if (match == null) continue

            val level = match.groupValues[1].length
            val text = match.groupValues[2].trim()

            entries.add(
                TocEntry(
                    level = level,
                    text = text,
                    lineIndex = lineIdx
                )
            )
        }

        return entries
    }
}