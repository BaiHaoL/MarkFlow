package com.markflow.editor.domain.model

/**
 * 文件类型注册表
 *
 * 将文件扩展名映射到 Prism4j 语法名称和显示名称。
 * 用于判断文件是否可编辑、是否支持语法高亮预览。
 *
 * @param extensions 关联的后缀列表（小写，不含点）
 * @param grammarName Prism4j Grammar 名称（空字符串表示无语法高亮，仅纯文本编辑）
 * @param displayName UI 显示名
 * @param isMarkdown 是否为 Markdown 文件（影响预览模式渲染方式）
 */
data class FileType(
    val extensions: List<String>,
    val grammarName: String,
    val displayName: String,
    val isMarkdown: Boolean = false
) {
    companion object {
        /** 大 TXT 阈值：超过该字节数的 .txt 文件改用只读分页阅读（0.5MB = 512 * 1024） */
        const val LARGE_FILE_THRESHOLD_BYTES = 512 * 1024L

        /** 大 Markdown 阈值：超过该字节数的 .md 文件预览降级为分页只读（1MB = 1024 * 1024） */
        const val LARGE_MD_THRESHOLD_BYTES = 1024 * 1024L

        /**
         * 支持的文件类型注册表
         *
         * 分类说明：
         * - Markdown：标准 Markdown 文件，预览模式使用 Markwon 渲染
         * - 有语法高亮：非 Markdown 但语法库支持，预览模式显示代码 UI 卡片
         * - 纯文本：无语法高亮，仅编辑模式
         */
        val REGISTRY: List<FileType> = listOf(
            // === Markdown ===
            FileType(listOf("md", "markdown"), "markdown", "Markdown", isMarkdown = true),

            // === Shell ===
            FileType(listOf("sh", "bash", "zsh"), "bash", "Shell"),

            // === Python ===
            FileType(listOf("py"), "python", "Python"),

            // === C/C++ ===
            FileType(listOf("c", "h"), "c", "C"),
            FileType(listOf("cpp", "cc", "cxx", "hpp", "hxx"), "cpp", "C++"),

            // === Java / Kotlin ===
            FileType(listOf("java"), "java", "Java"),
            FileType(listOf("kt", "kts"), "kotlin", "Kotlin"),

            // === JavaScript / TypeScript ===
            FileType(listOf("js", "mjs"), "javascript", "JavaScript"),
            FileType(listOf("ts", "tsx"), "typescript", "TypeScript"),

            // === Go / Rust ===
            FileType(listOf("go"), "go", "Go"),
            FileType(listOf("rs"), "rust", "Rust"),

            // === Swift / Dart ===
            FileType(listOf("swift"), "swift", "Swift"),
            FileType(listOf("dart"), "dart", "Dart"),

            // === PHP / Ruby / Lua ===
            FileType(listOf("php"), "php", "PHP"),
            FileType(listOf("rb"), "ruby", "Ruby"),
            FileType(listOf("lua"), "lua", "Lua"),

            // === SQL ===
            FileType(listOf("sql"), "sql", "SQL"),

            // === JSON ===
            FileType(listOf("json", "jsonc"), "json", "JSON"),

            // === YAML ===
            FileType(listOf("yaml", "yml"), "yaml", "YAML"),

            // === TOML ===
            FileType(listOf("toml"), "toml", "TOML"),

            // === XML / HTML ===
            FileType(listOf("xml"), "xml", "XML"),
            FileType(listOf("html", "htm"), "html", "HTML"),

            // === CSS ===
            FileType(listOf("css"), "css", "CSS"),
            FileType(listOf("scss"), "scss", "SCSS"),
            FileType(listOf("less"), "less", "Less"),

            // === 配置文件（无语法高亮，纯文本编辑） ===
            FileType(listOf("ini", "conf", "cfg"), "", "Config"),
            FileType(listOf("properties", "env"), "", "Config"),

            // === 纯文本 ===
            FileType(listOf("txt", "log", "text"), "", "Plain Text"),

            // === Dockerfile ===
            FileType(listOf("dockerfile", "docker"), "dockerfile", "Dockerfile"),

            // === Diff ===
            FileType(listOf("diff", "patch"), "diff", "Diff"),
        )

        /** 所有支持的后缀名集合（小写，不含点） */
        val ALL_EXTENSIONS: Set<String> = REGISTRY.flatMap { it.extensions }.toSet()

        /**
         * 根据文件名解析文件类型
         *
         * 委托给 [FormatRegistry.resolve] 进行格式匹配。
         * 保留此方法以保持向后兼容。
         *
         * @param fileName 文件名（含扩展名）
         * @return 匹配的 FileType，未匹配返回 null
         */
        fun resolve(fileName: String): FileType? {
            val plugin = FormatRegistry.resolve(fileName) ?: return null
            return FileType(
                extensions = plugin.extensions,
                grammarName = plugin.grammarName,
                displayName = plugin.displayName,
                isMarkdown = plugin.isMarkdown
            )
        }

        /**
         * 判断文件名是否属于支持的文件类型
         */
        fun isSupported(fileName: String): Boolean = resolve(fileName) != null

        /**
         * 判断文件名是否为 Markdown 文件
         */
        fun isMarkdown(fileName: String): Boolean = resolve(fileName)?.isMarkdown == true
    }
}