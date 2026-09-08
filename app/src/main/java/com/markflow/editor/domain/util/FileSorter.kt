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
     * 星标置顶排序：星标组整体排在前面（不参与全局排序位置），
     * 但星标组内部与非星标组内部各自仍按 [sortMode] 排序。
     *
     * 效果：无论排序模式如何，星标文件恒置顶；各组内顺序随模式改变（时间/名称）。
     */
    fun sortWithStar(
        files: List<MarkdownFile>,
        sortMode: SortMode,
        starredUris: Set<String>
    ): List<MarkdownFile> {
        if (starredUris.isEmpty()) return sort(files, sortMode)
        val (starGroup, rest) = files.partition { it.uri in starredUris }
        return sort(starGroup, sortMode) + sort(rest, sortMode)
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
