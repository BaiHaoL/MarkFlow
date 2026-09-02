package com.markflow.editor.domain.util

import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.SortMode

/**
 * 文件排序工具
 *
 * 将排序逻辑提取为纯函数，便于单元测试，覆盖率目标 ≥90%
 *
 * 排序规则：
 * - BY_NAME：按文件名升序（大小写不敏感，自然排序）
 * - BY_TIME_DESC：按修改时间降序（最新在前）
 */
object FileSorter {

    /**
     * 按指定模式排序列表
     *
     * @param files 待排序文件列表
     * @param sortMode 排序模式
     * @return 排序后的新列表（不修改原列表）
     */
    fun sort(files: List<MarkdownFile>, sortMode: SortMode): List<MarkdownFile> {
        if (files.size <= 1) return files.toList()
        return when (sortMode) {
            SortMode.BY_NAME -> sortByName(files)
            SortMode.BY_TIME_DESC -> sortByTimeDesc(files)
        }
    }

    /**
     * 按文件名升序排序（大小写不敏感）
     * 相同文件名时按修改时间降序作为二级排序
     */
    private fun sortByName(files: List<MarkdownFile>): List<MarkdownFile> {
        return files.sortedWith(
            compareBy({ it.fileName.lowercase() }, { -it.lastModified })
        )
    }

    /**
     * 按修改时间降序排序
     * 相同时间戳时按文件名升序作为二级排序
     */
    private fun sortByTimeDesc(files: List<MarkdownFile>): List<MarkdownFile> {
        return files.sortedWith(
            compareBy({ -it.lastModified }, { it.fileName.lowercase() })
        )
    }
}
