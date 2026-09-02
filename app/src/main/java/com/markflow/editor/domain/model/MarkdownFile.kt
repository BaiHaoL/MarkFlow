package com.markflow.editor.domain.model

/**
 * 文件来源
 */
enum class FileSource {
    /** 应用内创建或本地存储扫描到的文件 */
    LOCAL,
    /** 跨应用打开的导入文件 */
    IMPORTED
}

/**
 * 文本文件领域模型
 * 表示一个可被编辑的文本文件的核心元数据
 *
 * @param uri 文件的 Content URI（用于 SAF 访问）
 * @param fileName 文件名（不含路径）
 * @param filePath 文件的完整物理路径（可能为空，SAF 文件使用 URI）
 * @param lastModified 最后修改时间戳（毫秒）
 * @param fileSize 文件大小（字节）
 * @param source 文件来源：本地 / 导入
 * @param isMarkdown 是否为 Markdown 文件（影响预览模式行为）
 * @param grammarName 语法高亮名称（空字符串 = 无语法高亮，仅纯文本编辑）
 */
data class MarkdownFile(
    val uri: String,
    val fileName: String,
    val filePath: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    val fileSize: Long = 0L,
    val source: FileSource = FileSource.LOCAL,
    val isMarkdown: Boolean = true,
    val grammarName: String = "markdown"
) {
    /**
     * 格式化文件大小显示
     * 自动转换为 KB/MB 等人类可读单位
     */
    fun formattedSize(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> String.format("%.1f KB", fileSize / 1024.0)
            else -> String.format("%.1f MB", fileSize / (1024.0 * 1024.0))
        }
    }

    /**
     * 获取不带扩展名的文件名
     */
    fun nameWithoutExtension(): String {
        return fileName.substringBeforeLast(".", fileName)
    }
}