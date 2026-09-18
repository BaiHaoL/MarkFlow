package com.markflow.editor.ui.components

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.State
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

        // 围栏代码块标记：行首(≤3 空格) 3+ 连续反引号或波浪号，后接可选语言标识。
        // 用于识别 ``` ``` / ~~~ ~~~ 区间，区间内屏蔽所有语法高亮（代码区保持纯文本）。
        private val FENCE_PATTERN = Regex("""^[ ]{0,3}(`{3,}|~{3,})(.*)$""")

        /**
         * 小于该字符数的文档在 filter 内同步高亮（即时体验）；超过则异步计算。
         *
         * 原值 16_000 过低：2~3 万字符的中型 Markdown（几万字节）会被误判为"大文档"
         * 走异步路径。异步路径若反复触发（滑动/光标跟随导致 BasicTextField 频繁重组、
         * filter 每次未命中缓存都 cancel+restart 后台计算），80ms debounce 永远等不满，
         * 高亮永远无法上屏，表现为"打开后无加粗/无高亮"，且主线程每次 applyBaseColor
         * 重建整篇 AnnotatedString 造成滚动卡顿。同步路径对 11 种正则全量扫描在
         * 数万字符上是毫秒级，远优于异步路径的抖振。故提高到 64K 覆盖中型文档；
         * 真正的大文档（>1MB）本就不全文载入（走分页只读），不会进入本路径。
         */
        private const val SYNC_THRESHOLD_CHARS = 64_000

        /**
         * 编辑态富结构降级阈值（非空段落数预算）。
         *
         * filter 中「非空段落数 > 本值」的文档直接降级为「仅标题高亮」（不再进本方法）。
         * 根因：段落数决定 TextParagraph/StaticLayout 数量，富结构（一行一段、塞满列表/引用/
         * 分隔线）文档在等大字符下可差数倍，且 Relayout/draw 成本随「span 数 × 段落数」增长
         * ——probe_md_28k（28K，1928 段）真机每帧 Record View#draw 187~258ms，而更长的
         * Pandas（792 段）反而流畅。故降级按段落数而非字符数触发，普通/小文件保留全量高亮。
         *
         * 注意分层叠加：本阈值范围在 EDIT_LIVE_HIGHLIGHT_CHARS(32K) 之内，即「≤32K 且
         * 段数超预算」走本降级；>32K 已被 EDIT_LIVE_HIGHLIGHT_CHARS 拦截为仅标题。参见
         * filter 的 gate 顺序。
         */
        private const val RICH_PARAGRAPH_BUDGET = 1_500

        /**
         * 编辑态实时高亮上限（字符数）：超过该大小的 .md 在编辑模式下不做全量语法高亮，
         * 只保留标题高亮（结构导航），其余纯文本（预览模式仍走 Markwon 全量高亮）。
         *
         * 根因：编辑时每次键入 raw 都变 → highlightCache 永不命中 → 全量正则重算 +
         * StaticLayout 全量 relayout，在低内存/中端机（荣耀 X50 骁龙6Gen1 + MagicOS
         * 激进回收）上累积为卡顿或 OOM。Markor 官方对同类问题的解法即「大文件禁高亮」。
         *
         * 分层（编辑态）：≤本值 走语法高亮（其中段数 > RICH_PARAGRAPH_BUDGET 的富结构
         * 文件降级为仅标题）；>本值 仅标题高亮（长文档编辑开销高）。
         *
         * 注意：本值 < SYNC_THRESHOLD_CHARS(64K) 后，filter 内「>本值 被上方拦截直接返回」，
         * 使下方 SYNC 异步分支不可达（dead code），保留作可逆——将来恢复大文档实时高亮，
         * 调高本值即可。
         */
        private const val EDIT_LIVE_HIGHLIGHT_CHARS = 32_000

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

    /**
     * 异步高亮版本号（暴露给 Composable 层读取）。
     *
     * Composable 层将它纳入 visualTransformation 的 remember key，并在 key 变化时
     * 创建一个新的 VisualTransformation 包装实例。BasicTextField 检测到 VT 实例
     * 变化后会重新调用 filter，此时缓存已就绪，高亮得以正确上屏。
     *
     * 不能仅靠 filter 内部读取 cacheVersion 来触发重跑——VisualTransformation.filter
     * 在布局阶段被调用，其中的 State 读取不一定能可靠地触发 filter 再次执行
     *（表现为"长按后才有高亮"：长按改变了 selection → BasicTextField 重组 →
     * filter 重跑 → 命中缓存 → 高亮出现）。
     */
    val highlightVersion: State<Int> get() = cacheVersion

    private var highlightJob: Job? = null
    private val highlightScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在后台计算（或等待 debounce）的原始文本；用于去重，避免同一内容反复 cancel/restart */
    private var pendingRaw: String? = null

    /** 纯文本过渡态的缓存：异步计算期间 filter 会反复被调用，缓存避免主线程重复构建整篇 */
    private var plainCacheKey: String? = null
    private var plainCacheValue: AnnotatedString? = null

    /** 「仅标题高亮」过渡态的缓存：>阈值文档每次键入 raw 变，但布局阶段同一 raw 会被反复调用 */
    private var headingOnlyCacheKey: String? = null
    private var headingOnlyCacheValue: AnnotatedString? = null

    /**
     * TransformedText 实例缓存（主线程 only）。
     *
     * 卡顿根因：filter 每次返回 new TransformedText(annotated, ...)，即使 annotated 是
     * 同一个缓存对象，TransformedText 实例引用不等 → BasicTextField 检测到变化 →
     * 触发 StaticLayout 全量重建（26K 字符 + 960 span 要几百 ms）→ 滑动卡顿。
     *
     * 修复：同一 (raw, annotated 引用) 复用同一 TransformedText 实例，BasicTextField
     * 检测到引用相等跳过 relayout。annotated 引用变化时（纯文本→高亮）才创建新实例。
     */
    private var ttCacheRaw: String? = null
    private var ttCacheAnnotated: AnnotatedString? = null
    private var ttCacheValue: TransformedText? = null

    private fun transformedText(raw: String, annotated: AnnotatedString): TransformedText {
        // 同一 raw + 同一 annotated 引用 → 复用 TransformedText，避免触发 relayout
        if (ttCacheRaw == raw && ttCacheAnnotated === annotated && ttCacheValue != null) {
            return ttCacheValue!!
        }
        val tt = TransformedText(annotated, OffsetMapping.Identity)
        ttCacheRaw = raw
        ttCacheAnnotated = annotated
        ttCacheValue = tt
        return tt
    }

    /** 取消未完成的异步计算（组件销毁时调用，防对脱离组合的状态写入） */
    fun cancelPending() {
        highlightJob?.cancel()
        pendingRaw = null
    }

    // ==================== VisualTransformation 实现 ====================

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text

        if (raw.isEmpty()) {
            return transformedText(raw, applyBaseColor(raw))
        }

        // 编辑态实时高亮降级：超过阈值的文档只保留标题高亮（结构导航），其余纯文本。
        // 标题 span 仅数十个、MULTILINE 正则扫描毫秒级，几乎无开销；全量高亮（加粗/代码/
        // 链接等上千 span）才是每次键入全量重算 + relayout 累积卡顿/OOM 的元凶。
        if (raw.length > EDIT_LIVE_HIGHLIGHT_CHARS) {
            return transformedText(raw, applyHeadingOnlyCached(raw))
        }

        // 富结构降级（方案1止损）：非空段落数超预算的文件（如每行一段、塞满列表/引用/
        // 分隔线的 markdown）编辑态直接降为「仅标题高亮」。根因（真机 trace 定案）：这类
        // 文件每段一个 StaticLayout + 数百富 span，每次屏幕失效 Compose 都整篇重录
        // draw-op，实测 Record View#draw 187~258ms/帧；降级后 span 骤减、绘制骤快，
        // 代价是失去加粗/代码/链接等富高亮。判据用段数而非字符数（见 RICH_PARAGRAPH_BUDGET）。
        if (paragraphCountOf(raw) > RICH_PARAGRAPH_BUDGET) {
            return transformedText(raw, applyHeadingOnlyCached(raw))
        }

        // 阈值内文档：同步计算（首次计算后缓存，后续 filter 调用直接命中缓存）
        if (raw.length <= SYNC_THRESHOLD_CHARS) {
            synchronized(highlightCache) {
                highlightCache[raw]?.let { return transformedText(raw, it) }
            }
            val highlighted = try {
                computeHighlight(raw)
            } catch (t: Throwable) {
                // 正则回溯等异常兜底：回退纯文本，避免编辑热路径崩溃
                return transformedText(raw, applyBaseColorCached(raw))
            }
            synchronized(highlightCache) {
                highlightCache[raw] = highlighted
            }
            return transformedText(raw, highlighted)
        }

        // 大文档：命中缓存直接返回；未命中先返回纯文本（不阻塞输入），
        // 后台 + debounce 计算完成后自增版本号触发重组，再次执行时命中缓存
        cacheVersion.intValue // 订阅快照状态（本行仅读取，建立依赖）
        synchronized(highlightCache) {
            highlightCache[raw]?.let { return transformedText(raw, it) }
        }
        // 去重：同一内容已有在途计算（含 debounce 等待）时不再重复 cancel/restart。
        // 否则滑动/光标跟随导致的频繁重组会让 debounce 永远等不满、计算永远无法完成，
        // 表现为"始终无高亮"。
        if (pendingRaw != raw) {
            scheduleAsyncHighlight(raw)
        }
        return transformedText(raw, applyBaseColorCached(raw))
    }

    /** 后台 + debounce 计算大文档高亮，结果写入 LRU 缓存并触发重组 */
    private fun scheduleAsyncHighlight(raw: String) {
        highlightJob?.cancel()
        pendingRaw = raw
        highlightJob = highlightScope.launch {
            delay(HIGHLIGHT_DEBOUNCE_MS)
            val highlighted = try {
                computeHighlight(raw)
            } catch (t: Throwable) {
                // 正则回溯等异常兜底：放弃本次高亮，保持纯文本，避免后台线程崩溃
                pendingRaw = null
                return@launch
            }
            synchronized(highlightCache) { highlightCache[raw] = highlighted }
            pendingRaw = null
            // 快照状态允许跨线程写入；自增后订阅方（filter）重组并命中缓存
            cacheVersion.intValue++
        }
    }

    /** 仅铺基础文本色（空文本 / 异步计算期间的过渡形态），带缓存避免主线程重复构建 */
    private fun applyBaseColor(raw: String): AnnotatedString {
        val builder = AnnotatedString.Builder(raw)
        if (raw.isNotEmpty()) {
            builder.addStyle(SpanStyle(color = colors.text), 0, raw.length)
        }
        return builder.toAnnotatedString()
    }

    /** 带缓存的基础色：filter 在异步计算期间会被反复调用，缓存避免重复构建整篇 AnnotatedString */
    private fun applyBaseColorCached(raw: String): AnnotatedString {
        if (plainCacheKey == raw && plainCacheValue != null) return plainCacheValue!!
        val v = applyBaseColor(raw)
        plainCacheKey = raw
        plainCacheValue = v
        return v
    }

    /** 带缓存的「仅标题高亮」：>阈值文档编辑态只着色标题，其余纯文本 */
    private fun applyHeadingOnlyCached(raw: String): AnnotatedString {
        if (headingOnlyCacheKey == raw && headingOnlyCacheValue != null) return headingOnlyCacheValue!!
        val v = applyHeadingOnly(raw)
        headingOnlyCacheKey = raw
        headingOnlyCacheValue = v
        return v
    }

    /** 仅标题高亮（大文档编辑态的结构化降级：标题着色帮助定位，其余纯文本） */
    private fun applyHeadingOnly(raw: String): AnnotatedString {
        val builder = AnnotatedString.Builder(raw)
        // 围栏代码块区间内的 # 不被当作标题（代码区保持纯文本）
        applyHeadingHighlight(raw, builder, scanFencedBlocks(raw))
        return builder.toAnnotatedString()
    }

    /**
     * 全量计算语法高亮（同步路径直接调用；异步路径在后台线程调用）。
     *
     * 仅对「段数 ≤ [RICH_PARAGRAPH_BUDGET]」的文档调用（富结构文件已在 filter 被
     * 降级为仅标题高亮，不会进入本方法）。注释：已去掉全篇基础色 span——BasicTextField
     * 的 textStyle 已设 onSurface 默认色，与 colors.text 基本一致，省一个覆盖全篇的
     * span 可减少 StaticLayout 的 span 排序/二分查找开销。
     */
    private fun computeHighlight(raw: String): AnnotatedString {
        val builder = AnnotatedString.Builder(raw)

        // 围栏代码块区间：区间内屏蔽所有语法高亮（代码区保持纯文本，防 ``` ``` 内部
        // 的 #、**、- 列表等被全局正则误染）
        val fences = scanFencedBlocks(raw)

        // 按优先级应用高亮（后面的可能覆盖前面的）
        applyHeadingHighlight(raw, builder, fences)
        applyBoldItalicHighlight(raw, builder, fences)
        applyBoldHighlight(raw, builder, fences)
        applyItalicHighlight(raw, builder, fences)
        applyStrikethroughHighlight(raw, builder, fences)
        applyCodeHighlight(raw, builder, fences)
        applyLinkHighlight(raw, builder, fences)
        applyListHighlight(raw, builder, fences)
        applyQuoteHighlight(raw, builder, fences)
        applyRuleHighlight(raw, builder, fences)

        return builder.toAnnotatedString()
    }

    /**
     * 统计非空段落数（以空行分隔的连续非空行视为多段，纯空白行不计入）。
     * 单趟 O(n) 字符扫描，不产生 split 数组，成本远低于随后高亮正则，可每次调用。
     */
    private fun paragraphCountOf(raw: String): Int {
        var count = 0
        var inWord = false // 当前段是否已出现非空白字符
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            when {
                // 遇到段落分隔即结算当前段
                c == '\n' || c == '\r' -> {
                    if (inWord) count++
                    inWord = false
                    if (c == '\r' && i + 1 < n && raw[i + 1] == '\n') i++
                }
                // 空白不结束段（制表/空格并入当前段）
                c == ' ' || c == '\t' -> Unit
                // 首个非空白字符：开始一段
                else -> inWord = true
            }
            i++
        }
        if (inWord) count++
        return count
    }

    // ==================== 高亮方法 ====================

    /**
     * 扫描围栏代码块区间（``` ``` / ~~~ ~~~），区间内屏蔽所有语法高亮。
     *
     * 用栈式配对处理嵌套/异长 fence：`...``` 开 ` ```` `...``` ` ` ``` ``` 由与外层相同字符
     * 且长度 ≥ 外层长度的 fence 行闭合；不同字符或更短的 fence 行视为内容不闭合。
     * 未闭合者（文档被截断）容忍到文末。返回已按起始位置升序、互不重叠的区间列表。
     */
    private fun scanFencedBlocks(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var openStart = -1
        var openChar = '`'
        var openLen = 0
        var lineStart = 0
        // 逐字符定位行边界（避免 split 产生额外数组开销）
        val n = text.length
        while (lineStart <= n) {
            val lf = text.indexOf('\n', lineStart)
            val lineEnd = if (lf == -1) n else lf
            if (lineStart == lineEnd || text[lineStart] != '\n') {
                val line = text.substring(lineStart, lineEnd)
                val m = FENCE_PATTERN.find(line)
                if (m != null) {
                    val marker = m.groupValues[1]
                    val ch = marker[0]
                    val len = marker.length
                    if (openStart < 0) {
                        openStart = lineStart
                        openChar = ch
                        openLen = len
                    } else if (ch == openChar && len >= openLen) {
                        ranges.add(openStart..(lineEnd - 1))
                        openStart = -1
                    }
                }
            }
            if (lf == -1) break
            lineStart = lf + 1
        }
        if (openStart >= 0) ranges.add(openStart..(text.length - 1))
        return ranges
    }

    /** 判断 offset 是否落在任一围栏代码块区间内（fences 已升序且互不重叠，用二分） */
    private fun isInFences(fences: List<IntRange>, offset: Int): Boolean {
        if (fences.isEmpty()) return false
        var lo = 0
        var hi = fences.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = fences[mid]
            when {
                offset < r.first -> hi = mid - 1
                offset > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun applyHeadingHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in HEADING_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与标题高亮
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

    private fun applyBoldItalicHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in BOLD_ITALIC_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与缩体高亮
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

    private fun applyBoldHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in BOLD_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与缩体高亮
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

    private fun applyItalicHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in ITALIC_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与斜体高亮
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

    private fun applyStrikethroughHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in STRIKETHROUGH_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与删除线高亮
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

    private fun applyCodeHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in CODE_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与行内代码高亮
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.code
                ),
                start, end
            )
        }
    }

    private fun applyLinkHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in LINK_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与链接高亮
            val end = match.range.last + 1
            builder.addStyle(
                SpanStyle(
                    color = colors.link
                ),
                start, end
            )
        }
    }

    private fun applyListHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        // 无序列表：- * +
        for (match in LIST_UNORDERED_PATTERN.findAll(raw)) {
            val markerStart = match.range.first + match.groupValues[1].length
            if (isInFences(fences, markerStart)) continue // 围栏代码块内不参与列表高亮
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
            if (isInFences(fences, markerStart)) continue // 围栏代码块内不参与列表高亮
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

    private fun applyQuoteHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in QUOTE_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与引用高亮
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

    private fun applyRuleHighlight(raw: String, builder: AnnotatedString.Builder, fences: List<IntRange>) {
        for (match in RULE_PATTERN.findAll(raw)) {
            val start = match.range.first
            if (isInFences(fences, start)) continue // 围栏代码块内不参与分隔线高亮
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