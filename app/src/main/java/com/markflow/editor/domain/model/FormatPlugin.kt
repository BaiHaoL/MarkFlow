package com.markflow.editor.domain.model

/**
 * 格式插件接口
 *
 * 参考 Markor 的 FormatRegistry 设计，每种文件格式对应一个插件实例，
 * 包含格式识别、语法高亮、自动格式化规则等完整能力。
 *
 * 与 Markor 的映射关系：
 * - FormatPlugin      → FormatRegistry.getFormat()
 * - grammarName        → SyntaxHighlighter
 * - autoFormatPatterns → AutoTextFormatter.FormatPatterns
 * - isMarkdown         → TextConverter（Markdown 专用）
 */
interface FormatPlugin {
    /** 关联的文件扩展名列表（小写，不含点） */
    val extensions: List<String>

    /** Prism4j 语法名称（空字符串 = 无语法高亮） */
    val grammarName: String

    /** UI 显示名称 */
    val displayName: String

    /** 是否为 Markdown 格式（影响预览渲染方式） */
    val isMarkdown: Boolean get() = false

    /**
     * 自动格式化规则（可选）
     *
     * 非空时，编辑器在换行时应用对应的列表续行规则。
     * 例如 Markdown 的 `- ` / `* ` / `+ ` 无序列表、`1. ` 有序列表、`- [ ] ` 复选框。
     */
    val autoFormatPatterns: AutoFormatPatterns? get() = null

    /**
     * 格式名称（用于注册表日志和调试）
     */
    val formatName: String
        get() = displayName
}

/**
 * 自动格式化规则
 *
 * 定义列表前缀的正则匹配模式，用于编辑器的自动列表续行功能。
 * 参考 Markor 的 AutoTextFormatter.FormatPatterns。
 */
data class AutoFormatPatterns(
    /** 无序列表前缀：- / * / + 后跟空格，如 "^(\s*)([-*+])\s+(.*)" */
    val prefixUnorderedList: Regex,

    /** 复选框前缀：- [ ] 或 - [x] 后跟空格，如 "^(\s*)([-*+])\s+\[([ xX])\]\s+(.*)" */
    val prefixCheckBoxList: Regex,

    /** 有序列表前缀：数字. 或 数字) 后跟空格，如 "^(\s*)(\d+)([.)])\s+(.*)" */
    val prefixOrderedList: Regex,

    /** 缩进容差（空格数），用于判断缩进层级 */
    val indentSlack: Int = 4
) {
    companion object {
        /** Markdown 默认格式化规则 */
        val MARKDOWN = AutoFormatPatterns(
            prefixUnorderedList = Regex("""^(\s*)([-*+])\s+(.*)"""),
            prefixCheckBoxList = Regex("""^(\s*)([-*+])\s+\[([ xX])\]\s+(.*)"""),
            prefixOrderedList = Regex("""^(\s*)(\d+)([.)])\s+(.*)"""),
            indentSlack = 4
        )
    }
}