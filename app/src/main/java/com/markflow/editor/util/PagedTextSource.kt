package com.markflow.editor.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 大文本文件的字节分块读取内核：O(1) 跳块 + 懒惰块索引 + 有界内存。
 *
 * 相对旧实现（每页重开流 + 从头跳过 N 页，O(n)）的关键改进：
 * - 按字节分块（约 [chunkTargetBytes]），块边界落在换行处，不把行拆开；
 *   且边界按编码单元对齐（UTF-16 取 2 字节边界、UTF-8 硬截断回退完整字符），
 *   杜绝块首从字符中间开始解码导致的整块错位乱码；
 * - 块偏移索引 [chunkOffsets] 惰性构建：只索引已访问过的块，打开文件不预扫全文，
 *   跳块直接 seek 到块起始字节偏移，O(1)；
 * - 单行超长（如压缩日志）超过 [MAX_LINE_BYTES] 强制截断成独立块，防止单块膨胀；
 * - 编码：首次访问时用 [EncodingDetector] 探测（支持 [charsetOverride]），
 *   BOM 在首块起始偏移处跳过，解码用 REPLACE 不抛异常。
 *
 * 线程安全：所有公开方法内部持锁串行执行。
 */
class PagedTextSource(
    private val open: () -> RandomSeekReader?,
    private val chunkTargetBytes: Int = DEFAULT_CHUNK_TARGET_BYTES,
    private val charsetOverride: Charset? = null
) {

    companion object {
        const val DEFAULT_CHUNK_TARGET_BYTES = 10 * 1024

        /** 单行超过该字节数时强制截断成独立块（含无换行的超长行） */
        const val MAX_LINE_BYTES = 200 * 1024

        /** 编码探测采样的头字节数 */
        private const val SAMPLE_SIZE = 64 * 1024

        /** 单次顺序读的缓冲大小 */
        private const val READ_BUF = 8192
    }

    /** 单块读取结果 */
    data class Chunk(val text: String)

    private val lock = Mutex()

    /** (解码字符集, 首块需跳过的 BOM 字节数) */
    private var resolvedCharset: Charset? = null
    private var bomLength = 0

    /** 每块起始字节偏移；长度 = 已索引块数 */
    private val chunkOffsets = ArrayList<Long>()
    /** 每块原始字节长度，与 [chunkOffsets] 一一对应（写回/随机写定位用） */
    private val chunkByteLengths = ArrayList<Int>()
    /** 下一块（索引建到的地方）的起始字节偏移 */
    private var nextStart = 0L
    /** 已扫描到文件末尾 */
    private var eofReached = false

    /** 实际使用的解码字符集名（override 或探测所得）；未解析时返回 null */
    suspend fun detectedCharsetName(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            resolveEncodingLocked()
            resolvedCharset?.name()
        }
    }

    /** 实际使用的解码字符集（写回编码用）；未解析时返回 null */
    suspend fun charset(): Charset? = withContext(Dispatchers.IO) {
        lock.withLock {
            resolveEncodingLocked()
            resolvedCharset
        }
    }

    /** 第 [index] 块的起始字节偏移；越界/EOF 返回 null */
    suspend fun chunkOffsetOf(index: Int): Long? = withContext(Dispatchers.IO) {
        lock.withLock {
            resolveEncodingLocked()
            if (!ensureIndexLocked(index)) return@withLock null
            chunkOffsets[index]
        }
    }

    /**
     * 将全局字节偏移 [byteOffset] 解析为所在块索引（惰性推进索引直至覆盖该偏移）。
     * 用于"恢复阅读位置/书签跳转"：保存编辑只会改变目标块及其之后的字节，
     * 该偏移作为稳定锚点，写回后仍能反查回正确内容。越界/打开失败返回 null。
     */
    suspend fun blockIndexForByteOffset(byteOffset: Long): Int? = withContext(Dispatchers.IO) {
        lock.withLock { blockIndexForByteOffsetLocked(byteOffset) }
    }

    /** [blockIndexForByteOffset] 的持锁实现（夹具下从锁内调用） */
    private fun blockIndexForByteOffsetLocked(byteOffset: Long): Int? {
        resolveEncodingLocked()
        if (byteOffset <= 0) return 0
        // 已索引覆盖上限为 nextStart（下一块起点）；未覆盖则惰性推进，直至命中或抵达 EOF
        while (true) {
            if (byteOffset < nextStart) {
                return floorChunkIndexLocked(byteOffset).let { if (it >= 0) it else 0 }
            }
            if (!advanceOneLocked()) return null
        }
    }

    /**
     * 确保块索引已覆盖到 [index]；越界（EOF）或打开失败返回 false
     */
    private fun ensureIndexLocked(index: Int): Boolean {
        while (chunkOffsets.size <= index) {
            if (!advanceOneLocked()) return false
        }
        return true
    }

    /** 推进一块索引；EOF 或打开失败返回 false */
    private fun advanceOneLocked(): Boolean {
        if (eofReached) return false
        val bytes = readChunkLocked(nextStart) ?: return false
        if (bytes.isEmpty()) {
            eofReached = true
            return false
        }
        chunkOffsets.add(nextStart)
        chunkByteLengths.add(bytes.size)
        nextStart += bytes.size
        return true
    }

    /** 已索引块中最大的索引 i 且 chunkOffsets[i] <= [byteOffset]；无则 -1 */
    private fun floorChunkIndexLocked(byteOffset: Long): Int {
        var lo = 0
        var hi = chunkOffsets.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chunkOffsets[mid] <= byteOffset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 第 [index] 块的原始字节长度；越界/EOF 返回 null */
    suspend fun chunkByteLengthOf(index: Int): Int? = withContext(Dispatchers.IO) {
        lock.withLock {
            resolveEncodingLocked()
            if (!ensureIndexLocked(index)) return@withLock null
            chunkByteLengths[index]
        }
    }

    /** 第 [index] 块的原始字节（写回时用于平移/重组文件）；越界/EOF 返回 null */
    suspend fun rawChunkBytes(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        lock.withLock {
            resolveEncodingLocked()
            if (!ensureIndexLocked(index)) return@withLock null
            val start = chunkOffsets[index]
            val len = chunkByteLengths[index]
            val reader = open() ?: return@withLock null
            try {
                val buf = ByteArray(len)
                var got = 0
                var pos = start
                while (got < len) {
                    val n = reader.read(buf, pos, len - got)
                    if (n <= 0) break
                    got += n
                    pos += n
                }
                if (got < len) buf.copyOf(got) else buf
            } finally {
                runCatching { reader.close() }
            }
        }
    }

    /**
     * 读取第 [index] 块文本（绝对块索引，从 0 开始）。
     * 索引越界、文件已读完或打开失败返回 null。
     */
    suspend fun getChunk(index: Int): Chunk? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (index < 0) return@withLock null
            resolveEncodingLocked()
            if (!ensureIndexLocked(index)) return@withLock null
            val start = chunkOffsets[index]
            val bytes = readChunkLocked(start) ?: return@withLock null
            if (bytes.isEmpty()) {
                eofReached = true
                return@withLock null
            }
            Chunk(decodeLocked(bytes))
        }
    }

    /** 从 [start] 读取一块字节（边界落在换行处且按编码单元对齐，见类注释），失败返回 null */
    private fun readChunkLocked(start: Long): ByteArray? {
        val reader = open() ?: return null
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(READ_BUF)
            var total = 0
            // 换行搜索的起点游标：每轮只扫描新增字节（回退 1 字节防 UTF-16 换行对跨界）
            var scanFrom = -1
            while (true) {
                val n = reader.read(buf, start + total, READ_BUF)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                if (total >= chunkTargetBytes) {
                    val bytes = out.toByteArray()
                    val from = if (scanFrom < 0) chunkTargetBytes else scanFrom
                    // 从目标字节数之后找第一个换行作为块边界（按编码对齐）
                    val end = newlineAlignedEnd(bytes, from)
                    if (end > 0) return bytes.copyOf(end)
                    scanFrom = (total - 1).coerceAtLeast(chunkTargetBytes)
                    if (total >= MAX_LINE_BYTES) return alignHardCut(bytes)
                } else if (total >= MAX_LINE_BYTES) {
                    return alignHardCut(out.toByteArray())
                }
            }
            return if (total == 0) ByteArray(0) else out.toByteArray()
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * 从 [from] 起查找换行序列，返回块边界（保留字节数，含换行本身）；找不到返回 -1。
     *
     * 边界必须落在编码单元上，否则下一块会从字符中间开始解码（UTF-16 整块错位乱码）：
     * - UTF-16LE：换行为 `0A 00`（偶偏移对齐），边界 = 换行起始 + 2；
     * - UTF-16BE：换行为 `00 0A`（偶偏移对齐），边界 = 换行起始 + 2；
     * - 其他（UTF-8/GBK 等）：换行为单字节 `0A`（UTF-8 多字节序列、GBK 尾字节
     *   均不含 0x0A，不会误判），边界 = 位置 + 1。
     *
     * UTF-16 的"偶偏移"成立前提：BOM 长度为 0 或 2（偶），且所有块长均为偶数——
     * 本函数与 [alignHardCut] 共同保证该不变式。
     */
    private fun newlineAlignedEnd(bytes: ByteArray, from: Int): Int {
        val cs = resolvedCharset ?: Charsets.UTF_8
        val n = bytes.size
        if (isUtf16(cs)) {
            val le = isUtf16Le(cs)
            var i = from.coerceAtLeast(0)
            // 换行对必须起始于编码单元边界（偶偏移）
            if (i % 2 != 0) i++
            while (i + 1 < n) {
                val b0 = bytes[i]
                val b1 = bytes[i + 1]
                if (le) {
                    if (b0 == 0x0A.toByte() && b1 == 0x00.toByte()) return i + 2
                } else {
                    if (b0 == 0x00.toByte() && b1 == 0x0A.toByte()) return i + 2
                }
                i += 2
            }
            return -1
        }
        var i = from.coerceAtLeast(0)
        while (i < n) {
            if (bytes[i] == '\n'.code.toByte()) return i + 1
            i++
        }
        return -1
    }

    /**
     * 超长行硬截断时回退到完整字符边界，避免把多字节字符从中间切开：
     * - UTF-16：向下取偶（2 字节编码单元）；
     * - UTF-8：末尾若为不完整多字节序列则整体回退（最多 3 字节）；
     * - GBK/GB18030：首/尾字节范围重叠无法可靠回退，保持原样，
     *   解码端 REPLACE 最多在接缝处产生一个替换符，可接受。
     */
    private fun alignHardCut(bytes: ByteArray): ByteArray {
        val cs = resolvedCharset ?: Charsets.UTF_8
        var end = bytes.size
        when {
            isUtf16(cs) -> {
                end = end and 1.inv()
            }
            isUtf8(cs) -> {
                var i = end - 1
                var cont = 0
                while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80 && cont < 3) {
                    cont++
                    i--
                }
                if (i >= 0 && cont in 1..3) {
                    val lead = bytes[i].toInt() and 0xFF
                    val need = when {
                        lead and 0xE0 == 0xC0 -> 2
                        lead and 0xF0 == 0xE0 -> 3
                        lead and 0xF8 == 0xF0 -> 4
                        else -> 1
                    }
                    if (cont + 1 < need) end = i
                }
            }
        }
        // 防御：回退结果不得为空块（空块会被误判为 EOF）
        if (end <= 0) return bytes
        return if (end >= bytes.size) bytes else bytes.copyOf(end)
    }

    private fun isUtf16(cs: Charset): Boolean =
        cs.name().uppercase().startsWith("UTF-16")

    private fun isUtf16Le(cs: Charset): Boolean =
        cs.name().uppercase().endsWith("LE")

    private fun isUtf8(cs: Charset): Boolean =
        cs.name().uppercase().replace("_", "-").startsWith("UTF-8")

    /** 惰性解析并缓存编码与 BOM，仅初始化一次 */
    private fun resolveEncodingLocked() {
        if (resolvedCharset != null) return
        val reader = open()
        if (reader == null) {
            resolvedCharset = Charsets.UTF_8
            return
        }
        try {
            val sample = ByteArray(SAMPLE_SIZE)
            val n = reader.read(sample, 0, SAMPLE_SIZE)
            val bytes = if (n <= 0) ByteArray(0) else sample.copyOf(n)
            val det = EncodingDetector.detectSample(bytes)
            resolvedCharset = charsetOverride ?: det.charset
            bomLength = det.bomLength
            nextStart = bomLength.toLong()
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun decodeLocked(bytes: ByteArray): String {
        val cs = resolvedCharset ?: Charsets.UTF_8
        val decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}