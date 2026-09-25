package com.markflow.editor.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.annotation.SuppressLint
import android.app.RecoverableSecurityException
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.markflow.editor.domain.model.FileSource
import com.markflow.editor.domain.model.FileType
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.PendingFileOperation
import com.markflow.editor.domain.model.SecurityConsentRequiredException
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.util.EncodingDetector
import com.markflow.editor.util.RandomSeekReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Collections
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件存储仓库
 * 负责所有文件 I/O 操作：扫描、读取、写入、删除、重命名 Markdown 文件
 * 严格遵循 Android Scoped Storage 规范，兼容 Android 10+
 *
 * 核心策略：
 * - Android 10 以下：直接使用 File API 访问外部存储
 * - Android 10+：优先使用 MediaStore API 访问共享存储
 * - 同时支持通过 SAF (Storage Access Framework) 的 DocumentFile API
 */
@Singleton
class StorageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StorageRepository"

        /** 图片插入时缩放后的最大边长（像素），用于控制磁盘占用 */
        private const val IMAGE_MAX_EDGE_PX = 2048
        /** JPEG 压缩质量 */
        private const val IMAGE_JPEG_QUALITY = 85
        /** 图片插入时存到 .md 文件同级目录的子目录名 */
        private const val IMAGE_ATTACHMENT_DIR = "images"
        /** 让 MediaStore 忽略 images/ 目录的名（防止插入的图片被相册扫描） */
        private const val IMAGE_NOMEDIA_NAME = ".nomedia"
        /** 仅 Markdown 相关扩展名参与图片清理（非 .md 不产生 relative images/ 引用） */
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
        /** 图片清理时，超过该字节数的现存 .md 不整载做引用收集（保守保留，防 OOM） */
        private const val MAX_MD_REFERRER_BYTES = 4 * 1024 * 1024
        /** 本 app 自动生成图片副本的文件名前缀（与历史无前缀格式并存，见 REGEX_GENERATED_IMAGE） */
        private const val IMAGE_GENERATED_PREFIX = "mf_"
        /**
         * 仅匹配本 app 自动生成的图片副本名，防止误删用户资源：
         * - 新格式（本次起）：`mf_{13位毫秒时间戳}_{4位随机}.jpg`
         * - 历史格式（本次之前）：`{13位毫秒时间戳}_{4位随机}.jpg`
         * 13 位毫秒时间戳 + 4 位随机数是我们独有的生成规则，用户常见的 `2024_01.jpg`、
         * `1_2.jpg` 等命名（位数不符）不会被误判为副本。仅匹配此正则的文件才可被清理回收。
         */
        private val REGEX_GENERATED_IMAGE = Regex("""^(?:mf_)?\d{13}_\d{4}\.jpg$""")
        // Markdown 图片引用：![alt](images/xxx.png) ；捕获目标路径
        private val REGEX_MD_IMAGE = Regex("""!\[[^\]]*]\(\s*([^)\s]+)\s*\)""")
        // HTML 图片引用：<img src="images/xxx.png">
        private val REGEX_HTML_IMG = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }

    // ==================== 文件扫描 ====================

    /**
     * 扫描设备上所有支持的文本文件
     * 返回排序后的 Flow 列表
     *
     * @param sortMode 排序模式
     * @return Flow<List<MarkdownFile>> 文件列表流
     */
    fun scanMarkdownFiles(sortMode: SortMode = SortMode.BY_TIME_DESC): Flow<List<MarkdownFile>> = flow {
        val files = mutableListOf<MarkdownFile>()

        withContext(Dispatchers.IO) {
            // 方式一：通过 MediaStore 查询（Android 10+ 主要方式）
            files.addAll(queryMediaStore())

            // 方式二：扫描传统文件目录（兼容旧设备，去重）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val legacyFiles = scanLegacyDirectory()
                val existingUris = files.map { it.uri }.toSet()
                files.addAll(legacyFiles.filter { it.uri !in existingUris })
            }
        }

        // 排序
        val sorted = when (sortMode) {
            SortMode.BY_TIME_DESC -> files.sortedByDescending { it.lastModified }
            SortMode.BY_NAME -> files.sortedBy { it.fileName.lowercase() }
        }

        emit(sorted)
    }

    /**
     * 通过 MediaStore API 查询 Markdown 文件
     * 适用于 Android 10+ 的分区存储环境
     */
    private fun queryMediaStore(): List<MarkdownFile> {
        val files = mutableListOf<MarkdownFile>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val mimeCol = MediaStore.Files.FileColumns.MIME_TYPE
        val nameCol = MediaStore.Files.FileColumns.DISPLAY_NAME
        val relPathCol = MediaStore.Files.FileColumns.RELATIVE_PATH

        // 白名单（OR）：MIME 为 text/* 或 octet-stream，或文件名以受支持后缀结尾。
        // 后缀匹配必须覆盖全部受支持后缀（FileType.ALL_EXTENSIONS），
        // 否则重命名改后缀后（如 .md -> .json）文件会因 MIME 变化而从列表消失。
        val whitelist = buildList {
            add("$mimeCol LIKE ?")
            add("$mimeCol = ?")
            FileType.ALL_EXTENSIONS.forEach { add("$nameCol LIKE ?") }
        }
        val whitelistArgs = buildList {
            add("text/%")
            add("application/octet-stream")
            FileType.ALL_EXTENSIONS.forEach { add("%.$it") }
        }

        // 排除媒体文件（AND）：.ts 视频等 MIME 为 video/audio/image 的文件不显示。
        // MIME 为 null 时放行，避免误杀仅靠后缀匹配的文本文件。
        val mediaExclusion = listOf("video/%", "audio/%", "image/%")
            .joinToString(" AND ") { "($mimeCol IS NULL OR $mimeCol NOT LIKE ?)" }
        val mediaArgs = listOf("video/%", "audio/%", "image/%")

        // 仅扫描文档目录（AND，Android 10+）：Documents/、Download/，禁止全盘扫描
        val dirClause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            " AND ($relPathCol LIKE ? OR $relPathCol LIKE ?)"
        } else {
            ""
        }
        val dirArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf("Documents/%", "Download/%")
        } else {
            emptyList()
        }

        val selection = "(${whitelist.joinToString(" OR ")}) AND ($mediaExclusion)$dirClause"
        val selectionArgs = whitelistArgs + mediaArgs + dirArgs

        var cursor: Cursor?
        try {
            cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                null
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColIdx = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeColIdx = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                while (it.moveToNext()) {
                    // 行级容错：单行脏数据（如 DISPLAY_NAME 为 null）只跳过该行，
                    // 不允许整次扫描中断（否则极端 ROM 上一行脏数据即可让列表变空/截断）
                    try {
                        val fileName = it.getString(nameColIdx) ?: continue
                        // 仅处理支持的文件类型
                        val fileType = FileType.resolve(fileName) ?: continue

                        // 兜底：排除媒体文件（SQL 层已过滤，此处双保险）
                        if (mimeColIdx >= 0) {
                            val mime = it.getString(mimeColIdx) ?: ""
                            if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")) {
                                continue
                            }
                            // 部分 ROM 下 .ts 视频 MIME 为 null，按后缀二次兜底排除
                            if (mime.isEmpty() && fileName.endsWith(".ts", ignoreCase = true)) {
                                continue
                            }
                        }

                        val id = it.getLong(idCol)
                        val filePath = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                        val dateModified = it.getLong(dateCol) * 1000L // 转换为毫秒
                        val fileSize = it.getLong(sizeCol)

                        val uri = ContentUris.withAppendedId(collection, id).toString()

                        files.add(
                            MarkdownFile(
                                uri = uri,
                                fileName = fileName,
                                filePath = filePath,
                                lastModified = dateModified,
                                fileSize = fileSize,
                                isMarkdown = fileType.isMarkdown,
                                grammarName = fileType.grammarName
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "跳过 MediaStore 异常行", e)
                    }
                }
            }
        } catch (e: Exception) {
            // 查询失败时返回空列表，由调用方处理
            Log.e(TAG, "Operation failed", e)
        }

        return files
    }

    /**
     * 扫描传统文件系统目录（兼容 Android 9 及以下）
     * 仅扫描 Documents/ 与 Download/ 文档目录，禁止全盘扫描
     */
    private fun scanLegacyDirectory(): List<MarkdownFile> {
        val files = mutableListOf<MarkdownFile>()

        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            scanDirectoryRecursive(documentsDir, files)
            scanDirectoryRecursive(downloadsDir, files)
        } catch (e: Exception) {
            Log.e(TAG, "Operation failed", e)
        }

        return files
    }

    /**
     * 递归扫描目录（限制深度为 3，避免性能问题）
     */
    private fun scanDirectoryRecursive(
        directory: File,
        result: MutableList<MarkdownFile>,
        depth: Int = 0
    ) {
        if (depth > 3 || !directory.isDirectory) return

        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && !child.name.startsWith(".")) {
                scanDirectoryRecursive(child, result, depth + 1)
            } else if (child.isFile) {
                val fileType = FileType.resolve(child.name) ?: continue
                result.add(
                    MarkdownFile(
                        uri = Uri.fromFile(child).toString(),
                        fileName = child.name,
                        filePath = child.absolutePath,
                        lastModified = child.lastModified(),
                        fileSize = child.length(),
                        isMarkdown = fileType.isMarkdown,
                        grammarName = fileType.grammarName
                    )
                )
            }
        }
    }

    // ==================== 文件读取 ====================

    /**
     * 根据 URI 读取文件内容，自动探测或按指定编码解码
     *
     * @param uriString 文件 URI 字符串
     * @param charsetOverride 手动指定编码；为 null 时自动探测
     * @return 文件文本内容，读取失败返回 null
     */
    suspend fun readFileContent(uriString: String, charsetOverride: String? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = openRawStream(uriString)?.use { it.readBytes() } ?: return@withContext null
                val override = charsetOverride?.let { runCatching { Charset.forName(it) }.getOrNull() }
                EncodingDetector.decode(bytes, override)
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                null
            }
        }

    /**
     * 探测文件编码（仅读头部采样），返回字符集名；失败返回 null。
     * 供 UI 展示当前编码或用例中预判。
     */
    fun detectEncodingName(uriString: String): String? {
        return try {
            val sample = openRawStream(uriString)?.use { readAtMost(it, 64 * 1024) } ?: return null
            EncodingDetector.detectSample(sample).charset.name()
        } catch (_: Exception) {
            null
        }
    }

    /** 按文件 URI scheme 选择流来源：file:// 用 FileInputStream，其余用 ContentResolver */
    private fun openRawStream(uriString: String): InputStream? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") FileInputStream(uri.path ?: "")
            else context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    /** 从流读取至多 [max] 字节（兼容 minSdk<P33，不走 InputStream.readNBytes） */
    private fun readAtMost(stream: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val read = stream.read(buffer, total, max - total)
            if (read == -1) break
            total += read
        }
        return buffer.copyOf(total)
    }

    /**
     * 获取文件大小（字节）；无法查询时返回 null
     */
    fun getFileSize(uriString: String): Long? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val length = uri.path?.let { File(it).length() } ?: 0L
                if (length > 0) length else null
            } else {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 打开文件输入流（供分页只读阅读器按需读取）
     */
    fun openInputStream(uriString: String): InputStream? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") FileInputStream(uri.path ?: "")
            else context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 打开支持随机 seek 读取的文件源（大 txt O(1) 跳块的前置条件）。
     *
     * - file:// → RandomAccessFile（lseek）
     * - content:// → ParcelFileDescriptor 的 FileChannel（底层为真实文件，支持 seek）
     *
     * 使用方必须负责调用 close() 释放句柄。
     */
    fun openSeekable(uriString: String): RandomSeekReader? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val raf = RandomAccessFile(uri.path ?: "", "r")
                object : RandomSeekReader {
                    override val length: Long get() = raf.length()

                    override fun read(buffer: ByteArray, offset: Long, maxLen: Int): Int {
                        raf.seek(offset)
                        return raf.read(buffer, 0, maxLen)
                    }

                    override fun close() {
                        runCatching { raf.close() }
                    }
                }
            } else {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val channel = FileInputStream(pfd.fileDescriptor).channel
                object : RandomSeekReader {
                    override val length: Long get() = channel.size()

                    override fun read(buffer: ByteArray, offset: Long, maxLen: Int): Int {
                        channel.position(offset)
                        return channel.read(ByteBuffer.wrap(buffer, 0, maxLen))
                    }

                    override fun close() {
                        runCatching { channel.close() }
                        runCatching { pfd.close() }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 文件写入/保存 ====================

    /**
     * 打开 file:// 的可写句柄（"rw" 模式），用于分段编辑的随机写回。
     * content:// 不支持随机写，返回 null；使用方必须负责 close()。
     */
    fun openFileWriter(uriString: String): RandomAccessFile? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "file") return null
            RandomAccessFile(uri.path ?: "", "rw")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 用 [tempFile] 内容整体覆盖目标文件（分段编辑的 content:// 写回路径）。
     * - content://（Documents/MediaStore）：openFileDescriptor("wt") 截断覆盖；
     * - file://：RandomAccessFile 清空后写入。
     * 返回是否成功；成功后目标文件内容即 tempFile 内容。
     */
    suspend fun overwriteFileFromTemp(uriString: String, tempFile: File): Boolean =
        withContext(Dispatchers.IO) {
            var pfd: android.os.ParcelFileDescriptor? = null
            try {
                val uri = Uri.parse(uriString)
                if (uri.scheme == "file") {
                    RandomAccessFile(uri.path ?: "", "rw").use { raf ->
                        raf.setLength(0)
                        tempFile.inputStream().use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                            }
                        }
                        raf.fd.sync()
                    }
                    return@withContext true
                }
                pfd = context.contentResolver.openFileDescriptor(uri, "wt") ?: return@withContext false
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    tempFile.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.fd.sync()
                }
                true
            } catch (_: Exception) {
                false
            } finally {
                runCatching { pfd?.close() }
            }
        }

    /**
     * 将内容写入文件
     * 如果文件已存在则覆盖，否则创建新文件
     *
     * @param uriString 文件 URI
     * @param content 要写入的内容
     * @param charset 写回编码；默认 UTF-8（original 保持历史行为）。保存普通文件时应传入
     *   读取时探测/手动指定生效的字符集，避免把 GBK / UTF-16 等非 UTF-8 文件不可逆转码。
     * @return 是否写入成功
     */
    suspend fun writeFileContent(
        uriString: String,
        content: String,
        charset: Charset = Charsets.UTF_8
    ): Boolean = withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val outputStream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext false
                outputStream.use {
                    it.write(buildWritePayload(content, charset))
                    it.flush()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                false
            }
        }

    /**
     * 按 [charset] 编码文本为写回字节。
     *
     * Java 的 "UTF-16LE"/"UTF-16BE" Charset 编码时**不会**自动加 BOM，而本应用读取时靠
     * `EncodingDetector` 以 BOM 识别 UTF-16 家族——若不加 BOM 直接回写，下次重新探测会
     * 把无 BOM 的 UTF-16 误判为 GBK / UTF-8（乱码），造成二次损坏。因此这里对 UTF-16LE/BE
     * 手动补 BOM；标准 "UTF-16"（大端）其 Charset 自身已带 BOM，不再重复追加。
     */
    private fun buildWritePayload(content: String, charset: Charset): ByteArray {
        val bytes = content.toByteArray(charset)
        val name = charset.name()
        val bom = when {
            name.equals("UTF-16BE", ignoreCase = true) ->
                byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            name.equals("UTF-16LE", ignoreCase = true) ->
                byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> null
        }
        return if (bom != null) bom + bytes else bytes
    }

    // ==================== 图片插入 ====================

    /**
     * 将选中的图片解码压缩后写入 Markdown 文件同级的 images/ 目录，返回 Markdown 相对路径。
     *
     * @param imageUriString 图片源（通常为图库返回的 content:// URI）
     * @param mdFileUriString 当前打开的 .md 文件 URI（file:// 或 MediaStore content://）
     * @return 相对路径 "images/{name}.jpg"；任一步失败返回 null。压缩失败时回退写入原图字节
     */
    suspend fun copyImageForMarkdown(imageUriString: String, mdFileUriString: String): String? =
        withContext(Dispatchers.IO) {
            val mdUri = Uri.parse(mdFileUriString)
            // 低内存路径：不再把整份源图 readBytes 载入，解码由 decodeDownscaledToJpeg 按 URI
            // 直接流式/FD解码（file→decodeFile、content→decodeFileDescriptor，inSampleSize 生效）。
            // 解码/压缩失败时返回 null，由各分支回退写入原始字节（保持原行为）。
            val decodedJpeg = decodeDownscaledToJpeg(imageUriString)

            val imageName =
                "$IMAGE_GENERATED_PREFIX${System.currentTimeMillis()}_${ThreadLocalRandom.current().nextInt(1000, 10000)}.jpg"
            val relativePath = "$IMAGE_ATTACHMENT_DIR/$imageName"

            if (mdUri.scheme == "file") {
                // file://（应用私有目录下的导入/分享文件）：File API 写入 md 同级 images/
                val mdParent = mdUri.path?.let { File(it).parentFile } ?: return@withContext null
                val destFile = File(File(mdParent, IMAGE_ATTACHMENT_DIR), imageName)
                try {
                    destFile.parentFile?.mkdirs() ?: return@withContext null
                    // 解码成功写缩放图；失败则回退原始字节（仅此兜底路径才整读源图）
                    val outBytes = decodedJpeg
                        ?: openRawStream(imageUriString)?.use { it.readBytes() }
                        ?: return@withContext null
                    destFile.outputStream().use { it.write(outBytes) }
                    // 写入 .nomedia 使相册不显示本目录复制来的图片
                    ensureImagesHiddenFromGallery(destFile.parentFile?.absolutePath)
                    relativePath
                } catch (_: Exception) {
                    Log.e(TAG, "copyImageForMarkdown write file:// failed")
                    null
                }
            } else {
                // content://（MediaStore 文档）：insert 到 md 同目录的 images/ 子目录
                val targetRelativePath = resolveMediaStoreDir(mdUri) + "/$IMAGE_ATTACHMENT_DIR/"
                try {
                    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val values = ContentValues().apply {
                        put(MediaStore.Files.FileColumns.DISPLAY_NAME, imageName)
                        put(MediaStore.Files.FileColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Files.FileColumns.RELATIVE_PATH, targetRelativePath)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                        }
                    }
                    val inserted = context.contentResolver.insert(collection, values)
                        ?: return@withContext null
                    // 写流失败（null）时不应提交空/半截图片记录：删除已插入的行并放弃本次插入，
                    // 避免 images/ 残留 0 字节损坏图、且避免向 .md 写入失效引用。
                    val os = context.contentResolver.openOutputStream(inserted, "wt")
                    if (os == null) {
                        runCatching { context.contentResolver.delete(inserted, null, null) }
                        return@withContext null
                    }
                    // 解码成功写缩放图；失败则回退原始字节（仅此兜底路径才整读源图）。
                    // 若回退也失败，删除刚插入的行并放弃，绝不提交空/半截图。
                    val toWrite = decodedJpeg ?: openRawStream(imageUriString)?.use { it.readBytes() }
                    if (toWrite == null) {
                        runCatching { context.contentResolver.delete(inserted, null, null) }
                        return@withContext null
                    }
                    os.use {
                        it.write(toWrite)
                        it.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                        context.contentResolver.update(inserted, values, null, null)
                    }
                    // 主存储下 images/ 目录的物理路径（DocumentFile 场景可空，则跳过 .nomedia）
                    val imagesDirPath = physicalImagesDirPath(targetRelativePath)
                    ensureImagesHiddenFromGallery(imagesDirPath)
                    relativePath
                } catch (_: Exception) {
                    Log.e(TAG, "copyImageForMarkdown write content:// failed")
                    null
                }
            }
        }

    /** 查询 MediaStore 文档所在目录（RELATIVE_PATH，不含文件名）；查不到回退 Documents/MarkFlow */
    private fun resolveMediaStoreDir(uri: Uri): String {
        return try {
            val projection = arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val rel = cursor.getString(idx).removeSuffix("/")
                        if (rel.isNotBlank()) return rel
                    }
                }
            }
            "Documents/MarkFlow"
        } catch (_: Exception) {
            "Documents/MarkFlow"
        }
    }

    /**
     * 在主存储上由 RELATIVE_PATH 求目录的物理绝对路径（仅 Android 10+ 的
     * `Environment.getExternalStorageDirectory()` 可用时有效）。DocumentFile / 其他卷返回 null，
     * 由调用方决定跳过 .nomedia 写入）。
     */
    private fun physicalImagesDirPath(relativeImagesPath: String): String? {
        return try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val clean = relativeImagesPath.trim('/')
            if (clean.isBlank()) null else "$root/$clean".removeSuffix("/")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 在 images/ 目录放置 .nomedia 并触发媒体重扫，使 MediaStore 忽略该目录——
     * 相册不再显示复制来的图片（物理文件保留，相对路径照常可用）。
     * 任何失败都静默忽略，不阻断图片插入。
     */
    private fun ensureImagesHiddenFromGallery(imagesDirAbsolutePath: String?) {
        if (imagesDirAbsolutePath.isNullOrBlank()) return
        try {
            val nomedia = File(imagesDirAbsolutePath, IMAGE_NOMEDIA_NAME)
            // 确保 .nomedia 存在（不存在则创建）
            if (!nomedia.exists()) {
                nomedia.parentFile?.mkdirs()
                nomedia.createNewFile()
            }
            // 每次插入后都重扫该目录：MediaProvider 扫描到 .nomedia 会把本目录已索引图片记录移除，
            // 保证"刚插入的那条记录"也能被清掉（否则只在首次创建 .nomedia 时扫一次，后续插入仍留在相册）
            MediaScannerConnection.scanFile(
                context,
                arrayOf(imagesDirAbsolutePath),
                null,
                null
            )
        } catch (_: Exception) {
            Log.e(TAG, "write .nomedia failed; images may appear in gallery")
        }
    }

    /**
     * 解码图片并压缩为 JPEG 字节流：长边缩放到 [IMAGE_MAX_EDGE_PX]，质量 [IMAGE_JPEG_QUALITY]。
     * 直接按 URI 解码（file→decodeFile / content→decodeFileDescriptor），不再先把整份源图 readBytes
     * 载入内存，显著降低低内存机型插入大图时的峰值占用；inSampleSize 逼近目标后再精确缩放。
     * 解码或压缩失败返回 null，由调用方回退写入原始字节。
     */
    private fun decodeDownscaledToJpeg(uriString: String): ByteArray? {
        val uri = Uri.parse(uriString)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeViaSource(uri, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
                return null
            }

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= IMAGE_MAX_EDGE_PX ||
                bounds.outHeight / (sample * 2) >= IMAGE_MAX_EDGE_PX
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val decoded = decodeViaSource(uri, opts) ?: return null

            var working = decoded
            val maxDim = maxOf(working.width, working.height)
            if (maxDim > IMAGE_MAX_EDGE_PX) {
                val ratio = IMAGE_MAX_EDGE_PX.toFloat() / maxDim
                working = Bitmap.createScaledBitmap(
                    decoded,
                    (working.width * ratio).toInt().coerceAtLeast(1),
                    (working.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (working !== decoded) decoded.recycle()
            }

            try {
                val out = ByteArrayOutputStream()
                if (!working.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)) {
                    return null
                }
                out.toByteArray()
            } finally {
                if (working !== decoded) working.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 按 URI 对应的最省内存方式做一次解码：file→decodeFile，content→decodeFileDescriptor */
    private fun decodeViaSource(uri: Uri, options: BitmapFactory.Options): Bitmap? {
        return if (uri.scheme == "file") {
            uri.path?.let { BitmapFactory.decodeFile(it, options) }
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            }
        }
    }

    /**
     * 创建新 Markdown 文件（使用 MediaStore API）
     *
     * @param fileName 文件名（不含 .md 扩展名）
     * @param content 初始内容
     * @return 新文件的 URI 字符串，失败返回 null
     */
    suspend fun createFile(fileName: String, content: String = ""): String? =
        withContext(Dispatchers.IO) {
            try {
                // 复用 sanitizeFileName：已有后缀则保留，无后缀才补 .md
                val fullFileName = sanitizeFileName(fileName)
                val mimeType = when {
                    fullFileName.endsWith(".md", ignoreCase = true) -> "text/markdown"
                    fullFileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
                    else -> "application/octet-stream"
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fullFileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/MarkFlow")
                        put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }

                val insertedUri = context.contentResolver.insert(collection, contentValues)
                    ?: return@withContext null

                // 写入初始内容
                context.contentResolver.openOutputStream(insertedUri, "wt")?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                // 标记文件写入完成（Android 10+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    context.contentResolver.update(insertedUri, contentValues, null, null)
                }

                // 统一 URI 形式：插入用 VOLUME_EXTERNAL_PRIMARY（写主卷），但扫描列表
                // （queryMediaStore）用 VOLUME_EXTERNAL 生成 uri。若直接返回 insertedUri，
                // 「最近打开」队列存的是 external_primary 形式，分区解析时与 pool 中的
                // external 形式匹配不上，新建文件不会出现在「最近打开」。此处按扫描同款
                // collection 以 id 重建，保证入队 URI 与数据池一致。
                val id = ContentUris.parseId(insertedUri)
                val scanCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                ContentUris.withAppendedId(scanCollection, id).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                null
            }
        }

    // ==================== 文件删除 ====================

    /**
     * 删除文件（支持批量删除）。并发执行 + 分批控制，减少大量文件删除时的卡顿。
     *
     * @param uris 要删除的文件 URI 列表
     * @return 成功删除的文件 URI 集合
     */
    @SuppressLint("NewApi") // RecoverableSecurityException(API29+) 仅 Android 11+ MediaStore 会抛出；旧系统不抛故 catch 安全，见方法内注释
    suspend fun deleteFiles(uris: List<String>): Set<String> = withContext(Dispatchers.IO) {
        // 删除前置解析各待删 Markdown 所属 images/ 目录的物理路径：
        // content:// URI 在文件删除后无法再查询 RELATIVE_PATH，若删除后再解析会回退到默认目录，
        // 可能误清别的文档的 images/，故必须在删除前解析。非 md / 解析失败返回 null（不清理）。
        val imagesParentsByUri = uris.associateWith {
            runCatching { resolveMarkdownImagesParentDir(it) }.getOrNull()
        }
        val batchSize = 16
        val succeeded = Collections.synchronizedSet(mutableSetOf<String>())
        val consentExceptions = Collections.synchronizedList(mutableListOf<SecurityConsentRequiredException>())
        uris.chunked(batchSize).forEach { batch ->
            batch.map { uriString ->
                async {
                    try {
                        val uri = Uri.parse(uriString)
                        val ok = when {
                            uri.scheme == "file" -> {
                                val file = File(uri.path ?: return@async)
                                file.exists() && file.delete()
                            }
                            else -> {
                                context.contentResolver.delete(uri, null, null) > 0
                            }
                        }
                        if (ok) succeeded.add(uriString)
                    } catch (e: RecoverableSecurityException) {
                        consentExceptions.add(
                            SecurityConsentRequiredException(
                                e.userAction.actionIntent.intentSender,
                                PendingFileOperation.Delete(listOf(uriString))
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Operation failed", e)
                    }
                }
            }.awaitAll()
        }
        // 删除成功后，仅对"删除成功且是 Markdown"的项做 images/ 未引用清理（引用计数式，见 cleanupSingleImagesDir）
        val parentsToClean = succeeded.mapNotNull { imagesParentsByUri[it] }.toSet()
        if (parentsToClean.isNotEmpty()) {
            parentsToClean.forEach { cleanupSingleImagesDir(it) }
        }
        // 如有需要用户授权的操作，汇总后抛给 UI 层处理：
        // - pendingOperation 携带全部待授权 URI，授权重试可覆盖整批（而非只重试第一个）；
        // - partialSucceeded 携带已成功删除的 URI，UI 层须先把它们从列表移除，
        //   否则已删文件仍显示在列表中（UI 与磁盘状态不一致）。
        consentExceptions.firstOrNull()?.let { first ->
            val allConsentUris = consentExceptions.flatMap {
                (it.pendingOperation as? PendingFileOperation.Delete)?.uris ?: emptyList()
            }
            throw SecurityConsentRequiredException(
                first.intentSender,
                PendingFileOperation.Delete(allConsentUris),
                succeeded
            )
        }
        succeeded
    }

    /**
     * 解析待删文件所属 `images/` 目录的**物理路径**（须在文件删除前调用）。
     *
     * content:// URI 一旦被删除便无法再查询其 RELATIVE_PATH（会回退到默认目录，误清别的文档的
     * images/），因此必须在此前置解析后，再由 [deleteFiles] 在删除成功后交给 [cleanupSingleImagesDir]。
     *
     * @return 所属 `images/` 目录的父目录物理绝对路径；非 Markdown / file 无法定位 / 其他卷返回 null
     */
    private fun resolveMarkdownImagesParentDir(uriString: String): String? {
        val uri = Uri.parse(uriString)
        // 仅 Markdown 会产生 relative images/ 引用。
        // 扩展名必须从“文件名”推断，而非从 URI 字符串取：content://media/... 的 URI 不含扩展名
        // （形如 file/45283），若用 substringAfterLast('.') 得空串，清理会被静默跳过（严重 bug）。
        val ext = if (uri.scheme == "file") {
            uri.path?.substringAfterLast('.', "")
        } else {
            getFileName(uriString)?.substringAfterLast('.', "")
        }?.lowercase()
        if (ext !in MARKDOWN_EXTENSIONS) return null
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).parentFile?.absolutePath }
        } else {
            physicalImagesDirPath(resolveMediaStoreDir(uri))
        }
    }

    /** 清理单个 .md 目录下的 images/：删除未被现存 Markdown 引用的图片副本 */
    private fun cleanupSingleImagesDir(mdParentDirPath: String) {
        try {
            val imagesDir = File(mdParentDirPath, IMAGE_ATTACHMENT_DIR)
            // 仅清理本 app 自动生成的图片副本（timestamp_random4.jpg）。用户手动放入/非自动命名
            // 的图片一律保留，避免误删用户资源（REGEX 必须显式守卫，缺失即会误删）。
            val candidates = imagesDir.listFiles { f ->
                f.isFile && f.name != IMAGE_NOMEDIA_NAME && REGEX_GENERATED_IMAGE.matches(f.name)
            } ?: return

            // 收集该目录现存 .md 引用的图片文件名（仅解析指向 images/ 的相对引用）
            val referenced = mutableSetOf<String>()
            File(mdParentDirPath).listFiles { file ->
                file.isFile && file.extension.lowercase() in MARKDOWN_EXTENSIONS
            }?.forEach { md ->
                try {
                    // 超大 .md 不整载（避免瞬间占用大内存），跳过其引用收集。
                    // 结果是该目录图片偏保守保留、不误删，符合安全优先。
                    if (md.length() > MAX_MD_REFERRER_BYTES) return@forEach
                    collectImageReferences(md.readText(Charsets.UTF_8), referenced)
                } catch (_: Exception) {
                    // 单个文件读取失败跳过，不影响其他
                }
            }

            candidates.forEach { img ->
                if (img.name !in referenced) {
                    img.delete()
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "cleanupOrphanImages failed")
        }
    }

    /** 从 Markdown 文本中收集指向 images/（[IMAGE_ATTACHMENT_DIR]）的相对图片引用文件名 */
    private fun collectImageReferences(text: String, out: MutableSet<String>) {
        // Markdown 语法：![alt](images/xx.png)  ；兼容引号形式
        REGEX_MD_IMAGE.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim()
            addReferenceIfInImages(path, out)
        }
        // HTML 语法：<img src="images/xx.png">
        REGEX_HTML_IMG.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim('"', '\'', ' ').trim()
            addReferenceIfInImages(path, out)
        }
    }

    /** 仅当引用是指向本 `images/` 目录的相对路径时，记为引用（含子目录内文件按 basename 记） */
    private fun addReferenceIfInImages(path: String, out: MutableSet<String>) {
        if (path.isBlank()) return
        // 拒绝绝对/协议路径/跳级路径，避免越界
        if (path.startsWith("/") ||
            path.contains("://") ||
            path == ".." || path.startsWith("../")
        ) return
        val segments = path.split('/').map { it.trim() }.filter { it.isNotBlank() }
        // 只要路径含 images/ 段就记为对 images/ 文件夹的引用（basename 匹配）。
        // 不设层级上限：放宽更偏"多保留、少误删"（真实图片在 images/ 顶层，部分异常层级
        // 引用亦能保住同名文件；代价仅是极端情况下个别孤儿图被保守保留）。
        if (segments.contains(IMAGE_ATTACHMENT_DIR)) {
            val name = segments.last()
            if (name.isNotBlank() && !name.contains("..")) out.add(name)
        }
    }

    // ==================== 文件重命名 ====================

    /**
     * 重命名文件
     *
     * 支持三种 URI 类型：
     * - file:// URI：直接文件系统操作（适用于应用私有目录下的导入文件）
     * - content://media/... URI：通过 MediaStore API 更新 DISPLAY_NAME
     * - content://...documents/... URI：通过 DocumentsContract.renameDocument
     *
     * @param uriString 文件 URI
     * @param newName 新文件名（不含 .md 扩展名）
     * @return Result<Unit> 成功或包含具体错误信息的失败结果
     */
    suspend fun renameFile(uriString: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                // 方案2：重命名完全尊重用户输入（含后缀），不再强制补 .md；
                // 无后缀即无后缀，改后缀即改后缀
                val fullNewName = newName.trim()

                // 校验文件名合法性
                validateFileName(fullNewName).getOrThrow()

                when {
                    // 路径一：file:// URI — 直接文件系统操作
                    uri.scheme.equals("file", ignoreCase = true) -> {
                        renameFileOnDisk(uri, fullNewName)
                    }
                    // 路径二：content:// URI — 通过 ContentProvider 操作
                    else -> {
                        renameFileViaContentProvider(uri, fullNewName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                Result.failure(e)
            }
        }

    /**
     * 处理 file:// URI 的重命名（直接文件系统操作）
     */
    private fun renameFileOnDisk(uri: Uri, fullNewName: String): Result<Unit> {
        val path = uri.path ?: return Result.failure(FileNotFoundException("无效的文件路径"))
        val file = File(path)
        if (!file.exists()) {
            return Result.failure(FileNotFoundException("文件不存在: $path"))
        }
        if (!file.isFile) {
            return Result.failure(IOException("目标路径不是文件: $path"))
        }

        val parentDir = file.parentFile ?: return Result.failure(IOException("无法获取父目录"))
        val newFile = File(parentDir, fullNewName)

        // 检查同名文件
        if (newFile.exists()) {
            return Result.failure(IOException("同名文件 \"$fullNewName\" 已存在"))
        }

        // 检查父目录是否可写
        if (!parentDir.canWrite()) {
            return Result.failure(SecurityException("没有该目录的写入权限"))
        }

        return if (file.renameTo(newFile)) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("文件重命名失败，请检查文件是否被其他程序占用"))
        }
    }

    /**
     * 处理 content:// URI 的重命名
     */
    @SuppressLint("NewApi") // 同 deleteFiles：RecoverableSecurityException 仅 API29+ 抛出，旧系统不命中此 catch
    private fun renameFileViaContentProvider(uri: Uri, fullNewName: String): Result<Unit> {
        // 路径 2a：MediaStore API（适用于 content://media/... URI）
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fullNewName)
                // 同步更新 MIME_TYPE，防止 MediaStore 因原 MIME（如 text/markdown）
                // 与新扩展名不匹配而自动追加原扩展名（例如 2255.cpp -> 2255.cpp.md）
                put(MediaStore.Files.FileColumns.MIME_TYPE, inferMimeType(fullNewName))
            }
            val rows = context.contentResolver.update(uri, contentValues, null, null)
            if (rows > 0) return Result.success(Unit)
        } catch (e: RecoverableSecurityException) {
            // Android 11+ 非本应用创建文件需要用户授权
            return Result.failure(
                SecurityConsentRequiredException(
                    e.userAction.actionIntent.intentSender,
                    PendingFileOperation.Rename(uri.toString(), fullNewName)
                )
            )
        } catch (e: SecurityException) {
            // MediaStore 无权限，尝试 DocumentsContract
        }

        // 路径 2b：DocumentsContract API（适用于 content://com.android.externalstorage.documents/... URI）
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                // 部分 DocumentProvider 会根据 MIME 强制校验/追加扩展名，
                // 先尝试同步更新 MIME_TYPE，再执行重命名
                try {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(DocumentsContract.Document.COLUMN_MIME_TYPE, inferMimeType(fullNewName))
                        },
                        null, null
                    )
                } catch (e: Exception) {
                    // Provider 不支持更新 MIME，继续尝试重命名
                }
                DocumentsContract.renameDocument(context.contentResolver, uri, fullNewName)
                Result.success(Unit)
            } else {
                Result.failure(IOException("不支持的文件类型，无法重命名"))
            }
        } catch (e: RecoverableSecurityException) {
            Result.failure(
                SecurityConsentRequiredException(
                    e.userAction.actionIntent.intentSender,
                    PendingFileOperation.Rename(uri.toString(), fullNewName)
                )
            )
        } catch (e: FileNotFoundException) {
            Result.failure(FileNotFoundException("文件不存在或已被删除"))
        } catch (e: SecurityException) {
            Result.failure(SecurityException("没有该文件的重命名权限"))
        }
    }

    /**
     * 清理并补全文件名：已带扩展名（非末尾点）则保留原后缀，
     * 无扩展名才补 .md，避免把非 md 文件的后缀强制改成 .md
     */
    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        val dotIndex = trimmed.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < trimmed.length - 1
        return if (hasExtension) trimmed else "$trimmed.md"
    }

    /**
     * 根据文件名推断 MIME 类型，用于重命名时同步更新 MediaStore 的 MIME_TYPE，
     * 避免系统因 MIME 与扩展名不匹配而自动追加原扩展名。
     */
    private fun inferMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "xml" -> "text/xml"
            "js", "mjs" -> "application/javascript"
            "json", "jsonc" -> "application/json"
            "yaml", "yml" -> "application/x-yaml"
            "toml" -> "application/toml"
            "csv" -> "text/csv"
            "diff", "patch" -> "text/x-diff"
            "sh", "bash", "zsh" -> "application/x-sh"
            "py" -> "text/x-python"
            "java" -> "text/x-java-source"
            "kt", "kts" -> "text/x-kotlin"
            "cpp", "cc", "cxx", "hpp", "hxx" -> "text/x-c++src"
            "c", "h" -> "text/x-csrc"
            "go" -> "text/x-go"
            "rs" -> "text/rust"
            "swift" -> "text/x-swift"
            "dart" -> "text/x-dart"
            "php" -> "text/x-php"
            "rb" -> "text/x-ruby"
            "lua" -> "text/x-lua"
            "sql" -> "application/sql"
            "ini", "conf", "cfg" -> "text/plain"
            "properties", "env" -> "text/plain"
            "dockerfile", "docker" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * 校验文件名合法性
     * @throws IllegalArgumentException 文件名不合法时抛出
     */
    private fun validateFileName(fileName: String): Result<String> {
        val nameWithoutExt = fileName.substringBeforeLast(".", fileName)

        // 空文件名
        if (nameWithoutExt.isBlank()) {
            return Result.failure(IllegalArgumentException("文件名不能为空"))
        }

        // 文件名长度限制（255 字节是大多数文件系统的限制）
        if (fileName.toByteArray(Charsets.UTF_8).size > 255) {
            return Result.failure(IllegalArgumentException("文件名过长（不能超过 255 字节）"))
        }

        // 非法字符检查（Windows / Linux 文件系统保留字符）
        // 必须对整个 fileName（含扩展名段）校验——仅校验 nameWithoutExt 会让 "evil.a/b"
        // 这类扩展名段带路径分隔符的名称漏检，导致 File(parent, name) 落入子路径 / 逃逸父目录（H4）
        val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val foundInvalid = fileName.firstOrNull { it in invalidChars }
        if (foundInvalid != null) {
            return Result.failure(
                IllegalArgumentException("文件名包含非法字符: \"$foundInvalid\"")
            )
        }

        // 仅由空格或点号组成
        if (nameWithoutExt.all { it == '.' || it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("文件名不能仅由空格或点号组成"))
        }

        return Result.success(fileName)
    }

    // ==================== 辅助方法 ====================

    /**
     * 扫描应用内部导入目录中的 Markdown 文件
     * 扫描 imported/ 和 shared/ 两个目录
     */
    fun scanImportedFiles(): List<MarkdownFile> {
        val files = mutableListOf<MarkdownFile>()
        val dirs = listOf(
            File(context.filesDir, "imported"),
            File(context.filesDir, "shared")
        )
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { child ->
                if (child.isFile) {
                    val fileType = FileType.resolve(child.name) ?: return@forEach
                    files.add(
                        MarkdownFile(
                            uri = child.toURI().toString(),
                            fileName = child.name,
                            filePath = child.absolutePath,
                            lastModified = child.lastModified(),
                            fileSize = child.length(),
                            source = FileSource.IMPORTED,
                            isMarkdown = fileType.isMarkdown,
                            grammarName = fileType.grammarName
                        )
                    )
                }
            }
        }
        return files
    }

    // ==================== 文件导入 ====================

    /**
     * 将外部文件导入到本地 "Documents/MarkFlow" 目录（通过 MediaStore）
     * 用于"打开"功能：用户从文件管理器选择文件后，复制到本地存储
     *
     * 仅在文件类型受支持时成功导入，否则返回 null。
     *
     * @param sourceUri 源文件的 content:// URI
     * @return 导入后的 content:// URI 字符串，失败返回 null
     */
    suspend fun importFile(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(sourceUri.toString())
                ?: "imported_${System.currentTimeMillis()}.txt"

            // 验证文件类型是否受支持（不支持则导入失败）
            if (FileType.resolve(fileName) == null) return@withContext null

            // 根据扩展名推断 MIME，必须与 DISPLAY_NAME 的扩展名匹配——
            // 若硬编码 text/plain，Android 11+ MediaStore 会因 MIME 与扩展名不匹配
            // 自动追加规范后缀（如 Main.java → Main.java.txt，同重命名的 X-H5 教训）
            val mimeType = inferMimeType(fileName)

            val contentValues = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/MarkFlow")
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val destUri = context.contentResolver.insert(collection, contentValues)
                ?: return@withContext null

            // 从源文件读取内容并写入目标
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destUri, "wt")?.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            // 标记文件写入完成（Android 10+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                context.contentResolver.update(destUri, contentValues, null, null)
            }

            // 统一 URI 形式：import 用 VOLUME_EXTERNAL_PRIMARY 写入，但扫描列表
            // （queryMediaStore）用 VOLUME_EXTERNAL 生成 uri。若直接返回 destUri，
            // 「最近打开」队列存的是 external_primary 形式，分区解析时与 pool 中的
            // external 形式匹配不上，导入后自动打开的文件不会出现在「最近打开」。
            // 此处按扫描同款 collection 以 id 重建（同 createFile 的既定策略）。
            val id = ContentUris.parseId(destUri)
            val scanCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            ContentUris.withAppendedId(scanCollection, id).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Operation failed", e)
            null
        }
    }

    /**
     * 从 URI 获取文件名
     *
     * file:// URI 由 File.toURI() 生成（编码态：# → %23、空格 → %20、中文 → %XX），
     * query 不支持 file scheme 会走 fallback，此时必须 Uri.decode 还原磁盘真实文件名，
     * 否则返回 %23%20 之类的编码串导致标题乱码、后缀判定错误。
     */
    fun getFileName(uriString: String): String? {
        try {
            val uri = Uri.parse(uriString)
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {
            // 忽略
        }
        val lastSegment = uriString.substringAfterLast("/")
        return if (uriString.startsWith("file:")) Uri.decode(lastSegment) else lastSegment
    }
}
