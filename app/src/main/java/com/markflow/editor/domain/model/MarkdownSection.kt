package com.markflow.editor.domain.model

/**
 * Markdown 解析后的分段数据模型
 *
 * 将原始 Markdown 文本拆分为普通文本段和代码块段，
 * 为 LazyColumn 分段渲染提供数据结构。
 */
sealed class MarkdownSection {

    /** 用于 LazyColumn key 的唯一标识 */
    abstract val id: String

    /** 在原 Markdown 中的起始行号（0-indexed） */
    abstract val startLine: Int

    /** 在原 Markdown 中的结束行号（exclusive） */
    abstract val endLine: Int

    /** 普通 Markdown 文本段（段落、标题、表格等） */
    data class Text(
        override val id: String,
        val content: String,
        override val startLine: Int,
        override val endLine: Int
    ) : MarkdownSection()

    /** 代码块段 */
    data class Code(
        override val id: String,
        val info: CodeBlockInfo,
        override val startLine: Int,
        override val endLine: Int
    ) : MarkdownSection()
}

/**
 * 代码块元信息
 *
 * @param language 语言标识（如 "kotlin", "bash"），可能为空
 * @param rawCode 原始代码文本（不含围栏标记），用于复制
 * @param lineCount 代码行数
 */
data class CodeBlockInfo(
    val language: String,
    val rawCode: String,
    val lineCount: Int = rawCode.lines().size
)