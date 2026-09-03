package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class PagedTextSourceTest {

    /** 基于内存字节数组的 seek 读取器，模拟真实文件 */
    private class ByteArraySeekReader(private val data: ByteArray) : RandomSeekReader {
        override val length: Long get() = data.size.toLong()
        override fun read(buffer: ByteArray, offset: Long, maxLen: Int): Int {
            if (offset >= data.size) return -1
            val start = offset.toInt()
            val len = minOf(maxLen, data.size - start)
            System.arraycopy(data, start, buffer, 0, len)
            return len
        }

        override fun close() {}
    }

    private fun textSource(
        content: String,
        charset: Charset = Charsets.UTF_8,
        chunkBytes: Int = PagedTextSource.DEFAULT_CHUNK_TARGET_BYTES
    ): PagedTextSource {
        val bytes = content.toByteArray(charset)
        return PagedTextSource(
            open = { ByteArraySeekReader(bytes) },
            chunkTargetBytes = chunkBytes
        )
    }

    /** 顺序拼接所有块，返回 (完整文本, 块数) */
    private suspend fun readAll(source: PagedTextSource): Pair<String, Int> {
        val sb = StringBuilder()
        var index = 0
        while (true) {
            val chunk = source.getChunk(index) ?: break
            sb.append(chunk.text)
            index++
        }
        return sb.toString() to index
    }

    @Test
    fun `拼接所有块应无损还原原文`() = runTest {
        val content = buildString {
            for (i in 1..5000) append("第 $i 行：这是一段用于测试分块的文本内容。\n")
        }
        val (joined, chunkCount) = readAll(textSource(content))
        assertEquals(content, joined)
        assertTrue("10KB 块下 5000 行应产生多块，实际 $chunkCount 块", chunkCount > 1)
    }

    @Test
    fun `每个块大小不应小于目标字节数`() = runTest {
        val content = buildString {
            for (i in 1..2000) append("这是一行用于测量块边界的文本，行号 $i。\n")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val target = 10 * 1024
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) }, chunkTargetBytes = target)
        var index = 0
        var prevEnd = 0L
        while (true) {
            val chunk = source.getChunk(index) ?: break
            val chunkBytes = chunk.text.toByteArray(Charsets.UTF_8).size
            if (prevEnd + chunkBytes < bytes.size) {
                // 非最后一块：大小应 >= target
                assertTrue(
                    "中间块大小应 >= target($target)，实际 $chunkBytes",
                    chunkBytes >= target
                )
            }
            prevEnd += chunkBytes
            index++
        }
    }

    @Test
    fun `块边界不应切断行`() = runTest {
        val content = buildString {
            for (i in 1..3000) append("LINE_$i content payload\n")
        }
        val (joined, _) = readAll(textSource(content))
        // 原文拼接无损 → 每行保持完整
        assertTrue(joined.contains("LINE_1 content payload"))
        assertTrue(joined.contains("LINE_3000 content payload"))
    }

    @Test
    fun `超长单行应被强制截断为独立块而不死循环`() = runTest {
        // 构造一个超长行（>200KB 无换行）+ 一段正常文本
        val longLine = "A".repeat(PagedTextSource.MAX_LINE_BYTES + 50_000)
        val content = longLine + "\n尾部正常文本\n"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        val (joined, count) = readAll(source)
        assertTrue("超长行应能完成读取并产生多块", count >= 2)
        assertTrue("拼接结果应包含尾部文本", joined.endsWith("尾部正常文本\n"))
    }

    @Test
    fun `utf8 中文不乱码`() = runTest {
        val content = "你好，世界！第一章\n" + "这是一段中文小说内容。\n".repeat(100)
        val (joined, _) = readAll(textSource(content))
        assertEquals(content, joined)
    }

    @Test
    fun `gbk 内容按指定编码解码不乱码`() = runTest {
        val gbk = Charset.forName("GBK")
        val content = "中文测试内容，第一章。\n" + "第二行内容。\n".repeat(50)
        val bytes = content.toByteArray(gbk)
        val source = PagedTextSource(
            open = { ByteArraySeekReader(bytes) },
            charsetOverride = gbk
        )
        val (joined, _) = readAll(source)
        assertEquals(content, joined)
    }

    @Test
    fun `utf8 bom 应被剥离`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "带 BOM 的文本\n".toByteArray(Charsets.UTF_8)
        val bytes = bom + body
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        val (joined, _) = readAll(source)
        assertEquals("带 BOM 的文本\n", joined)
    }

    @Test
    fun `utf16le 分块不错位乱码`() = runTest {
        val content = buildString {
            for (i in 1..2000) append("第 $i 行：UTF-16LE 中文分块测试内容。\n")
        }
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + content.toByteArray(Charsets.UTF_16LE)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        val (joined, chunkCount) = readAll(source)
        assertTrue("应产生多块，实际 $chunkCount 块", chunkCount > 1)
        assertEquals("UTF-16LE 拼接应无损还原原文", content, joined)
        assertTrue("不应出现替换符", !joined.contains('�'))
    }

    @Test
    fun `utf16be 分块不错位乱码`() = runTest {
        val content = buildString {
            for (i in 1..2000) append("第 $i 行：UTF-16BE 中文分块测试内容。\n")
        }
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val bytes = bom + content.toByteArray(Charsets.UTF_16BE)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        val (joined, chunkCount) = readAll(source)
        assertTrue("应产生多块，实际 $chunkCount 块", chunkCount > 1)
        assertEquals("UTF-16BE 拼接应无损还原原文", content, joined)
        assertTrue("不应出现替换符", !joined.contains('�'))
    }

    @Test
    fun `utf8 超长行硬截断不切断多字节字符`() = runTest {
        // 300KB 无换行中文行：强制截断处必然落在多字节字符中间，应回退到完整字符边界
        val longLine = "中".repeat(100_000)
        val content = longLine + "\n尾部\n"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        val (joined, chunkCount) = readAll(source)
        assertTrue("应产生多块，实际 $chunkCount 块", chunkCount >= 2)
        assertEquals("拼接应无损还原原文（无替换符）", content, joined)
    }

    @Test
    fun `utf16le 块边界应为偶数字节偏移`() = runTest {
        val content = buildString {
            for (i in 1..1500) append("ROW_$i UTF-16 偶偏移校验行。\n")
        }
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + content.toByteArray(Charsets.UTF_16LE)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) })
        var index = 0
        while (true) {
            val offset = source.chunkOffsetOf(index) ?: break
            assertEquals("块 $index 起始偏移应为偶数（编码单元对齐）", 0L, offset % 2)
            index++
        }
        assertTrue("应产生多块，实际 $index 块", index > 1)
    }

    @Test
    fun `越界访问返回 null`() = runTest {
        val content = "只有一行\n"
        val source = textSource(content)
        assertNotNull(source.getChunk(0))
        assertNull(source.getChunk(1))
        assertNull(source.getChunk(100))
    }

    @Test
    fun `块偏移与块字节长度应单调且覆盖全文`() = runTest {
        val content = buildString {
            for (i in 1..1200) append("OFFSET_ROW_$i 的原始字节。\n")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val source = textSource(content, chunkBytes = 512)
        var index = 0
        var prev = 0L
        var total = 0L
        while (true) {
            val offset = source.chunkOffsetOf(index) ?: break
            val len = source.chunkByteLengthOf(index) ?: break
            assertEquals("块 $index 起始偏移应连续递增", prev, offset)
            assertTrue("块字节长度应为正", len > 0)
            prev = offset + len
            total += len
            index++
        }
        assertEquals("所有块字节长度之和应等于文件字节数", bytes.size.toLong(), total)
    }

    @Test
    fun `rawChunkBytes 拼接应无损还原原始文件字节`() = runTest {
        // 覆盖中文/换行/超长行混合，确保随机写回依赖的原始字节与文件一致
        val content = buildString {
            for (i in 1..400) append("RAW_ROW_$i 的原始内容，包含中文与英文 mixed content。\n")
            append("超长行".repeat(80_000))
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val source = textSource(content, chunkBytes = 1024)
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (true) {
            val raw = source.rawChunkBytes(index) ?: break
            out.write(raw)
            index++
        }
        assertTrue("应产生多块，实际 $index 块", index > 1)
        assertTrue("rawChunkBytes 拼接应与原文逐字节一致", out.toByteArray().contentEquals(bytes))
    }

    @Test
    fun `rawChunkBytes 单个块应与文件对应字节区间一致`() = runTest {
        val content = buildString {
            for (i in 1..600) append("CHUNK_CHECK_$i 内容。\n")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val source = textSource(content, chunkBytes = 512)
        // 取中间某块，校验其原始字节等于文件在该偏移区间的切片
        val idx = 7
        val offset = source.chunkOffsetOf(idx) ?: throw AssertionError("块 $idx 越界")
        val len = source.chunkByteLengthOf(idx) ?: throw AssertionError("块 $idx 越界")
        val raw = source.rawChunkBytes(idx) ?: throw AssertionError("块 $idx 读取失败")
        val slice = bytes.copyOfRange(offset.toInt(), (offset + len).toInt())
        assertTrue("块 $idx 原始字节应等于文件对应切片", raw.contentEquals(slice))
    }

    @Test
    fun `bom 情况下 rawChunkBytes 拼接应剥除 BOM`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = buildString {
            for (i in 1..200) append("BOM_ROW_$i 内容。\n")
        }
        val bytes = bom + body.toByteArray(Charsets.UTF_8)
        val source = PagedTextSource(open = { ByteArraySeekReader(bytes) }, chunkTargetBytes = 512)
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (true) {
            val raw = source.rawChunkBytes(index) ?: break
            out.write(raw)
            index++
        }
        assertTrue("BOM 应被剥离", out.toByteArray().contentEquals(body.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `随机跳块与顺序读取结果一致`() = runTest {
        val content = buildString {
            for (i in 1..800) append("RANDOM_ROW_$i 的内容。\n")
        }
        // 小块参数使块数足够多，覆盖"先随机访问触发惰性建索引，再顺序拼接"的路径
        val source = textSource(content, chunkBytes = 512)
        assertNotNull(source.getChunk(5))
        assertNotNull(source.getChunk(2))
        assertNotNull(source.getChunk(30))
        val (joined, _) = readAll(source)
        assertEquals(content, joined)
    }

    private fun runTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}