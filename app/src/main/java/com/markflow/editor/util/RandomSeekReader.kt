package com.markflow.editor.util

import java.io.Closeable

/**
 * 支持随机 seek 读取的文件句柄抽象。
 *
 * Android 实现由存储层提供：
 * - file:// → RandomAccessFile
 * - content:// → ParcelFileDescriptor 的 FileChannel（真 O(1) seek）
 *
 * 纯 JVM 接口，便于内核在 JVM 单元测试中注入内存实现。
 */
interface RandomSeekReader : Closeable {

    /** 文件总字节数 */
    val length: Long

    /**
     * 从绝对偏移 [offset] 读取至多 [maxLen] 字节到 [buffer]。
     * @return 实际读取的字节数；到达文件末尾返回 -1
     */
    fun read(buffer: ByteArray, offset: Long, maxLen: Int): Int
}