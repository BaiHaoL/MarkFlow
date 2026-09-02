package com.markflow.editor.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.ext.tables.TableSpan
import io.noties.markwon.ext.tables.TableRowSpan
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.markflow.editor.domain.model.MarkdownSection
import com.markflow.editor.util.MarkdownParser
import com.markflow.editor.util.MarkwonConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markdown 预览组件（Column + verticalScroll 全量渲染版）
 *
 * 将 Markdown 文本拆分为普通文本段和代码块段，分别渲染：
 * - 文本段：使用 Markwon 渲染为富文本（支持表格、图片、任务列表等）
 * - 代码块：使用 [CodeBlockCard] 渲染为 VS Code 风格独立卡片
 *
 * 使用 Column + verticalScroll 替代 LazyColumn，一次性组合所有 item，
 * 消除 LazyColumn 高度估算误差导致的向上滚动跳动问题。
 * 对于 Markdown 文档（通常 5-50 个 section），性能完全可接受。
 *
 * 核心特性：
 * - 代码块折叠/展开状态按 ID 隔离
 * - 复制反馈状态按代码块 ID 隔离
 * - 搜索关键词高亮：在文本段 Spannable 上叠加 BackgroundColorSpan
 *
 * @param markdownText       原始 Markdown 文本
 * @param baseDir            当前 .md 文件所在目录的绝对路径（用于解析相对路径图片）
 * @param isDarkTheme        当前是否为深色主题
 * @param searchQuery        搜索关键词（为空则不高亮）
 * @param currentSearchIndex 当前高亮的匹配项索引（来自 ViewModel，基于原始 Markdown）
 * @param scrollState        滚动状态（由父组件管理）
 * @param modifier           修饰符
 */
@Composable
fun MarkdownPreview(
    markdownText: String,
    baseDir: String = "",
    isDarkTheme: Boolean = false,
    searchQuery: String = "",
    currentSearchIndex: Int = -1,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 预处理管线：图片路径 → LaTeX 环境 → 行内 LaTeX 公式
    val processedResult = remember(markdownText, baseDir) {
        val img = MarkwonConfig.preprocessImagePaths(markdownText, baseDir)
        val withEnvs = MarkwonConfig.preprocessLatexEnvironments(img.text)
        val text = MarkwonConfig.preprocessLatexInline(withEnvs)
        MarkwonConfig.ImagePreprocessResult(text, img.missingPlaceholders)
    }
    val processedText = processedResult.text
    val missingPlaceholders = processedResult.missingPlaceholders
    // 缺失图片占位文本的灰色弱化颜色（随主题切换）
    val missingImageGray = if (isDarkTheme) {
        MarkwonConfig.MISSING_IMAGE_GRAY_DARK
    } else {
        MarkwonConfig.MISSING_IMAGE_GRAY_LIGHT
    }

    // 解析为分段（try-catch 保护，防止解析异常导致整个组合树崩溃）
    val sections = remember(processedText) {
        try {
            MarkdownParser.parse(processedText)
        } catch (_: Exception) {
            listOf(
                MarkdownSection.Text(
                    id = "parse_error",
                    content = "Markdown 解析失败，请检查文件内容",
                    startLine = 0,
                    endLine = 0
                )
            )
        }
    }

    // 完整 Markwon 实例（用于文本段渲染）
    val markwon = remember(isDarkTheme) {
        try {
            MarkwonConfig.create(context, isDarkTheme)
        } catch (_: Exception) {
            io.noties.markwon.Markwon.builder(context).build()
        }
    }

    val textColor = if (isDarkTheme) {
        android.graphics.Color.parseColor("#E8EAED")
    } else {
        android.graphics.Color.parseColor("#1F1F1F")
    }

    // ---- 状态管理：按代码块 ID 隔离 ----
    val collapseStates = remember { mutableStateMapOf<String, Boolean>() }
    val copyFeedbackStates = remember { mutableStateMapOf<String, Boolean>() }

    // 确定当前搜索匹配项所在的段落索引
    val currentMatchSectionIndex = remember(searchQuery, currentSearchIndex, processedText, sections) {
        if (searchQuery.isEmpty() || currentSearchIndex < 0) return@remember -1
        findSectionContainingMatch(
            markdownText = processedText,
            sections = sections,
            searchQuery = searchQuery,
            matchIndex = currentSearchIndex
        )
    }

    // ---- Column + verticalScroll 全量渲染 ----
    // 使用 Column 替代 LazyColumn：一次性组合所有 item，彻底消除高度估算误差。
    // LazyColumn 只测量可见区域附近的 item，视口外的表格被低估高度，
    // 向上滚动时实际测量值修正导致滚动补偿跳动。Column 无此问题。
    Column(
        modifier = modifier.verticalScroll(scrollState)
    ) {
        sections.forEachIndexed { index, section ->
            when (section) {
                is MarkdownSection.Text -> {
                    MarkdownTextSectionView(
                        content = section.content,
                        markwon = markwon,
                        textColor = textColor,
                        searchQuery = searchQuery,
                        isCurrentMatchSection = index == currentMatchSectionIndex,
                        missingImagePlaceholders = missingPlaceholders,
                        missingImageGray = missingImageGray
                    )
                }

                is MarkdownSection.Code -> {
                    val codeId = section.id
                    val isCollapsed = collapseStates[codeId] ?: false
                    val showFeedback = copyFeedbackStates[codeId] ?: false

                    CodeBlockCard(
                        language = section.info.language,
                        rawCode = section.info.rawCode,
                        isDarkTheme = isDarkTheme,
                        isCollapsed = isCollapsed,
                        showCopyFeedback = showFeedback,
                        onToggleCollapse = {
                            collapseStates[codeId] = !(collapseStates[codeId] ?: false)
                        },
                        onCopyStart = {
                            copyFeedbackStates[codeId] = true
                            scope.launch {
                                delay(1500)
                                copyFeedbackStates[codeId] = false
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 文本段渲染组件
 *
 * 使用 AndroidView + Markwon 渲染单个文本段。
 * 支持搜索关键词高亮（独立于其他文本段）。
 */
@Composable
private fun MarkdownTextSectionView(
    content: String,
    markwon: io.noties.markwon.Markwon,
    textColor: Int,
    searchQuery: String,
    isCurrentMatchSection: Boolean,
    missingImagePlaceholders: List<String> = emptyList(),
    missingImageGray: Int = 0
) {
    var lastContent by remember { mutableStateOf("") }
    var lastMarkwon by remember { mutableStateOf<io.noties.markwon.Markwon?>(null) }
    var lastQuery by remember { mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            object : TextView(ctx) {
                override fun onTextContextMenuItem(id: Int): Boolean {
                    if (id == android.R.id.copy) {
                        val selStart = selectionStart
                        val selEnd = selectionEnd
                        if (selStart >= 0 && selEnd > selStart) {
                            val min = selStart.coerceAtMost(selEnd)
                            val max = selStart.coerceAtLeast(selEnd)
                            val spanned = text as? Spanned
                            if (spanned != null) {
                                val tableSpans = spanned.getSpans(min, max, TableSpan::class.java)
                                if (tableSpans.isNotEmpty()) {
                                    val formatted = formatTableAsMarkdown(spanned, min, max)
                                    if (formatted != null) {
                                        val result = super.onTextContextMenuItem(id)
                                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Markdown Table", formatted))
                                        return result
                                    }
                                }
                                // 处理 ReplacementSpan（公式 ImageSpan）：展开选区到 Span 边界后复制完整原文
                                val replacementSpans = spanned.getSpans(
                                    min, max, android.text.style.ReplacementSpan::class.java
                                )
                                if (replacementSpans.isNotEmpty()) {
                                    val spanStart = replacementSpans.minOf { spanned.getSpanStart(it) }
                                    val spanEnd = replacementSpans.maxOf { spanned.getSpanEnd(it) }
                                    val expandedMin = minOf(min, spanStart)
                                    val expandedMax = maxOf(max, spanEnd)
                                    val textToCopy = spanned.subSequence(expandedMin, expandedMax).toString()
                                    val result = super.onTextContextMenuItem(id)
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("text", textToCopy))
                                    return result
                                }
                            }
                        }
                    }
                    return super.onTextContextMenuItem(id)
                }

                override fun setText(text: CharSequence?, type: BufferType?) {
                    super.setText(text, type)
                    // 同步修复公式 Span 对齐：确保首次测量即使用 VerticalAlignedLatexSpan。
                    // 移除 postDelayed 二次修复：500ms 延迟回调中修改 Spannable 会触发
                    // requestLayout() → 行高变化 → LazyColumn 滚动补偿跳动。
                    // AsyncDrawable 异步加载完成后公式尺寸若变化，Markwon 内部会自行触发
                    // 重绘，无需额外修复。
                    try {
                        MarkwonConfig.fixFormulaSpanAlignment(this)
                    } catch (_: Exception) { }
                }
            }.apply {
                setTextIsSelectable(true)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
                setLineSpacing(8f, 1.0f)
                val paddingPx = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                setTextColor(textColor)
                try {
                    markwon.setMarkdown(this, content)
                } catch (_: Exception) {
                    this.text = content
                }
                try {
                    MarkwonConfig.applyMissingImagePlaceholders(this, missingImagePlaceholders, missingImageGray)
                } catch (_: Exception) { }
                lastContent = content
                lastMarkwon = markwon
            }
        },
        update = { textView ->
            // 视图已脱离窗口时跳过渲染，防止在组合树移除过程中操作已销毁的视图
            if (!textView.isAttachedToWindow) return@AndroidView

            textView.setTextColor(textColor)

            // 检测 markwon 实例变化（主题切换时）或内容变化，触发重新渲染
            val markwonChanged = markwon !== lastMarkwon
            val needsRender = content != lastContent || markwonChanged
            if (needsRender) {
                lastContent = content
                lastMarkwon = markwon
                lastQuery = ""
                // 同步渲染：在 update 回调中直接设置内容，确保 Compose 首帧测量
                // 时 TextView 已包含正确内容，避免 post {} 延迟导致 LazyColumn 在
                // 旧内容高度上测量、随后内容变化触发滚动补偿跳动的 bug。
                try {
                    markwon.setMarkdown(textView, content)
                } catch (_: Exception) {
                    textView.text = content
                }
                try {
                    MarkwonConfig.applyMissingImagePlaceholders(textView, missingImagePlaceholders, missingImageGray)
                } catch (_: Exception) { }
                if (searchQuery.isNotEmpty()) {
                    applySectionSearchHighlights(textView, searchQuery, isCurrentMatchSection)
                }
            } else {
                if (searchQuery.isEmpty() && lastQuery.isNotEmpty()) {
                    lastQuery = ""
                    textView.post {
                        if (textView.isAttachedToWindow) {
                            clearSearchHighlights(textView)
                        }
                    }
                } else if (searchQuery.isNotEmpty() && searchQuery != lastQuery) {
                    lastQuery = searchQuery
                    textView.post {
                        if (textView.isAttachedToWindow) {
                            applySectionSearchHighlights(textView, searchQuery, isCurrentMatchSection)
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 在文本段 TextView 上应用搜索高亮
 *
 * 与全局搜索高亮类似，但仅作用于当前文本段。
 * 如果 isCurrentMatchSection 为 true，第一个匹配项使用橙色高亮（当前匹配），
 * 其余使用浅蓝色高亮。
 */
private fun applySectionSearchHighlights(
    textView: TextView,
    searchQuery: String,
    isCurrentMatchSection: Boolean
) {
    val spannable = (textView.text as? android.text.Spannable) ?: return

    // 移除现有高亮
    val existingSpans = spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
    existingSpans.forEach { spannable.removeSpan(it) }

    if (searchQuery.isEmpty()) return

    val renderedText = spannable.toString()
    val matches = mutableListOf<Pair<Int, Int>>()
    var searchStart = 0
    val queryLen = searchQuery.length
    while (searchStart < renderedText.length) {
        val found = renderedText.indexOf(searchQuery, searchStart, ignoreCase = true)
        if (found == -1) break
        val end = (found + queryLen).coerceAtMost(spannable.length)
        if (found in 0 until end && end <= spannable.length) {
            matches.add(found to end)
        }
        searchStart = found + queryLen
    }

    if (matches.isEmpty()) return

    val currentColor = android.graphics.Color.parseColor("#FFB74D")
    val otherColor = android.graphics.Color.parseColor("#81D4FA")

    matches.forEachIndexed { index, (start, end) ->
        spannable.setSpan(
            BackgroundColorSpan(
                if (isCurrentMatchSection && index == 0) currentColor else otherColor
            ),
            start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

/**
 * 清除文本段 TextView 上的搜索高亮
 */
private fun clearSearchHighlights(textView: TextView) {
    val spannable = (textView.text as? android.text.Spannable) ?: return
    val existingSpans = spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
    existingSpans.forEach { spannable.removeSpan(it) }
}

/**
 * 从 Span 中提取表格数据，重构为等效的 Markdown 源码格式。
 *
 * 通过反射读取 TableRowSpan 的 private cells 字段获取 Cell 文本和对齐方式，
 * 输出 | Col1 | Col2 |\n|------|------|\n| Val1 | Val2 | 格式。
 * 该格式与编辑模式下的原始 Markdown 表格功能等价，可直接粘贴回编辑器渲染。
 */
private fun formatTableAsMarkdown(spanned: Spanned, start: Int, end: Int): String? {
    val tableSpans = spanned.getSpans(start, end, TableSpan::class.java)
    if (tableSpans.isEmpty()) return null

    val tableSpan = tableSpans.first()
    val tableStart = spanned.getSpanStart(tableSpan)
    val tableEnd = spanned.getSpanEnd(tableSpan)

    val rowSpans = spanned.getSpans(tableStart, tableEnd, TableRowSpan::class.java)
        .sortedBy { spanned.getSpanStart(it) }
    if (rowSpans.isEmpty()) return null

    val cellsField = try {
        TableRowSpan::class.java.getDeclaredField("cells").apply { isAccessible = true }
    } catch (_: Exception) {
        return null
    }

    data class CellData(val text: String, val alignment: Int)

    val rows = rowSpans.mapNotNull { rowSpan ->
        try {
            @Suppress("UNCHECKED_CAST")
            val cells = cellsField.get(rowSpan) as? List<TableRowSpan.Cell> ?: return@mapNotNull null
            cells.map { CellData(it.text().toString().trim(), it.alignment()) }
        } catch (_: Exception) {
            null
        }
    }
    if (rows.isEmpty()) return null

    val colCount = rows.maxOf { it.size }
    val colWidths = IntArray(colCount)
    rows.forEach { row ->
        row.forEachIndexed { i, cell ->
            colWidths[i] = maxOf(colWidths[i], cell.text.length)
        }
    }

    return buildString {
        rows.forEachIndexed { rowIndex, row ->
            append("| ")
            row.forEachIndexed { colIndex, cell ->
                append(cell.text.padEnd(colWidths[colIndex]))
                append(" |")
                if (colIndex < colCount - 1) append(" ")
            }
            for (i in row.size until colCount) {
                append(" ".repeat(colWidths[i]))
                append(" |")
                if (i < colCount - 1) append(" ")
            }
            append("\n")

            // 表头后插入分隔行
            if (rowIndex == 0) {
                append("|")
                for (colIndex in 0 until colCount) {
                    val align =
                        if (colIndex < row.size) row[colIndex].alignment else TableRowSpan.ALIGN_LEFT
                    val dashes = "-".repeat(colWidths[colIndex].coerceAtLeast(3))
                    append(" ")
                    when (align) {
                        TableRowSpan.ALIGN_CENTER -> append(":").append(dashes).append(":")
                        TableRowSpan.ALIGN_RIGHT -> append(dashes).append(":")
                        else -> append(dashes).append("-")
                    }
                    append("|")
                }
                append("\n")
            }
        }
    }.trimEnd()
}

/**
 * 查找当前搜索匹配项所在的段落索引
 *
 * 将 ViewModel 的全局字符偏移量转换为行号，然后匹配到对应的段落。
 *
 * @param markdownText 原始 Markdown 文本
 * @param sections 分段列表
 * @param searchQuery 搜索关键词
 * @param matchIndex 全局匹配项索引（基于 markdownText）
 * @return 包含该匹配项的段落索引，-1 表示未找到
 */
private fun findSectionContainingMatch(
    markdownText: String,
    sections: List<MarkdownSection>,
    searchQuery: String,
    matchIndex: Int
): Int {
    if (searchQuery.isEmpty() || matchIndex < 0) return -1

    // 在原始文本中查找第 matchIndex 个匹配项
    var count = 0
    var searchStart = 0
    val queryLen = searchQuery.length
    while (searchStart < markdownText.length) {
        val found = markdownText.indexOf(searchQuery, searchStart, ignoreCase = true)
        if (found == -1) break
        if (count == matchIndex) {
            // 将字符偏移量转换为行号
            val line = markdownText.substring(0, found.coerceAtMost(markdownText.length))
                .count { it == '\n' }
            // 查找包含该行的段落
            return sections.indexOfFirst { section ->
                line in section.startLine until section.endLine
            }
        }
        count++
        searchStart = found + queryLen
    }
    return -1
}