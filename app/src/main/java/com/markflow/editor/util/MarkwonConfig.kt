package com.markflow.editor.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Spannable
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import java.io.File

/**
 * Markwon 自定义配置工具类
 *
 * 核心特性：
 * 1. 完整 Prism4j 语法高亮（50+ 语言，kapt 自动生成语法定义）
 * 2. 浅色/深色主题自动适配
 * 3. 代码块使用等宽字体（JetBrains Mono / 系统 Monospace 回退）
 * 4. 标题使用加粗字重
 * 5. 增强排版：代码块、引用块、链接、标题分隔线
 *
 * 字体配置：
 * - 代码块：优先使用 JetBrains Mono（需放入 res/font/jetbrains_mono.ttf），
 *   回退到系统等宽字体 Typeface.MONOSPACE
 * - 标题：使用 Typeface.DEFAULT_BOLD 加粗
 * - 正文：使用系统默认字体（如需 Inter/思源黑体，放入 res/font/ 后配置）
 */
object MarkwonConfig {

    @Volatile
    private var cachedLightMarkwon: Markwon? = null
    @Volatile
    private var cachedDarkMarkwon: Markwon? = null
    @Volatile
    private var cachedLightCodeBlockMarkwon: Markwon? = null
    @Volatile
    private var cachedDarkCodeBlockMarkwon: Markwon? = null

    /**
     * 创建 Markwon 实例（按主题缓存，线程安全）
     */
    @Synchronized
    fun create(context: Context, isDarkTheme: Boolean = false): Markwon {
        return if (isDarkTheme) {
            cachedDarkMarkwon ?: buildMarkwon(context, isDarkTheme = true).also {
                cachedDarkMarkwon = it
            }
        } else {
            cachedLightMarkwon ?: buildMarkwon(context, isDarkTheme = false).also {
                cachedLightMarkwon = it
            }
        }
    }

    /**
     * 创建代码块专用 Markwon 实例（按主题缓存，线程安全）
     *
     * 与完整版 Markwon 的区别：
     * - 代码块背景透明（卡片组件自行提供背景）
     * - 不包含表格、任务列表、图片、HTML 等非代码块插件
     * - 仅保留语法高亮能力
     */
    @Synchronized
    fun createForCodeBlock(context: Context, isDarkTheme: Boolean = false): Markwon {
        return if (isDarkTheme) {
            cachedDarkCodeBlockMarkwon ?: buildCodeBlockMarkwon(context, isDarkTheme = true).also {
                cachedDarkCodeBlockMarkwon = it
            }
        } else {
            cachedLightCodeBlockMarkwon ?: buildCodeBlockMarkwon(context, isDarkTheme = false).also {
                cachedLightCodeBlockMarkwon = it
            }
        }
    }

    /**
     * 创建语法高亮主题（深色模式覆盖 diff 相关 token 颜色，提升在暗背景上的可见性）
     */
    private fun createHighlightTheme(isDarkTheme: Boolean): Prism4jTheme {
        if (!isDarkTheme) return Prism4jThemeDefault.create()
        return DiffFixedDarkulaTheme()
    }

    /**
     * 构建代码块专用 Markwon 实例
     * try-catch 保护：防止 Prism4j/SyntaxHighlight 初始化异常导致崩溃
     */
    private fun buildCodeBlockMarkwon(context: Context, isDarkTheme: Boolean): Markwon {
        return try {
            val prism4j = Prism4j(AliasedGrammarLocator())
            val highlightTheme = createHighlightTheme(isDarkTheme)
            val codeTypeface = Typeface.MONOSPACE

            Markwon.builder(context)
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, highlightTheme))
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .codeBlockBackgroundColor(Color.TRANSPARENT)
                            .codeTextColor(
                                if (isDarkTheme) Color.parseColor("#D4D4D4") else Color.parseColor("#24292F")
                            )
                            .codeBlockMargin(0)
                            .codeBlockTypeface(codeTypeface)
                    }
                })
                .build()
        } catch (_: Exception) {
            // 语法高亮初始化失败时回退到无高亮的 Markwon 实例
            Markwon.builder(context).build()
        }
    }

    /**
     * 构建 Markwon 实例
     * try-catch 保护：防止 Prism4j/SyntaxHighlight 初始化异常导致崩溃
     */
    private fun buildMarkwon(context: Context, isDarkTheme: Boolean): Markwon {
        return try {
            // 完整语法高亮：使用 AliasedGrammarLocator 支持 c++/c#/py 等别名
            val prism4j = Prism4j(AliasedGrammarLocator())

            // 主题配色
            // 代码块背景色与 Prism4j Darkula 主题背景（#2D2D2D）保持一致，
            // 确保无语言标识的代码块与有语法高亮的代码块背景色统一
            val codeBg = if (isDarkTheme) Color.parseColor("#2D2D2D") else Color.parseColor("#F4F4F5")
            val codeText = if (isDarkTheme) Color.parseColor("#C9D1D9") else Color.parseColor("#24292F")
            val blockQuoteColor = if (isDarkTheme) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")
            val linkColor = if (isDarkTheme) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")
            val headingBreakColor = if (isDarkTheme) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0")

            // 代码块字体：优先使用 JetBrains Mono，回退系统等宽字体
            val codeTypeface = Typeface.MONOSPACE

            // 语法高亮主题：深色模式覆盖 diff token 颜色以提升可见性
            val highlightTheme = createHighlightTheme(isDarkTheme)

            Markwon.builder(context)
                // GFM 表格（必须在图片插件之前，否则表格内图片会导致渲染异常）
                .usePlugin(TablePlugin.create(context))
                // Coil 图片加载
                .usePlugin(CoilImagesPlugin.create(context))
                // 删除线
                .usePlugin(StrikethroughPlugin.create())
                // 上标（^superscript^）—— 对应 Flexmark SuperscriptExtension
                .usePlugin(SuperscriptPlugin())
                // 下标（~subscript~）—— 对应 Flexmark SubscriptExtension
                .usePlugin(SubscriptPlugin())
                // 任务列表
                .usePlugin(TaskListPlugin.create(context))
                // 完整语法高亮（50+ 语言）
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, highlightTheme))
                // HTML 标签
                .usePlugin(HtmlPlugin.create())
                // MarkwonInlineParserPlugin（LaTeX 行内公式解析依赖）
                .usePlugin(MarkwonInlineParserPlugin.create())
                // LaTeX 数学公式（行内 $...$ 和块级 $$...$$）
                .usePlugin(
                    JLatexMathPlugin.create(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics),
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics),
                    ) { builder ->
                        builder.theme()
                            .inlineTextColor(
                                if (isDarkTheme) Color.parseColor("#E8EAED") else Color.parseColor("#1F1F1F")
                            )
                            .blockTextColor(
                                if (isDarkTheme) Color.parseColor("#E8EAED") else Color.parseColor("#1F1F1F")
                            )
                            .backgroundProvider(null)
                            .blockFitCanvas(true)
                            .blockHorizontalAlignment(
                                ru.noties.jlatexmath.JLatexMathDrawable.ALIGN_CENTER
                            )
                        builder.inlinesEnabled(true)
                        builder.blocksEnabled(true)
                        builder.errorHandler { latex, error ->
                            android.util.Log.e("MarkFlow/LaTeX", "渲染失败: $latex", error)
                            null
                        }
                    }
                )
                // 自定义主题
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            // 代码块：深色背景 + 浅色文字 + 等宽字体
                            .codeBlockBackgroundColor(codeBg)
                            .codeTextColor(codeText)
                            .codeBlockMargin(16)
                            .codeBlockTypeface(codeTypeface)
                            // 引用块：蓝色左边框 + 适当缩进
                            .blockQuoteColor(blockQuoteColor)
                            .blockMargin(24)
                            // 链接颜色
                            .linkColor(linkColor)
                            // 标题底部分隔线
                            .headingBreakColor(headingBreakColor)
                            .headingBreakHeight(2)
                    }
                })
                .build()
        } catch (_: Exception) {
            // 语法高亮初始化失败时回退到无高亮的 Markwon 实例
            Markwon.builder(context).build()
        }
    }

    // ==================== 代码区间保护 ====================

    /**
     * 找出 Markdown 中的代码区间（围栏代码块 + 行内代码）。
     *
     * 各类预处理（LaTeX 环境转换 / 行内公式改写 / 图片路径改写）必须跳过这些区间：
     * 代码示例中的 `$`、`^`、`![...]()` 等序列属于代码内容本身，改写会静默篡改代码。
     */
    private fun findCodeRanges(markdown: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        // 1) 围栏代码块：与 MarkdownParser 一致，行首 ``` 翻转代码块状态（未闭合则到文末）
        var inFence = false
        var fenceStart = 0
        var offset = 0
        for (line in markdown.split('\n')) {
            if (line.startsWith("```")) {
                if (!inFence) {
                    inFence = true
                    fenceStart = offset
                } else {
                    inFence = false
                    ranges.add(fenceStart until (offset + line.length))
                }
            }
            offset += line.length + 1 // +1 为被 split 吃掉的 \n
        }
        if (inFence) {
            ranges.add(fenceStart until markdown.length)
        }
        // 2) 行内代码（`...`，同行、等长反引号对）：仅统计围栏之外的区间
        val inlineRegex = Regex("(`+)([^`\\n]*?)\\1")
        for (match in inlineRegex.findAll(markdown)) {
            val r = match.range
            val insideFence = ranges.any { r.first >= it.first && r.last <= it.last }
            if (!insideFence) ranges.add(r)
        }
        return ranges.sortedBy { it.first }
    }

    /** 仅对代码区间之外的文本段应用 [transform]，代码区间原样保留 */
    private inline fun transformOutsideCode(markdown: String, transform: (String) -> String): String {
        val protectedRanges = findCodeRanges(markdown)
        if (protectedRanges.isEmpty()) return transform(markdown)
        val sb = StringBuilder(markdown.length)
        var cursor = 0
        for (range in protectedRanges) {
            if (range.first > cursor) {
                sb.append(transform(markdown.substring(cursor, range.first)))
            }
            sb.append(markdown.substring(range))
            cursor = range.last + 1
        }
        if (cursor < markdown.length) {
            sb.append(transform(markdown.substring(cursor)))
        }
        return sb.toString()
    }

    // ==================== LaTeX 行内公式预处理 ====================

    /**
     * 预处理 Markdown 中的行内 LaTeX 公式（$...$）
     *
     * JLatexMathPlugin 的 JLatexMathInlineProcessor 仅匹配 $$...$$（双美元），
     * 不匹配 $...$（单美元）。此方法将 $...$ 转换为 $$...$$ 以兼容插件。
     *
     * 安全措施：
     * - 仅转换包含 LaTeX 特征字符（\, ^, _, {, }）的内容，避免误转换 $100 等普通文本
     * - 不跨行匹配，避免转换错误
     * - 不匹配已有 $$...$$（双美元已在插件中处理）
     */
    fun preprocessLatexInline(markdown: String): String {
        if (!markdown.contains("$")) return markdown
        val regex = Regex("(?<!\\$)\\$(?!\\$)([^\\$\n]+?)\\$(?!\\$)")
        return try {
            // 跳过代码区间：代码示例中的 $...$ 是代码内容，不得改写
            transformOutsideCode(markdown) { segment ->
                if (!segment.contains("$")) return@transformOutsideCode segment
                regex.replace(segment) { match ->
                    val content = match.groupValues[1]
                    // 仅当内容包含 LaTeX 特征字符时才转换
                    if (content.any { it in setOf('\\', '^', '_', '{', '}') }) {
                        "\$\$${content}\$\$"
                    } else {
                        match.value
                    }
                }
            }
        } catch (_: Exception) {
            markdown
        }
    }

    // ==================== LaTeX 块级环境预处理 ====================

    /**
     * 预处理 LaTeX 块级公式环境，将 JLaTeXMath 不支持的环境转换为 \begin{array} 等效形式
     *
     * JLaTeXMath 受限支持的环境：
     * - pmatrix / vmatrix → 理论上支持但可能因内部命令（\ddots、\vdots 等）失败
     * - cases → 不支持
     * - aligned → 不支持
     *
     * 全部转换为 \begin{array} + \left/\right 分隔符，确保兼容性。
     *
     * @param markdown 原始 Markdown 文本
     * @return 转换后的 Markdown 文本
     */
    fun preprocessLatexEnvironments(markdown: String): String {
        if (!markdown.contains("\\begin{")) return markdown
        return try {
            // 跳过代码区间：代码示例中的 \begin{...} 是代码内容，不得转换
            transformOutsideCode(markdown) { segment ->
                if (!segment.contains("\\begin{")) return@transformOutsideCode segment
                var result = segment
                result = convertMatrixEnv(result, "pmatrix", "(", ")")
                result = convertMatrixEnv(result, "vmatrix", "|", "|")
                result = convertCasesEnv(result)
                result = convertAlignedEnv(result)
                result
            }
        } catch (_: Exception) {
            markdown
        }
    }

    /**
     * 将 pmatrix / vmatrix 转换为 \left<delim>\begin{array}...\end{array}\right<delim>
     *
     * 自动检测第一行 & 数量确定列数，使用居中对齐（c）。
     */
    private fun convertMatrixEnv(markdown: String, envName: String, leftDelim: String, rightDelim: String): String {
        val regex = Regex(
            "\\\\begin\\{$envName}(.*?)\\\\end\\{$envName}",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return regex.replace(markdown) { match ->
            val content = match.groupValues[1]
            // 取第一个非空行统计 & 数量，确定列数
            val lines = content.split("\\\\")
            val firstDataLine = lines.firstOrNull { line ->
                line.any { it == '&' }
            } ?: lines.firstOrNull { it.isNotBlank() } ?: ""
            val numCols = firstDataLine.count { it == '&' } + 1
            val colSpec = "c".repeat(numCols.coerceAtLeast(1))
            "\\left$leftDelim\\begin{array}{$colSpec}$content\\end{array}\\right$rightDelim"
        }
    }

    /**
     * 将 \begin{cases} 转换为 \left\{\begin{array}{ll}...\end{array}\right.
     *
     * cases 环境语义：两列左对齐，隐式左花括号。
     */
    private fun convertCasesEnv(markdown: String): String {
        val regex = Regex(
            "\\\\begin\\{cases}(.*?)\\\\end\\{cases}",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return regex.replace(markdown) { match ->
            val content = match.groupValues[1]
            "\\left\\{\\begin{array}{ll}$content\\end{array}\\right."
        }
    }

    /**
     * 将 \begin{aligned} 转换为 \begin{array}{rl...}
     *
     * aligned 的 & 是 alignment point，array 的 & 是 column separator。
     * 转换时统计每行最大 & 数量，生成交替 rl 列对齐，并将 & 替换为 & （列分隔符）。
     */
    private fun convertAlignedEnv(markdown: String): String {
        val regex = Regex(
            "\\\\begin\\{aligned}(.*?)\\\\end\\{aligned}",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return regex.replace(markdown) { match ->
            val content = match.groupValues[1]
            val lines = content.split("\\\\")
            val maxAmpersands = lines.maxOfOrNull { line -> line.count { it == '&' } } ?: 0
            val numCols = maxAmpersands + 1
            // aligned 列交替右-左对齐
            val colSpec = (0 until numCols).joinToString("") { if (it % 2 == 0) "r" else "l" }
            // 将 & 替换为 & （列分隔符，保留空格）
            val convertedContent = content.replace("&", "& ")
            "\\begin{array}{$colSpec}$convertedContent\\end{array}"
        }
    }

    // ==================== 图片路径处理 ====================

    /** 匹配 Android 包名风格的 hostname（如 com.miui.notes），这类 URL 不是真实网络地址 */
    private val UNSAFE_IMAGE_HOST = Regex(
        "https?://(com|org|net)\\.[a-z]+\\.[a-z]+/"
    )

    /**
     * 图片路径预处理结果
     *
     * @param text 处理后的 Markdown 文本
     * @param missingPlaceholders 所有被替换为占位文本"图片{alt}"的缺失图片占位串，
     *        用于渲染后统一施加灰色弱化样式
     */
    data class ImagePreprocessResult(
        val text: String,
        val missingPlaceholders: List<String>
    )

    /**
     * 预处理 Markdown 中的图片路径
     * - 相对路径 → 解析为绝对 file:// 路径；若目标文件不存在，替换为灰色占位文本"图片{alt}"
     * - 不安全 URL（如 Mi Notes 自定义 URI）→ 替换为纯文本文件名
     * - 正常 HTTP URL → 保留原样
     */
    fun preprocessImagePaths(markdown: String, baseDir: String): ImagePreprocessResult {
        if (!markdown.contains("![")) return ImagePreprocessResult(markdown, emptyList())
        val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
        val missingPlaceholders = mutableListOf<String>()
        return try {
            // 跳过代码区间：代码示例中的 ![...]() 是代码内容，不得改写/替换占位
            val replaced = transformOutsideCode(markdown) { segment ->
                if (!segment.contains("![")) return@transformOutsideCode segment
                imageRegex.replace(segment) { matchResult ->
                    val alt = matchResult.groupValues[1]
                    val path = matchResult.groupValues[2].trim()

                    // 跳过空路径
                    if (path.isEmpty()) return@replace matchResult.value

                    // 相对路径：解析为绝对路径
                    if (baseDir.isNotEmpty()) {
                        val resolvedPath = resolveRelativeImagePath(path, baseDir)
                        if (resolvedPath != null) {
                            // 目标图片文件不存在 → 回退为占位文本"图片{alt}"，供预览渲染成灰色弱化
                            if (!File(resolvedPath).exists()) {
                                val placeholder = if (alt.isEmpty()) "图片" else "图片$alt"
                                missingPlaceholders.add(placeholder)
                                return@replace placeholder
                            }
                            return@replace "![$alt](file://$resolvedPath)"
                        }
                    }

                    // 检测不安全 URL（如 Mi Notes 的 https://com.miui.notes/...）
                    if (isUnsafeImageUrl(path)) {
                        val displayName = extractImageName(path).ifEmpty { alt.ifEmpty { "图片" } }
                        return@replace displayName
                    }

                    matchResult.value
                }
            }
            ImagePreprocessResult(replaced, missingPlaceholders)
        } catch (_: Exception) {
            ImagePreprocessResult(markdown, emptyList())
        }
    }

    /**
     * 检测图片 URL 是否安全（不会导致 Coil 加载崩溃）
     *
     * 不安全的情况：
     * - URL 以 http(s) 开头但 hostname 是 Android 包名格式（如 com.miui.notes）
     * - URI 解析失败
     */
    private fun isUnsafeImageUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return true
            if (host.isEmpty()) return true
            // 检测 Android 包名风格的 hostname
            UNSAFE_IMAGE_HOST.containsMatchIn(url)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 从图片 URL 中提取文件名用于显示
     * 例如：https://com.miui.notes/note_image/abc123 → abc123
     */
    private fun extractImageName(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.lastPathSegment?.substringBefore('?')?.substringBefore('#') ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveRelativeImagePath(imageUri: String, baseDir: String): String? {
        val trimmed = imageUri.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null
        if (trimmed.startsWith("file://") || trimmed.startsWith("/")) return null
        val resolvedFile = File(baseDir, trimmed)
        return resolvedFile.absolutePath
    }

    fun resolveBaseDir(context: Context, fileUri: String): String {
        // file:// URI（应用私有目录下的导入/分享文件）直接取父目录
        try {
            val uri = Uri.parse(fileUri)
            if (uri.scheme == "file") {
                return uri.path?.let { File(it).parent } ?: ""
            }
            // content:// URI：优先用 DATA 列（绝对路径最可靠），不可用时退回 RELATIVE_PATH
            // + 外部存储根，以支持 Scoped Storage 下 DATA 返回 null 的情形
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            context.contentResolver.query(uri, projection, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        if (dataIdx >= 0) {
                            val fullPath = cursor.getString(dataIdx)
                            if (!fullPath.isNullOrEmpty()) return File(fullPath).parent ?: ""
                        }
                        val relIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                        if (relIdx >= 0) {
                            val relativePath = cursor.getString(relIdx)
                            if (!relativePath.isNullOrEmpty()) {
                                val base = File(Environment.getExternalStorageDirectory(), relativePath)
                                return base.absolutePath
                            }
                        }
                    }
                }
        } catch (_: Exception) { }
        return ""
    }

    /** 缺失图片占位文本颜色（浅色主题） */
    const val MISSING_IMAGE_GRAY_LIGHT = 0xFF5F6368.toInt()
    /** 缺失图片占位文本颜色（深色主题） */
    const val MISSING_IMAGE_GRAY_DARK = 0xFF9AA0A6.toInt()

    /**
     * 对渲染后的 TextView 中出现的缺失图片占位文本（"图片{alt}"）施加灰色弱化样式。
     * 应在 markwon.setMarkdown 之后同步调用。
     */
    fun applyMissingImagePlaceholders(
        textView: TextView,
        placeholders: List<String>,
        grayColor: Int
    ) {
        if (placeholders.isEmpty()) return
        val spannable = (textView.text as? Spannable) ?: return
        val rendered = spannable.toString()
        for (p in placeholders) {
            if (p.isEmpty()) continue
            var idx = rendered.indexOf(p)
            while (idx >= 0) {
                if (idx + p.length <= spannable.length) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(grayColor),
                        idx, idx + p.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                idx = rendered.indexOf(p, idx + p.length)
            }
        }
    }

    /**
     * 行内公式相对字高的上移补偿比例。
     *
     * markwon-latex 将行内公式的几何中心对齐到行盒中心，与中文文字视觉中心
     * 存在偏差（公式偏低）。此比例乘以当前字体高度得到向上平移的像素量。
     * 调整此值可微调公式上下位置：增大 → 公式上移，减小 → 公式下移。
     */
    private const val INLINE_FORMULA_UPWARD_OFFSET_RATIO = 0.35f

    /**
     * 修复行内 LaTeX 公式 Span 的垂直对齐问题
     *
     * markwon-latex 使用 JLatexInlineAsyncDrawableSpan（继承 AsyncDrawableSpan，
     * 本质是 ReplacementSpan）渲染行内公式，以 ALIGN_CENTER 将公式 Drawable 的
     * 几何中心对齐到行盒中心，与中文文字的视觉中心存在偏差，导致公式偏低。
     *
     * 此方法遍历 Spanned 中的所有 AsyncDrawableSpan，识别出 LaTeX 行内公式
     * （drawable 为 JLatextAsyncDrawable 且 isBlock() == false），替换为
     * [com.markflow.editor.ui.components.VerticalAlignedLatexSpan] 以施加
     * 垂直偏移补偿。块级公式与普通图片 Span 保持原样。
     *
     * 此方法应在 Markwon 完成渲染后调用（例如 TextView.setText 覆写中同步调用）。
     *
     * @param textView 已渲染 Markdown 的 TextView
     */
    fun fixFormulaSpanAlignment(textView: TextView) {
        val spannable = (textView.text as? Spannable) ?: return
        val spans = spannable.getSpans(0, spannable.length, AsyncDrawableSpan::class.java)
        if (spans.isEmpty()) return

        // 基于当前 TextView 字体度量计算上移量，随字号自动缩放
        val fm = textView.paint.fontMetrics
        val fontHeight = fm.descent - fm.ascent
        val upwardOffsetPx = fontHeight * INLINE_FORMULA_UPWARD_OFFSET_RATIO

        for (span in spans) {
            val rawDrawable = span.getDrawable()

            // 仅处理行内 LaTeX 公式（JLatextAsyncDrawable 且 isBlock() == false）
            if (latexBlockState(rawDrawable) != 0) continue

            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            spannable.removeSpan(span)
            spannable.setSpan(
                com.markflow.editor.ui.components.VerticalAlignedLatexSpan(
                    rawDrawable, upwardOffsetPx
                ),
                start, end, flags
            )
        }
    }

    /**
     * 判断 AsyncDrawable 是否为 LaTeX 公式 drawable，并返回块级状态
     *
     * JLatexMathPlugin 内部使用 JLatextAsyncDrawable（包私有类），
     * 通过反射调用 isBlock() 方法区分：非 LaTeX drawable 无此方法。
     *
     * @return 1 = 块级公式，0 = 行内公式，-1 = 非 LaTeX drawable
     */
    private fun latexBlockState(drawable: AsyncDrawable): Int {
        return try {
            val method = drawable.javaClass.getMethod("isBlock")
            if ((method.invoke(drawable) as? Boolean) == true) 1 else 0
        } catch (_: Exception) {
            -1
        }
    }

    // ==================== 自定义主题 ====================

    /**
     * 深色模式 diff 专属语法高亮主题
     *
     * 继承 Prism4jThemeDarkula，覆盖 diff token 颜色以提升在 VS Code 风格
     * 暗背景（#1E1E1E）上的可见性。
     *
     * 使用命名类而非匿名内部类，避免 Kotlin-Java 互操作中潜在的类加载 / GC 问题。
     */
    private class DiffFixedDarkulaTheme : Prism4jThemeDarkula(
        android.graphics.Color.parseColor("#2D2D2D")
    ) {
        private val insertedColor = android.graphics.Color.parseColor("#89D185")
        private val deletedColor = android.graphics.Color.parseColor("#F48771")

        override fun color(language: String, type: String, alias: String?): Int {
            if (type == "inserted" || alias == "inserted") return insertedColor
            if (type == "deleted" || alias == "deleted") return deletedColor
            return super.color(language, type, alias)
        }
    }
}