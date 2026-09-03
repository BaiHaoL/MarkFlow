package com.markflow.editor.data.repository

import android.content.Context
import android.net.Uri
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.util.RandomSeekReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件仓库（统一门面）
 * 封装 StorageRepository，提供统一的文件操作接口
 * 作为 ViewModel ↔ 存储层之间的桥梁
 */
@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val preferencesManager: PreferencesManager
) {
    /** 文件变更通知流，文件保存后 emit，供 FileListViewModel 后台无感刷新 */
    private val _fileChangedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fileChangedEvent: SharedFlow<Unit> = _fileChangedEvent.asSharedFlow()

    /** 通知文件列表有变更（由 EditorViewModel 在保存成功后调用） */
    fun notifyFileChanged() {
        _fileChangedEvent.tryEmit(Unit)
    }

    /**
     * 获取所有 Markdown 文件列表（过滤已隐藏的文件）
     */
    fun getFiles(sortMode: SortMode = SortMode.BY_TIME_DESC): Flow<List<MarkdownFile>> {
        return storageRepository.scanMarkdownFiles(sortMode)
            .map { files -> files.filter { it.uri !in preferencesManager.getHiddenUris() } }
    }

    /**
     * 获取导入的 Markdown 文件列表（跨应用打开的文件，过滤已隐藏的文件）
     */
    fun getImportedFiles(): List<MarkdownFile> {
        val hiddenUris = preferencesManager.getHiddenUris()
        return storageRepository.scanImportedFiles().filter { it.uri !in hiddenUris }
    }

    /**
     * 读取文件内容（自动探测编码，或按指定编码解码）
     */
    suspend fun readContent(uri: String, charsetOverride: String? = null): String? {
        return storageRepository.readFileContent(uri, charsetOverride)
    }

    /**
     * 探测文件编码，返回字符集名；失败返回 null
     */
    fun detectEncodingName(uri: String): String? {
        return storageRepository.detectEncodingName(uri)
    }

    /**
     * 保存文件内容
     */
    suspend fun saveContent(uri: String, content: String): Boolean {
        return storageRepository.writeFileContent(uri, content)
    }

    /**
     * 创建新文件
     */
    suspend fun createFile(fileName: String, content: String = ""): String? {
        return storageRepository.createFile(fileName, content)
    }

    /**
     * 删除文件，返回成功删除的 URI 集合。
     */
    suspend fun deleteFiles(uris: List<String>): Set<String> {
        return storageRepository.deleteFiles(uris)
    }

    /**
     * 仅从软件文件列表移除文件（不删除文件本身）
     */
    suspend fun hideFiles(uris: List<String>): Boolean {
        return preferencesManager.addHiddenUris(uris)
    }

    /**
     * 将文件从隐藏集合移除（物理删除后调用，避免 URI 复用导致误隐藏）
     */
    suspend fun unhideFiles(uris: List<String>): Boolean {
        return preferencesManager.removeHiddenUris(uris)
    }

    /**
     * 重命名文件
     */
    suspend fun renameFile(uri: String, newName: String): Result<Unit> {
        return storageRepository.renameFile(uri, newName)
    }

    /**
     * 获取文件名
     */
    fun getFileName(uri: String): String? {
        return storageRepository.getFileName(uri)
    }

    /**
     * 获取文件大小（字节）
     */
    fun getFileSize(uri: String): Long? {
        return storageRepository.getFileSize(uri)
    }

    /**
     * 打开文件输入流（供分页只读阅读器按需读取）
     */
    fun openInputStream(uri: String): java.io.InputStream? {
        return storageRepository.openInputStream(uri)
    }

    /**
     * 打开支持随机 seek 读取的文件源（大 txt O(1) 跳块），使用方负责 close()
     */
    fun openSeekable(uri: String): RandomSeekReader? {
        return storageRepository.openSeekable(uri)
    }

    /**
     * 打开 file:// 的可写句柄（分段编辑随机写回），使用方负责 close()
     */
    fun openFileWriter(uri: String): java.io.RandomAccessFile? {
        return storageRepository.openFileWriter(uri)
    }

    /**
     * 用临时文件整体覆盖目标文件（content:// 写回路径）
     */
    suspend fun overwriteFileFromTemp(uri: String, tempFile: java.io.File): Boolean {
        return storageRepository.overwriteFileFromTemp(uri, tempFile)
    }

    /**
     * 将图片压缩写入 Markdown 文件同级 images/ 目录，返回 Markdown 相对路径；失败返回 null
     */
    suspend fun copyImageForMarkdown(imageUri: String, mdFileUri: String): String? {
        return storageRepository.copyImageForMarkdown(imageUri, mdFileUri)
    }

    /**
     * 导入外部文件到应用内部存储
     */
    suspend fun importFile(uri: Uri): String? {
        return storageRepository.importFile(uri)
    }
}