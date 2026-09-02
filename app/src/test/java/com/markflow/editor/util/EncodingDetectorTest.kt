package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectorTest {

    /** 中文「你好，世界！第一章」 */
    private val CHINESE = "你好，世界！第一章"

    private fun charsetOr(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()

    @Test
    fun `utf8 bytes are detected as utf8`() {
        val bytes = CHINESE.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detectSample(bytes)
        assertEquals(Charsets.UTF_8, det.charset)
        assertFalse(det.hasBom)
        assertEquals(EncodingDetector.Confidence.HIGH, det.confidence)
    }

    @Test
    fun `utf8 with bom is detected and bom stripped on decode`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = CHINESE.toByteArray(Charsets.UTF_8)
        val bytes = bom + body

        val det = EncodingDetector.detectSample(bytes)
        assertEquals(Charsets.UTF_8, det.charset)
        assertTrue(det.hasBom)
        assertEquals(3, det.bomLength)

        val decoded = EncodingDetector.decode(bytes)
        assertEquals(CHINESE, decoded)
    }

    @Test
    fun `gbk bytes are detected and decoded without garbling`() {
        val gbk = charsetOr("GBK")
            ?: return // 若运行平台不支持 GBK，跳过（Android 通常支持）
        val bytes = CHINESE.toByteArray(gbk)

        val det = EncodingDetector.detectSample(bytes)
        // 不应误判为 UTF-8
        assertTrue(det.charset.toString().startsWithIgnoreCase("GB"))
        assertFalse(det.hasBom)

        // 手动按 GBK 解码应还原
        val decoded = EncodingDetector.decode(bytes, charsetOverride = gbk)
        assertEquals(CHINESE, decoded)
    }

    @Test
    fun `pure ascii treated as utf8`() {
        val bytes = "hello world 123".toByteArray(Charsets.US_ASCII)
        val det = EncodingDetector.detectSample(bytes)
        assertEquals(Charsets.UTF_8, det.charset)
        assertFalse(det.hasBom)
    }

    @Test
    fun `decode never throws on invalid bytes`() {
        // 各 0-255 字节混排，无论探测结果为何，decode 都不应抛异常
        val bytes = (0..255).map { it.toByte() }.toByteArray()
        try {
            val text = EncodingDetector.decode(bytes)
            assertTrue(text.isNotEmpty())
        } catch (e: Exception) {
            throw AssertionError("decode 不应抛异常", e)
        }
    }

    @Test
    fun `valid utf8 sample is not misdetected as gb even with high bytes`() {
        // 含大量多字节 UTF-8 文本，必须是合法 UTF-8
        val longText = (1..2000).joinToString("") { "$CHINESE$it " }
        val bytes = longText.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detectSample(bytes)
        assertEquals(Charsets.UTF_8, det.charset)
    }

    private fun String.startsWithIgnoreCase(prefix: String): Boolean =
        lowercase().startsWith(prefix.lowercase())
}