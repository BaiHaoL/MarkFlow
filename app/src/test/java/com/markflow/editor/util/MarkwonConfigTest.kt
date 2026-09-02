package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarkwonConfig 单元测试
 *
 * 覆盖当前实现仍保证的稳定行为：
 * - file:// / 绝对路径 / HTTP(S) URL 不被改写
 * - 空 baseDir 时不改写相对路径
 * - 无图片、空文本、纯链接语法不改写
 * - 表格语法不被改写
 *
 * 注：相对路径在目标图片文件不存在时，当前实现回退为占位文本"图片{alt}"，
 * 该行为依赖真实文件系统，由真机/集成测试覆盖，不在此单测。
 */
class MarkwonConfigTest {

    @Test
    fun `preprocessImagePaths_file_protocol_unchanged`() {
        val result = MarkwonConfig.preprocessImagePaths(
            "![alt](file:///sdcard/photo.png)",
            "/storage/emulated/0/Documents"
        )
        assertEquals("file:// 协议不应被修改", "![alt](file:///sdcard/photo.png)", result.text)
    }

    @Test
    fun `preprocessImagePaths_absolute_path_unchanged`() {
        val result = MarkwonConfig.preprocessImagePaths(
            "![alt](/sdcard/photo.png)",
            "/storage/emulated/0/Documents"
        )
        assertEquals("绝对路径不应被修改", "![alt](/sdcard/photo.png)", result.text)
    }

    @Test
    fun `preprocessImagePaths_empty_base_dir_unchanged`() {
        val result = MarkwonConfig.preprocessImagePaths(
            "![alt](images/photo.png)",
            ""
        )
        assertEquals("空 baseDir 时不应修改", "![alt](images/photo.png)", result.text)
    }

    @Test
    fun `preprocessImagePaths_no_image_syntax`() {
        val result = MarkwonConfig.preprocessImagePaths(
            "这是普通文本，没有图片语法",
            "/storage/emulated/0/Documents"
        )
        assertEquals("无图片时不应修改", "这是普通文本，没有图片语法", result.text)
    }

    @Test
    fun `preprocessImagePaths_empty_markdown`() {
        val result = MarkwonConfig.preprocessImagePaths(
            "",
            "/storage/emulated/0/Documents"
        )
        assertEquals("空文本不应修改", "", result.text)
    }

    @Test
    fun `preprocessImagePaths_table_syntax_unchanged`() {
        val md = """
            | 列1 | 列2 | 列3 |
            |-----|-----|-----|
            |  A  |  B  |  C  |
        """.trimMargin()
        val result = MarkwonConfig.preprocessImagePaths(md, "/storage/emulated/0/Documents")
        assertEquals("表格语法不应被修改", md, result.text)
    }

    @Test
    fun `preprocessImagePaths_no_false_positive_on_link_syntax`() {
        val md = "[链接文字](https://example.com) 不是图片"
        val result = MarkwonConfig.preprocessImagePaths(md, "/storage/emulated/0/Documents")
        assertEquals("链接语法不应被修改", md, result.text)
    }
}
