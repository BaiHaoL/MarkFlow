package com.markflow.editor.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本编码探测：解决 txt 乱码。
 *
 * 探测优先级：
 * 1. BOM（UTF-8 / UTF-16 LE / UTF-16 BE）—— 最强证据；
 * 2. 无 BOM 时用严格 UTF-8 校验（含非法字节序/超长编码语义）判定 UTF-8；
 * 3. 非 UTF-8 且含高字节 → 判定为 GB 系（GB18030/GBK/GB2312，按可用性回退）；
 * 4. 纯 ASCII → 按 UTF-8 处理（ASCII 是其子集）。
 *
 * 解码一律用 REPLACE 模式，任何非法字节都不抛异常、不中断读取。
 * 纯逻辑、无 Android 依赖，可直接 JUnit 单测。
 */
object EncodingDetector {

    /** 探测结果 */
    data class Detection(
        val charset: Charset,
        val hasBom: Boolean = false,
        val bomLength: Int = 0,
        val confidence: Confidence = Confidence.MEDIUM
    )

    enum class Confidence { HIGH, MEDIUM, LOW }

    /** 参与内容探测的头字节采样上限 */
    private const val SAMPLE_SIZE = 64 * 1024

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private val GB_CHARSET_NAMES = listOf("GB18030", "GBK", "GB2312")

    /**
     * 基于给定字节采样做编码探测。
     *
     * @param bytes 文件头部字节（至少含 BOM/前缀；建议传入尽量多的头若干 KB）
     * @return 探测到的编码
     */
    fun detectSample(bytes: ByteArray): Detection {
        // 1. BOM
        if (startsWith(bytes, UTF8_BOM)) {
            return Detection(StandardCharsets.UTF_8, true, UTF8_BOM.size, Confidence.HIGH)
        }
        if (startsWith(bytes, UTF16LE_BOM)) {
            return Detection(StandardCharsets.UTF_16LE, true, UTF16LE_BOM.size, Confidence.HIGH)
        }
        if (startsWith(bytes, UTF16BE_BOM)) {
            return Detection(StandardCharsets.UTF_16BE, true, UTF16BE_BOM.size, Confidence.HIGH)
        }

        // 2. 严格 UTF-8
        if (isValidUtf8(bytes)) {
            return Detection(StandardCharsets.UTF_8, false, 0, Confidence.HIGH)
        }

        // 3. 含高字节 → GB 系
        if (bytes.any { (it.toInt() and 0xFF) >= 0x80 }) {
            val gb = gbCharset() ?: StandardCharsets.ISO_8859_1
            return Detection(gb, false, 0, Confidence.MEDIUM)
        }

        // 4. 纯 ASCII → UTF-8
        return Detection(StandardCharsets.UTF_8, false, 0, Confidence.MEDIUM)
    }

    /**
     * 探测并解码字节流为字符串。
     * 使用 [charsetOverride]（手动指定）时跳过探测；否则自动探测。
     * 若检测到 BOM 会将其剥离。解码用 REPLACE，永不抛异常。
     */
    fun decode(
        bytes: ByteArray,
        charsetOverride: Charset? = null
    ): String {
        val detection = detectSample(bytes)
        val charset = charsetOverride ?: detection.charset
        val start = detection.bomLength // 无论选哪个编码，剥掉已识别的 BOM 字节更安全
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val tail = bytes.copyOfRange(start, bytes.size)
        return decoder.decode(ByteBuffer.wrap(tail)).toString()
    }

    /**
     * 严格校验是否为合法 UTF-8。
     *
     * 与 Charset 解码器的差异：允许字节流**结尾处不完整的序列**——
     * 探测采样可能恰好截断在多字节字符中间，此时不应判为非法；
     * 但中间出现非法字节序（孤立续字节、超长编码、代理区等）仍判非法。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= n) return true // 结尾不完整
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 1 >= n) return true
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    if (b == 0xE0 && (bytes[i + 1].toInt() and 0xE0) == 0x80) return false // 超长 E0 80..9F
                    if (i + 2 >= n) return true
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    if (b == 0xED && (bytes[i + 1].toInt() and 0xE0) == 0xA0) return false // 代理区 ED A0..BF
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 1 >= n) return true
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    if (b == 0xF0 && (bytes[i + 1].toInt() and 0xF0) == 0x80) return false // F0 80..8F
                    if (b == 0xF4 && (bytes[i + 1].toInt() and 0xF0) == 0xF0) return false // F4 90..BF
                    if (i + 2 >= n) return true
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    if (i + 3 >= n) return true
                    if ((bytes[i + 3].toInt() and 0xC0) != 0x80) return false
                    i += 4
                }
                else -> return false // 0x80..0xC1：孤立续字节 / 超长首字节
            }
        }
        return true
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun gbCharset(): Charset? {
        for (name in GB_CHARSET_NAMES) {
            val cs = runCatching { Charset.forName(name) }.getOrNull()
            if (cs != null) return cs
        }
        return null
    }
}