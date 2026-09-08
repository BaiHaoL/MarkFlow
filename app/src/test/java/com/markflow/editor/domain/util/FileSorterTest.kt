package com.markflow.editor.domain.util

import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileSorter 单元测试
 *
 * 覆盖率目标 ≥90%，覆盖以下场景：
 * - 按文件名排序（大小写、Unicode、相同名称）
 * - 按修改时间排序（相同时间戳）
 * - 空列表、单元素列表
 * - 不修改原列表
 */
class FileSorterTest {

    private fun file(name: String, time: Long, size: Long = 100L) = MarkdownFile(
        uri = "uri://$name",
        fileName = name,
        lastModified = time,
        fileSize = size
    )

    // ==================== BY_NAME 排序 ====================

    @Test
    fun `sortByName_ascending_order`() {
        val files = listOf(
            file("zebra.md", 100),
            file("apple.md", 200),
            file("mango.md", 300)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(listOf("apple.md", "mango.md", "zebra.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sortByName_case_insensitive`() {
        val files = listOf(
            file("Banana.md", 100),
            file("apple.md", 200),
            file("Cherry.md", 300)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(listOf("apple.md", "Banana.md", "Cherry.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sortByName_same_name_falls_back_to_time_desc`() {
        val files = listOf(
            file("dup.md", 100),
            file("dup.md", 300),
            file("dup.md", 200)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        // 相同文件名时按修改时间降序
        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.lastModified })
    }

    @Test
    fun `sortByName_unicode_names`() {
        val files = listOf(
            file("测试.md", 100),
            file("Alpha.md", 200),
            file("文档.md", 300)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        // 按 lowercase 字典序：alpha < 文档 < 测试
        assertEquals(listOf("Alpha.md", "文档.md", "测试.md"), sorted.map { it.fileName })
    }

    // ==================== BY_TIME_DESC 排序 ====================

    @Test
    fun `sortByTime_descending_order`() {
        val files = listOf(
            file("old.md", 100),
            file("new.md", 300),
            file("mid.md", 200)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_TIME_DESC)
        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.lastModified })
    }

    @Test
    fun `sortByTime_same_timestamp_falls_back_to_name`() {
        val files = listOf(
            file("zebra.md", 100),
            file("apple.md", 100),
            file("mango.md", 100)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_TIME_DESC)
        // 相同时间戳时按文件名升序
        assertEquals(listOf("apple.md", "mango.md", "zebra.md"), sorted.map { it.fileName })
    }

    // ==================== 边界情况 ====================

    @Test
    fun `sort_empty_list_returns_empty`() {
        val sorted = FileSorter.sort(emptyList(), SortMode.BY_NAME)
        assertTrue(sorted.isEmpty())
    }

    @Test
    fun `sort_single_element_returns_copy`() {
        val files = listOf(file("only.md", 100))
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(1, sorted.size)
        assertEquals("only.md", sorted[0].fileName)
    }

    @Test
    fun `sort_does_not_modify_original_list`() {
        val original = listOf(
            file("zebra.md", 100),
            file("apple.md", 200)
        )
        val sorted = FileSorter.sort(original, SortMode.BY_NAME)
        // 原列表不变
        assertEquals("zebra.md", original[0].fileName)
        assertEquals("apple.md", sorted[0].fileName)
    }

    @Test
    fun `sort_two_elements_already_sorted_by_name`() {
        val files = listOf(
            file("apple.md", 100),
            file("zebra.md", 200)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(listOf("apple.md", "zebra.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sort_two_elements_reverse_sorted_by_name`() {
        val files = listOf(
            file("zebra.md", 200),
            file("apple.md", 100)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(listOf("apple.md", "zebra.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sort_mixed_case_and_numbers`() {
        val files = listOf(
            file("file10.md", 100),
            file("file2.md", 200),
            file("File1.md", 300)
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        // 按字符串比较（非自然排序）：file1 < file10 < file2
        assertEquals(listOf("File1.md", "file10.md", "file2.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sort_large_list_by_time`() {
        val files = (1..100).map { file("file$it.md", it.toLong() * 1000) }
        val sorted = FileSorter.sort(files, SortMode.BY_TIME_DESC)
        assertEquals(100, sorted.size)
        // 最新（最大时间戳）在最前
        assertEquals(100000L, sorted[0].lastModified)
        assertEquals(1000L, sorted.last().lastModified)
    }

    @Test
    fun `sort_large_list_by_name`() {
        val files = (1..100).map { file("file${it.toString().padStart(3, '0')}.md", it.toLong()) }
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals(100, sorted.size)
        // 范围 1..100 生成 file001~file100，按字符串排序 file001 最小、file100 最大
        assertEquals("file001.md", sorted[0].fileName)
        assertEquals("file100.md", sorted.last().fileName)
    }

    @Test
    fun `sort_preserves_all_file_properties`() {
        val files = listOf(
            MarkdownFile(
                uri = "uri://test",
                fileName = "test.md",
                filePath = "/path/to/test.md",
                lastModified = 12345,
                fileSize = 6789,
                source = com.markflow.editor.domain.model.FileSource.IMPORTED
            )
        )
        val sorted = FileSorter.sort(files, SortMode.BY_NAME)
        assertEquals("uri://test", sorted[0].uri)
        assertEquals("/path/to/test.md", sorted[0].filePath)
        assertEquals(12345, sorted[0].lastModified)
        assertEquals(6789, sorted[0].fileSize)
        assertEquals(com.markflow.editor.domain.model.FileSource.IMPORTED, sorted[0].source)
    }

    // ==================== sortWithStar 星标置顶 ====================

    @Test
    fun `sortWithStar_starred_group_always_first_by_name`() {
        val starA = file("b1.md", 200)
        val starB = file("a1.md", 100)
        val normalC = file("c1.md", 50)
        val normalD = file("d1.md", 60)
        val files = listOf(normalC, starA, normalD, starB)
        val starred = setOf(starA.uri, starB.uri)

        val sorted = FileSorter.sortWithStar(files, SortMode.BY_NAME, starred)
        // 星标组置顶，组内按名称序；随后非星标组按名称序
        assertEquals(listOf("a1.md", "b1.md", "c1.md", "d1.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sortWithStar_starred_group_by_time_inside_by_name_mode`() {
        val starOld = file("b.md", 100)
        val starNew = file("a.md", 300)
        val files = listOf(file("zz.md", 500), starOld, starNew, file("cc.md", 0))

        val sorted = FileSorter.sortWithStar(files, SortMode.BY_TIME_DESC, setOf(starOld.uri, starNew.uri))
        // 星标组置顶，组内按时间降序：starNew(300) > starOld(100)；非星标按时间降序：zz(500) > cc(0)
        assertEquals(listOf("a.md", "b.md", "zz.md", "cc.md"), sorted.map { it.fileName })
    }

    @Test
    fun `sortWithStar_empty_starred_delegates_to_sort`() {
        val files = listOf(file("z.md", 100), file("a.md", 200))
        val sorted = FileSorter.sortWithStar(files, SortMode.BY_NAME, emptySet())
        assertEquals(listOf("a.md", "z.md"), sorted.map { it.fileName })
    }
}
