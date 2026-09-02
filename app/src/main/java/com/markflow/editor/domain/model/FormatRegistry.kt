package com.markflow.editor.domain.model

/**
 * 格式注册中心
 *
 * 参考 Markor 的 FormatRegistry 设计，管理所有可用的文件格式插件。
 * 提供格式查找、扩展名解析、自动格式化规则获取等能力。
 *
 * 设计原则：
 * - 插件式注册：每个格式通过 [FormatPlugin] 接口注册
 * - 后注册优先：同名扩展名冲突时，后注册的插件覆盖先注册的
 * - 默认兜底：无匹配格式时返回纯文本模式
 */
object FormatRegistry {

    /** 已注册的格式插件列表（后注册的优先级更高） */
    private val _plugins = mutableListOf<FormatPlugin>()

    /** 扩展名 → 格式名称 的快速查找缓存 */
    private val _extensionCache = mutableMapOf<String, String>()

    /** 所有已注册的扩展名集合 */
    val allExtensions: Set<String>
        get() = _extensionCache.keys.toSet()

    // ==================== 初始化 ====================

    init {
        registerDefaultPlugins()
    }

    /**
     * 注册默认格式插件
     *
     * 涵盖 Markdown 及常用编程语言的格式定义。
     * 注意：注册顺序影响扩展名冲突时的优先级（后注册覆盖先注册）。
     */
    private fun registerDefaultPlugins() {
        // === Markdown（带自动格式化规则） ===
        register(
            MarkdownFormatPlugin(
                extensions = listOf("md", "markdown"),
                grammarName = "markdown",
                displayName = "Markdown",
                autoFormatPatterns = AutoFormatPatterns.MARKDOWN
            )
        )

        // === 编程语言（无自动格式化，仅语法高亮） ===
        register(CodeFormatPlugin(listOf("sh", "bash", "zsh"), "bash", "Shell"))
        register(CodeFormatPlugin(listOf("py"), "python", "Python"))
        register(CodeFormatPlugin(listOf("c", "h"), "c", "C"))
        register(CodeFormatPlugin(listOf("cpp", "cc", "cxx", "hpp", "hxx"), "cpp", "C++"))
        register(CodeFormatPlugin(listOf("java"), "java", "Java"))
        register(CodeFormatPlugin(listOf("kt", "kts"), "kotlin", "Kotlin"))
        register(CodeFormatPlugin(listOf("js", "mjs"), "javascript", "JavaScript"))
        register(CodeFormatPlugin(listOf("ts", "tsx"), "typescript", "TypeScript"))
        register(CodeFormatPlugin(listOf("go"), "go", "Go"))
        register(CodeFormatPlugin(listOf("rs"), "rust", "Rust"))
        register(CodeFormatPlugin(listOf("swift"), "swift", "Swift"))
        register(CodeFormatPlugin(listOf("dart"), "dart", "Dart"))
        register(CodeFormatPlugin(listOf("php"), "php", "PHP"))
        register(CodeFormatPlugin(listOf("rb"), "ruby", "Ruby"))
        register(CodeFormatPlugin(listOf("lua"), "lua", "Lua"))
        register(CodeFormatPlugin(listOf("sql"), "sql", "SQL"))

        // === 数据格式 ===
        register(CodeFormatPlugin(listOf("json", "jsonc"), "json", "JSON"))
        register(CodeFormatPlugin(listOf("yaml", "yml"), "yaml", "YAML"))
        register(CodeFormatPlugin(listOf("toml"), "toml", "TOML"))
        register(CodeFormatPlugin(listOf("xml"), "xml", "XML"))
        register(CodeFormatPlugin(listOf("html", "htm"), "html", "HTML"))
        register(CodeFormatPlugin(listOf("css"), "css", "CSS"))
        register(CodeFormatPlugin(listOf("scss"), "scss", "SCSS"))
        register(CodeFormatPlugin(listOf("less"), "less", "Less"))

        // === 构建/配置 ===
        register(CodeFormatPlugin(listOf("dockerfile", "docker"), "dockerfile", "Dockerfile"))
        register(CodeFormatPlugin(listOf("diff", "patch"), "diff", "Diff"))

        // === 纯文本（无语法高亮，仅编辑） ===
        register(PlainTextPlugin(listOf("ini", "conf", "cfg"), "Config"))
        register(PlainTextPlugin(listOf("properties", "env"), "Config"))
        register(PlainTextPlugin(listOf("txt", "log", "text"), "Plain Text"))
    }

    // ==================== 公共 API ====================

    /**
     * 注册格式插件
     *
     * @param plugin 格式插件实例
     */
    fun register(plugin: FormatPlugin) {
        _plugins.add(plugin)
        // 更新扩展名缓存（后注册的覆盖先注册的）
        for (ext in plugin.extensions) {
            _extensionCache[ext] = plugin.formatName
        }
    }

    /**
     * 根据文件名解析格式插件
     *
     * @param fileName 文件名（含扩展名）
     * @return 匹配的 FormatPlugin，未匹配返回 null
     */
    fun resolve(fileName: String): FormatPlugin? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return _plugins.lastOrNull { ext in it.extensions }
    }

    /**
     * 根据扩展名获取格式插件
     *
     * @param extension 文件扩展名（小写，不含点）
     * @return 匹配的 FormatPlugin，未匹配返回 null
     */
    fun findByExtension(extension: String): FormatPlugin? {
        val ext = extension.lowercase()
        return _plugins.lastOrNull { ext in it.extensions }
    }

    /**
     * 获取格式的自动格式化规则
     *
     * @param fileName 文件名
     * @return 自动格式化规则，格式不支持时返回 null
     */
    fun getAutoFormatPatterns(fileName: String): AutoFormatPatterns? {
        return resolve(fileName)?.autoFormatPatterns
    }

    /**
     * 判断文件名是否属于支持的格式
     */
    fun isSupported(fileName: String): Boolean = resolve(fileName) != null

    /**
     * 判断文件名是否为 Markdown 格式
     */
    fun isMarkdown(fileName: String): Boolean = resolve(fileName)?.isMarkdown == true

    /**
     * 获取所有已注册的格式插件
     */
    fun allPlugins(): List<FormatPlugin> = _plugins.toList()

    /**
     * 清除所有自定义注册（重置为默认状态）
     */
    fun reset() {
        _plugins.clear()
        _extensionCache.clear()
        registerDefaultPlugins()
    }
}

// ==================== 内置格式插件实现 ====================

/**
 * Markdown 格式插件
 */
private data class MarkdownFormatPlugin(
    override val extensions: List<String>,
    override val grammarName: String,
    override val displayName: String,
    override val autoFormatPatterns: AutoFormatPatterns? = null
) : FormatPlugin {
    override val isMarkdown: Boolean = true
}

/**
 * 代码格式插件（有语法高亮，无自动格式化）
 */
private data class CodeFormatPlugin(
    override val extensions: List<String>,
    override val grammarName: String,
    override val displayName: String
) : FormatPlugin

/**
 * 纯文本格式插件（无语法高亮，无自动格式化）
 */
private data class PlainTextPlugin(
    override val extensions: List<String>,
    override val displayName: String
) : FormatPlugin {
    override val grammarName: String = ""
}