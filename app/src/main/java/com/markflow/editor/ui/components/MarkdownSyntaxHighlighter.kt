package com.markflow.editor.ui.components

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markdown 编辑模式语法高亮
 *
 * 参考 Markor 的 MarkdownSyntaxHighlighter 正则覆盖范围，在编辑模式下对
 * Markdown 语法标记进行着色，提升编辑可读性。
 *
 * 覆盖范围（对照 Markor）：
 * - HEADING       → 标题标记（# ## ### 等）用主题色
 * - BOLD          → 粗体标记（**text**）用粗体+主题色
 * - ITALICS       → 斜体标记（*text*）用斜体+主题色
 * - BOLD_ITALICS  → 粗斜体标记（***text***）用粗斜体+主题色
 * - STRIKETHROUGH → 删除线标记（~~text~~）用删除线+灰色
 * - CODE          → 行内代码（`code`）用代码背景色
 * - LINK          → 链接文本（[text](url)）用蓝色
 * - LIST_ORDERED  → 有序列表数字（1. 2.）用主题色
 * - LIST_UNORDERED→ 无序列表标记（- * +）用主题色
 * - QUOTATION     → 引用标记（>）用引用色
 * - HORIZONTAL_RULE → 分隔线（--- *** ___）用灰色
 *
 * 执行策略（按文档大小分流，避免长文档主线程卡顿）：
 * - ≤ 16K 字符：filter 内同步计算，与原行为一致（即时高亮）；
 * - \> 16K 字符：后台线程 + 80ms debounce 异步计算，期间先返回纯文本不阻塞输入，
 *   计算完成后经快照状态触发重组并命中 LRU 缓存上屏。
 *
 * 不负责（由 Prism4j 在预览模式处理）：
 * - 代码块语法高亮
 * - 表格渲染
 *
 * @param colors 主题色配置
 */
class MarkdownSyntaxHighlighter(
    private val colors: SyntaxColors = SyntaxColors.light()
) : VisualTransformation {

    /** 浅色/深色主题色配置 */
    data class SyntaxColors(
        val heading: Color,
        val bold: Color,
        val italic: Color,
        val boldItalic: Color,
        val strikethrough: Color,
        val code: Color,
        val codeBackground: Color,
        val link: Color,
        val listMarker: Color,
        val quote: Color,
        val rule: Color,
        val text: Color
    ) {
        companion object {
            fun light() = SyntaxColors(
                heading = Color(0xFF1A73E8),
                bold = Color(0xFF202124),
                italic = Color(0xFF202124),
                boldItalic = Color(0xFF202124),
                strikethrough = Color(0xFF80868B),
                code = Color(0xFFD32F2F),
                codeBackground = Color(0xFFF1F3F4),
                link = Color(0xFF1A73E8),
                listMarker = Color(0xFF5F6368),
                quote = Color(0xFF80868B),
                rule = Color(0xFFDADCE0),
                text = Color(0xFF202124)
            )

            fun dark() = SyntaxColors(
                heading = Color(0xFF8AB4F8),
                bold = Color(0xFFE8EAED),
                italic = Color(0xFFE8EAED),
                boldItalic = Color(0xFFE8EAED),
                strikethrough = Color(0xFF9AA0A6),
                code = Color(0xFFF28B82),
                codeBackground = Color(0xFF303134),
                link = Color(0xFF8AB4F8),
                listMarker = Color(0xFF9AA0A6),
                quote = Color(0xFF9AA0A6),
                rule = Color(0xFF3C4043),
                text = Color(0xFFE8EAED)
            )
        }
    }

    // ==================== 正则模式 ====================

    companion object {
        // 标题：行首的 # 标记及后续文字（整行匹配）
        private val HEADING_PATTERN = Regex("""^(#{1,6})\s.*$""", RegexOption.MULTILINE)

        // 粗斜体：***text***
        private val BOLD_ITALIC_PATTERN = Regex("""\*\*\*(.+?)\*\*\*""")

        // 粗体：**text**（不匹配 ***）
        private val BOLD_PATTERN = Regex("""(?<!\*)\*\*(?!\*)(.+?)(?<!\*)\*\*(?!\*)""")

        // 斜体：*text*（不匹配 ** 或 ***）
        private val ITALIC_PATTERN = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

        // 删除线：~~text~~（前后不能有 ~，避免与 ~~~ 代码块/下标混淆）
        private val STRIKETHROUGH_PATTERN = Regex("""~~(?!~)(.+?)(?<!~)~~""")

        // 行内代码：`code`
        private val CODE_PATTERN = Regex("""`([^`\n]+?)`""")

        // 链接：[text](url)
        private val LINK_PATTERN = Regex("""\[([^\]]+)\]\([^)]+\)""")

        // 行首无序列表标记：- * +
        private val LIST_UNORDERED_PATTERN = Regex("""^(\s*)([-*+])\s""", RegexOption.MULTILINE)

        // 行首有序列表标记：1. 2) 等
        private val LIST_ORDERED_PATTERN = Regex("""^(\s*)(\d+[.)])\s""", RegexOption.MULTILINE)

        // 引用标记：>
        private val QUOTE_PATTERN = Regex("""^(>+)\s?""", RegexOption.MULTILINE)

        // 分隔线：--- *** ___（独占一行）
        private val RULE_PATTERN = Regex("""^(\s*[-*_]{3,})\s*$""", RegexOption.MULTILINE)

        /** 小于该字符数的文档在 filter 内同步高亮（即时体验）；超过则异步计算 */
        private const val SYNC_THRESHOLD_CHARS = 16_000

        /** 异步高亮 debounce（毫秒）：停止输入该时长后才在后台计算 */
        private const val HIGHLIGHT_DEBOUNCE_MS = 80L

        /** 异步结果缓存上限（按访问序 LRU 逐出，防大文档多版本驻留内存） */
        private const val MAX_CACHE_ENTRIES = 8
    }

    // ==================== 异步高亮（大文档） ====================

    /** 异步高亮结果缓存：key = 文本内容，按访问序 LRU 逐出 */
    private val highlightCache = object : LinkedHashMap<String, AnnotatedString>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /** 快照状态：异步计算完成后自增，触发 filter 重新执行并命中缓存 */
    private val cacheVersion = mutableIntStateOf(0)

    private var highlightJob: Job? = null
    private val highlightScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 取消未完成的异步计算（组件销毁时调用，防对脱离组合的状态写入） */
    fun cancelPending() {
        highlightJob?.cancel()
    }

    // ==================== VisualTransformation 实现 ====================

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text

        if (raw.isEmpty()) {
            return TransformedText(applyBaseColor(raw), OffsetMapping.Identity)
        }

        // 小文档：同步计算（与原行为一致，即时高亮，无感知差异）
        if (raw.length <= SYNC_THRESHOLD_CHARS) {
            return TransformedText(computeHighlight(raw), OffsetMapping.Identity)
        }

        // 大文档：命中缓存直接返回；未命中先返回纯文本（不阻塞输入），
        // 后台 + debounce 计算完成后自增版本号触发重组，再次执行时命中缓存
        cacheVersion.intValue // 订阅快照状态（本行仅读取，建立依赖）
        synchronized(highlightCache) {
            highlightCache[raw]?.let { return TransformedText(it, OffsetMapping.Identity) }
        }
        scheduleAsyncHighlight(raw)
        return TransformedText(applyBaseColor(raw), OffsetMapping.Identity)
    }

    /** 后台 + debounce 计算大文档高亮，结果写入 LRU 缓存并触发重组 */
    private fun scheduleAsyncHighlight(raw: String) {
        highlightJob?.cancel()
        highlightJob = highlightScope.launch {
            delay(HIGHLIGHT_DEBOUNCE_MS)
            val highlighted = computeHighlight(raw)
            synchronized(highlightCache) { highlightCache[raw] = highlighted }
            // 快照状态允许跨线程写入；自增后订阅方（filter）重组并命中缓存
            cacheVersion.intValue++
        }
    }

    /** 仅铺基础文本色（空文本 / 异步计算期间的过渡形态） */
    private fun applyBaseColor(raw: String): AnnotatedString {
        val builder = AnnotatedString.Builder(raw)
        if (raw.isNotEmpty()) {
            builder.addStyle(SpanStyle(color = colors.text), 0, raw.length)
        }
        return builder.toAnnotatedString()
    }

    /** 全量计算 11 种元素的语法高亮（同步路径直接调用；异步路径在后台线程调用） */
    private fun computeHighlight(raw: String): AnnotatedString {
        val builder = AnnotatedString.Builder(raw)

        // 应用基础文本颜色
        builder.addStyle(
            SpanStyle(color = colors.text),
            0, raw.length
        )

        // 按优先级应用高亮（后面的可能覆盖前面的）
        applyHeadingHighlight(raw, builder)
        applyBoldItalicHighlight(raw, builder)
        applyBoldHighlight(raw, builder)
        applyItalicHighlight(raw, builder)
        applyStrikethroughHighlight(raw, builder)
        applyCodeHighlight(raw, builder)
        applyLinkHighlight(raw, builder)
        applyListHighlight(raw, builder)
        applyQuoteHighlight(raw, builder)
        applyRuleHighlight(raw, builder)

        return builder.toAnnotatedString()
    }

    // ==================== 高亮方法 ====================

    private fun applyHeadingHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in HEADING_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.heading,
                    fontWeight = FontWeight.Bold
                ),
                start, end
            )
        }
    }

    private fun applyBoldItalicHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in BOLD_ITALIC_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.boldItalic,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                ),
                start, end
            )
        }
    }

    private fun applyBoldHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in BOLD_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.bold,
                    fontWeight = FontWeight.Bold
                ),
                start, end
            )
        }
    }

    private fun applyItalicHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in ITALIC_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.italic,
                    fontStyle = FontStyle.Italic
                ),
                start, end
            )
        }
    }

    private fun applyStrikethroughHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in STRIKETHROUGH_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.strikethrough,
                    textDecoration = TextDecoration.LineThrough
                ),
                start, end
            )
        }
    }

    private fun applyCodeHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in CODE_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.code
                ),
                start, end
            )
        }
    }

    private fun applyLinkHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in LINK_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.link
                ),
                start, end
            )
        }
    }

    private fun applyListHighlight(raw: String, builder: AnnotatedString.Builder) {
        // 无序列表：- * +
        for (match in LIST_UNORDERED_PATTERN.findAll(raw)) {
            val start = match.groupValues[1].length.coerceAtMost(match.range.first)
            val markerStart = match.range.first + match.groupValues[1].length
            val markerEnd = match.range.last
            builder.addStyle(
                SpanStyle(
                    color = colors.listMarker,
                    fontWeight = FontWeight.Bold
                ),
                markerStart, markerEnd
            )
        }
        // 有序列表：1. 2) 等
        for (match in LIST_ORDERED_PATTERN.findAll(raw)) {
            val markerStart = match.range.first + match.groupValues[1].length
            val markerEnd = match.range.last
            builder.addStyle(
                SpanStyle(
                    color = colors.listMarker,
                    fontWeight = FontWeight.Bold
                ),
                markerStart, markerEnd
            )
        }
    }

    private fun applyQuoteHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in QUOTE_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.quote,
                    fontWeight = FontWeight.Bold
                ),
                start, end
            )
        }
    }

    private fun applyRuleHighlight(raw: String, builder: AnnotatedString.Builder) {
        for (match in RULE_PATTERN.findAll(raw)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.rule
                ),
                start, end
            )
        }
    }
}