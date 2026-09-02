package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TocParser 单元测试
 * 覆盖标题解析、代码块过滤、层级识别、字符偏移计算
 */
class TocParserTest {

    @Test
    fun `parse_single_h1`() {
        val md = "# 标题一"
        val entries = TocParser.parse(md)
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].level)
        assertEquals("标题一", entries[0].text)
        assertEquals(0, entries[0].lineIndex)
    }

    @Test
    fun `parse_all_heading_levels`() {
        val md = "# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6"
        val entries = TocParser.parse(md)
        assertEquals(6, entries.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), entries.map { it.level })
    }

    @Test
    fun `parse_ignores_code_block_headings`() {
        val md = "# 正式标题\n```\n# 代码块内注释\n```\n## 第二标题"
        val entries = TocParser.parse(md)
        assertEquals(2, entries.size)
        assertEquals("正式标题", entries[0].text)
        assertEquals("第二标题", entries[1].text)
    }

    @Test
    fun `parse_empty_string`() {
        val entries = TocParser.parse("")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parse_no_headings`() {
        val md = "这是普通文本。\n没有标题。\n- 列表项"
        val entries = TocParser.parse(md)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parse_heading_with_trailing_spaces`() {
        val md = "# 标题   "
        val entries = TocParser.parse(md)
        assertEquals(1, entries.size)
        assertEquals("标题", entries[0].text)
    }

    @Test
    fun `parse_line_index_correct`() {
        val md = "第一行\n第二行\n# 标题在第三行"
        val entries = TocParser.parse(md)
        assertEquals(1, entries.size)
        assertEquals(2, entries[0].lineIndex)
    }

    @Test
    fun `parse_skips_hash_without_space`() {
        val md = "#NotAHeading\n# IsAHeading"
        val entries = TocParser.parse(md)
        assertEquals(1, entries.size)
        assertEquals("IsAHeading", entries[0].text)
    }

    @Test
    fun `parse_more_than_6_hashes_not_heading`() {
        val md = "####### 七个#不是标题"
        val entries = TocParser.parse(md)
        assertTrue(entries.isEmpty())
    }
}
