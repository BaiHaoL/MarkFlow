package com.markflow.editor.util

/**
 * 根据文件名后缀推断分享时应使用的 MIME 类型。
 *
 * 与文件列表多选分享策略保持一致，使编辑器内单文件分享也能准确声明 MIME。
 */
fun resolveShareMimeType(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "txt", "log", "text" -> "text/plain"
        "json", "jsonc" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "ts", "tsx" -> "application/typescript"
        "csv" -> "text/csv"
        "yaml", "yml" -> "text/x-yaml"
        "toml" -> "application/toml"
        "sh", "bash", "zsh" -> "application/x-sh"
        "py" -> "text/x-python"
        "java" -> "text/x-java-source"
        "kt", "kts" -> "text/x-kotlin"
        "cpp", "cc", "cxx", "hpp", "hxx" -> "text/x-c++src"
        "c", "h" -> "text/x-csrc"
        "sql" -> "application/sql"
        else -> "text/plain"
    }
}
