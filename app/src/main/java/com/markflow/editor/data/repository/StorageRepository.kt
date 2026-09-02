package com.markflow.editor.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.markflow.editor.domain.model.FileSource
import com.markflow.editor.domain.model.FileType
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.util.EncodingDetector
import com.markflow.editor.util.RandomSeekReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
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

        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"

        val selectionArgs = arrayOf(
            "text/%",
            "application/octet-stream",
            "%.md",
            "%.markdown"
        )

        var cursor: Cursor?
        try {
            cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

                while (it.moveToNext()) {
                    val fileName = it.getString(nameCol)
                    // 仅处理支持的文件类型
                    val fileType = FileType.resolve(fileName) ?: continue

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
     */
    private fun scanLegacyDirectory(): List<MarkdownFile> {
        val files = mutableListOf<MarkdownFile>()

        try {
            val rootDir = Environment.getExternalStorageDirectory()
            scanDirectoryRecursive(rootDir, files)
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
     * @return 是否写入成功
     */
    suspend fun writeFileContent(uriString: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                false
            }
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
            // 原图字节（分两次解码：先量尺寸、再采样解码）
            val rawBytes = openRawStream(imageUriString)?.use { it.readBytes() } ?: return@withContext null

            val imageName =
                "${System.currentTimeMillis()}_${ThreadLocalRandom.current().nextInt(1000, 10000)}.jpg"
            val relativePath = "$IMAGE_ATTACHMENT_DIR/$imageName"

            if (mdUri.scheme == "file") {
                // file://（应用私有目录下的导入/分享文件）：File API 写入 md 同级 images/
                val mdParent = mdUri.path?.let { File(it).parentFile } ?: return@withContext null
                val destFile = File(File(mdParent, IMAGE_ATTACHMENT_DIR), imageName)
                try {
                    destFile.parentFile?.mkdirs() ?: return@withContext null
                    val outBytes = runCatching { decodeDownscaledToJpeg(rawBytes) }
                        .getOrElse { rawBytes }
                    destFile.outputStream().use { it.write(outBytes) }
                    relativePath
                } catch (_: Exception) {
                    Log.e(TAG, "copyImageForMarkdown write file:// failed")
                    null
                }
            } else {
                // content://（MediaStore 文档）：insert 到 md 同目录的 images/ 子目录
                val targetRelativePath = resolveMediaStoreDir(mdUri) + "/$IMAGE_ATTACHMENT_DIR/"
                val outBytes = runCatching { decodeDownscaledToJpeg(rawBytes) }.getOrNull()
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
                    context.contentResolver.openOutputStream(inserted, "wt")?.use { os ->
                        os.write(outBytes ?: rawBytes)
                        os.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                        context.contentResolver.update(inserted, values, null, null)
                    }
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
     * 解码图片并压缩为 JPEG 字节流：长边缩放到 [IMAGE_MAX_EDGE_PX]，质量 [IMAGE_JPEG_QUALITY]。
     * 用 inSampleSize 逼近目标后再精确缩放，避免一次性解码超大图导致 OOM。
     */
    private fun decodeDownscaledToJpeg(rawBytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IOException("无法解码图片")
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMAGE_MAX_EDGE_PX ||
            bounds.outHeight / (sample * 2) >= IMAGE_MAX_EDGE_PX
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
            ?: throw IOException("解码图片失败")

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
                throw IOException("JPEG 压缩失败")
            }
            return out.toByteArray()
        } finally {
            if (working !== decoded) working.recycle()
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

                val uri = context.contentResolver.insert(collection, contentValues)
                    ?: return@withContext null

                // 写入初始内容
                context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                // 标记文件写入完成（Android 10+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }

                uri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
                null
            }
        }

    // ==================== 文件删除 ====================

    /**
     * 删除文件（支持批量删除）
     *
     * @param uris 要删除的文件 URI 列表
     * @return 成功删除的数量
     */
    suspend fun deleteFiles(uris: List<String>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        for (uriString in uris) {
            try {
                val uri = Uri.parse(uriString)
                when {
                    uri.scheme == "file" -> {
                        // file:// URI：直接删除文件（用于应用私有目录下的导入文件）
                        val file = File(uri.path ?: continue)
                        if (file.exists() && file.delete()) deletedCount++
                    }
                    else -> {
                        // content:// URI：通过 ContentResolver 删除
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) deletedCount++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed", e)
            }
        }
        deletedCount
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
                val fullNewName = sanitizeFileName(newName)

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
    private fun renameFileViaContentProvider(uri: Uri, fullNewName: String): Result<Unit> {
        // 路径 2a：MediaStore API（适用于 content://media/... URI）
        try {
            val rows = context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fullNewName)
                },
                null, null
            )
            if (rows > 0) return Result.success(Unit)
        } catch (e: SecurityException) {
            // MediaStore 无权限，尝试 DocumentsContract
        }

        // 路径 2b：DocumentsContract API（适用于 content://com.android.externalstorage.documents/... URI）
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.renameDocument(context.contentResolver, uri, fullNewName)
                Result.success(Unit)
            } else {
                Result.failure(IOException("不支持的文件类型，无法重命名"))
            }
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
     * 校验文件名合法性
     * @throws IllegalArgumentException 文件名不合法时抛出
     */
    private fun validateFileName(fileName: String): Result<String> {
        val nameWithoutExt = fileName.removeSuffix(".md").removeSuffix(".MD")

        // 空文件名
        if (nameWithoutExt.isBlank()) {
            return Result.failure(IllegalArgumentException("文件名不能为空"))
        }

        // 文件名长度限制（255 字节是大多数文件系统的限制）
        if (fileName.toByteArray(Charsets.UTF_8).size > 255) {
            return Result.failure(IllegalArgumentException("文件名过长（不能超过 255 字节）"))
        }

        // 非法字符检查（Windows / Linux 文件系统保留字符）
        val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val foundInvalid = nameWithoutExt.firstOrNull { it in invalidChars }
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

            // 验证文件类型是否受支持
            val fileType = FileType.resolve(fileName)
                ?: return@withContext null

            // 根据文件类型确定 MIME 类型
            val mimeType = if (fileType.isMarkdown) "text/markdown" else "text/plain"

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

            destUri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Operation failed", e)
            null
        }
    }

    /**
     * 从 URI 获取文件名
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
        return uriString.substringAfterLast("/")
    }
}
