package com.markflow.editor.ui.screens.filelist

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.data.repository.FileRepository
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.PendingFileOperation
import com.markflow.editor.domain.model.SecurityConsentRequiredException
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.domain.util.FileSorter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 文件列表加载超时（毫秒）。MediaStore 查询异常卡死/极慢未返回时，
 * [loadFiles]/[silentRefresh] 通过 withTimeoutOrNull 兜底释放加载态，
 * 避免「没有文件」场景下加载转圈永远不消失。正常扫描秒级返回，远低于该值。
 */
private const val LOAD_TIMEOUT_MILLIS = 15_000L

/**
 * 文件列表页 UI 状态
 */
data class FileListUiState(
    /** 最近打开文件（≤ RECENT_QUEUE_SIZE，按最近打开顺序、队首最新，不受 sortMode 影响） */
    val recentFiles: List<MarkdownFile> = emptyList(),
    /** Markdown 文件列表（排除最近打开；MediaStore 扫描 + 私有导入中的 .md） */
    val mdFiles: List<MarkdownFile> = emptyList(),
    /** 非 Markdown 文本文件列表（排除最近打开） */
    val otherFiles: List<MarkdownFile> = emptyList(),
    /** 合并后的文件列表（用于多选、搜索等操作） */
    val files: List<MarkdownFile> = emptyList(),
    /** 当前星标的文件 URI 集合（UI 据其显示星标图标） */
    val starredUris: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val sortMode: SortMode = SortMode.BY_TIME_DESC,
    /** 搜索关键词（过滤文件名） */
    val searchQuery: String = "",
    /** 是否处于多选模式 */
    val isSelectionMode: Boolean = false,
    /** 已选中的文件 URI 集合 */
    val selectedFiles: Set<String> = emptySet(),
    val showRenameDialog: Boolean = false,
    val renameTargetFile: MarkdownFile? = null,
    /** 重命名操作是否正在进行中 */
    val isRenaming: Boolean = false,
    /** 后缀变化确认框是否显示（方案2：可改后缀，变化时先弹确认） */
    val showRenameConfirmDialog: Boolean = false,
    /** 待确认的新文件名（后缀变化时暂存） */
    val pendingRenameName: String = "",
    val showDeleteConfirmDialog: Boolean = false,
    /** 删除时是否同时删除文件本身；false（默认）则仅从列表移除 */
    val deleteWithFile: Boolean = false,
    val showFileDetailsDialog: Boolean = false,
    val detailTargetFile: MarkdownFile? = null,
    val errorMessage: String? = null,
    val showNewFileDialog: Boolean = false,
    val pendingNewFileContent: String = "",
    /** 新建文件成功后的 URI，用于导航到编辑器 */
    val navigateToEditorUri: String? = null,
    /** Android 11+ 非本应用文件操作需要弹系统授权框 */
    val pendingSecurityRequest: PendingSecurityRequest? = null
)

/**
 * 待用户授权的安全请求。
 */
data class PendingSecurityRequest(
    val intentSender: IntentSender
)

/**
 * 文件列表页 ViewModel
 *
 * 交互逻辑：
 * - 单击文件 → 直接导航到编辑器
 * - 长按文件 → 进入多选模式
 * - 多选模式下：单选（1个）→ 删除/重命名/详细信息都可用；多选（2+）→ 仅删除可用
 */
@HiltViewModel
class FileListViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    /** 文件加载任务，确保同时只有一个在运行，避免竞态条件 */
    private var loadJob: Job? = null

    /** Android 11+ 非本应用文件操作授权通过后重试 */
    private var pendingSecurityOperation: PendingFileOperation? = null

    init {
        val savedSortMode = preferencesManager.getSortMode()
        _uiState.update { it.copy(sortMode = savedSortMode) }
        loadFiles()
        // 监听文件保存事件，后台无感刷新文件列表
        viewModelScope.launch {
            fileRepository.fileChangedEvent.collect {
                silentRefresh()
            }
        }
        // 监听文件打开事件，重排「最近打开」队列并刷新分区
        viewModelScope.launch {
            fileRepository.fileOpenedEvent.collect {
                silentRefresh()
            }
        }
    }

    // ==================== 文件加载 ====================

    fun refreshFiles() {
        loadFiles()
    }

    /**
     * 无感刷新：从编辑器返回时静默刷新文件列表，不显示加载指示器
     * 与 refreshFiles() 的区别：不设置 isLoading = true，避免 UI 闪烁
     */
    fun silentRefresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val sortMode = _uiState.value.sortMode
            try {
                val completed = withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
                    fileRepository.getFiles(sortMode)
                        .catch { e ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "加载文件失败: ${e.message}"
                                )
                            }
                        }
                        .collect { allFiles ->
                            // 私有目录扫描（filesDir）同步 I/O 放 IO 线程，避免阻塞主线程
                            val imported = withContext(Dispatchers.IO) {
                                fileRepository.getImportedFiles()
                            }
                            val pool = mergeByUri(allFiles + imported)
                            // 完成即复位 isLoading=false：若前一个 loadFiles 在被取消前
                            // 已将 isLoading 置 true 且未复位（CancellationException 重抛不写标志），
                            // 这里必须清零，否则空列表场景会永久转圈
                            partitionAndSet(sortMode, pool, updateLoading = true)
                        }
                }
                // 超时兜底：MediaStore 查询卡死/极慢未返回时释放加载态，避免空列表无限转圈
                if (completed == null) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "加载文件失败: ${e.message}")
                }
            }
        }
    }

    private fun loadFiles() {
        // 取消上一次未完成的加载任务，避免旧协程用过期数据覆盖新排序结果
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val sortMode = _uiState.value.sortMode
            try {
                val completed = withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
                    fileRepository.getFiles(sortMode)
                        .catch { e ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "加载文件失败: ${e.message}"
                                )
                            }
                        }
                        .collect { allFiles ->
                            // 私有目录扫描（filesDir）同步 I/O 放 IO 线程，避免阻塞主线程
                            val imported = withContext(Dispatchers.IO) {
                                fileRepository.getImportedFiles()
                            }
                            val pool = mergeByUri(allFiles + imported)
                            partitionAndSet(sortMode, pool, updateLoading = true)
                        }
                }
                // 超时兜底：MediaStore 查询卡死/极慢未返回时释放加载态，避免空列表无限转圈
                if (completed == null) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "加载文件失败: ${e.message}")
                }
            }
        }
    }

    /** 按 uri 去重合并（池 = MediaStore 扫描 + 私有导入） */
    private fun mergeByUri(list: List<MarkdownFile>): List<MarkdownFile> {
        val seen = mutableSetOf<String>()
        return list.filter { seen.add(it.uri) }
    }

    /**
     * 将合并池分区为 最近打开 / Markdown / 其他 三个互斥列表。
     * - 最近打开：按 recentUris 队列顺序解析池中仍存在且未隐藏的文件，不受 sortMode 影响；
     * - Markdown / 其他：去掉最近打开中的条目后，按 sortMode 排序按 isMarkdown 拆分。
     */
    private fun partitionAndSet(sortMode: SortMode, pool: List<MarkdownFile>, updateLoading: Boolean) {
        val recentUris = preferencesManager.getRecentUris()
        val recentUriSet = recentUris.toSet()
        val map = pool.associateBy { it.uri }

        val recentFiles = recentUris.mapNotNull { map[it] }
        // 剪掉已不存在的 recent 条目（删除/隐藏后残留），保持队列干净
        val resolvedUris = recentFiles.map { it.uri }.toSet()
        if (resolvedUris.size != recentUris.size) {
            preferencesManager.removeRecentUris(recentUris.filter { it !in resolvedUris })
        }

        val rest = pool.filter { it.uri !in recentUriSet }
        val starredUris = preferencesManager.getStarredUris()
        val md = FileSorter.sortWithStar(rest.filter { it.isMarkdown }, sortMode, starredUris)
        val other = FileSorter.sortWithStar(rest.filter { !it.isMarkdown }, sortMode, starredUris)

        _uiState.update {
            it.copy(
                sortMode = sortMode,
                recentFiles = recentFiles,
                mdFiles = md,
                otherFiles = other,
                files = recentFiles + md + other,
                starredUris = starredUris,
                isLoading = if (updateLoading) false else it.isLoading,
                errorMessage = null
            )
        }
    }

    // ==================== 排序切换 ====================

    fun toggleSortMode(mode: SortMode) {
        // 如果与当前排序模式相同，无需操作
        if (_uiState.value.sortMode == mode) return
        preferencesManager.setSortMode(mode)
        // 最近打开保持 recency 顺序，不受排序影响；仅重排 Markdown/其他 两区
        _uiState.update {
            val starredUris = preferencesManager.getStarredUris()
            val sortedMd = FileSorter.sortWithStar(it.mdFiles, mode, starredUris)
            val sortedOther = FileSorter.sortWithStar(it.otherFiles, mode, starredUris)
            it.copy(
                sortMode = mode,
                mdFiles = sortedMd,
                otherFiles = sortedOther,
                files = it.recentFiles + sortedMd + sortedOther
            )
        }
    }

    // ==================== 多选模式管理 ====================

    /** 长按进入多选模式，选中当前文件 */
    fun enterSelectionMode(fileUri: String) {
        _uiState.update {
            it.copy(isSelectionMode = true, selectedFiles = setOf(fileUri))
        }
    }

    /** 切换单个文件的选中状态 */
    fun toggleFileSelection(fileUri: String) {
        _uiState.update { state ->
            val newSelected = state.selectedFiles.toMutableSet()
            if (newSelected.contains(fileUri)) {
                newSelected.remove(fileUri)
            } else {
                newSelected.add(fileUri)
            }
            state.copy(selectedFiles = newSelected)
        }
    }

    /** 全选 / 取消全选（仅作用于当前搜索过滤后的可见文件） */
    fun toggleSelectAll() {
        _uiState.update { state ->
            val visibleFiles = filterFilesByQuery(state.files, state.searchQuery)
            val visibleUris = visibleFiles.map { it.uri }.toSet()
            val currentlySelectedVisible = state.selectedFiles.intersect(visibleUris)
            if (currentlySelectedVisible.size == visibleUris.size && visibleUris.isNotEmpty()) {
                // 当前可见文件已全部选中 → 取消选中这些可见文件（保留不可见文件的选中态）
                state.copy(selectedFiles = state.selectedFiles - visibleUris)
            } else {
                // 选中所有可见文件
                state.copy(selectedFiles = state.selectedFiles + visibleUris)
            }
        }
    }

    private fun filterFilesByQuery(files: List<MarkdownFile>, query: String): List<MarkdownFile> {
        return if (query.isBlank()) files
        else files.filter { it.fileName.contains(query, ignoreCase = true) }
    }

    /** 当前搜索过滤后的可见文件数。
     * 该数与 [toggleSelectAll] 的动作口径一致（都作用于过滤后可见子集），供多选
     * 头「全选/取消全选」标签比较使用——避免用全集造成"已全选可见仍显示全选"的标签/动作相反。 */
    fun visibleFileCount(): Int = filterFilesByQuery(_uiState.value.files, _uiState.value.searchQuery).size

    /** 退出多选模式 */
    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedFiles = emptySet()) }
    }

    // ==================== 文件操作 ====================

    /**
     * 切换指定文件的星标状态。打星/取消后立刻重排各分区（星标组置顶），
     * 并更新星标标记。最近打开分区顺序不受影响（仅标记）。
     */
    fun toggleStar(uri: String) {
        val starred = preferencesManager.getStarredUris()
        preferencesManager.setStarred(uri, uri !in starred)
        val sortMode = _uiState.value.sortMode
        val remainingStarred = preferencesManager.getStarredUris()

        _uiState.update { current ->
            val sortedMd = FileSorter.sortWithStar(current.mdFiles, sortMode, remainingStarred)
            val sortedOther = FileSorter.sortWithStar(current.otherFiles, sortMode, remainingStarred)
            current.copy(
                mdFiles = sortedMd,
                otherFiles = sortedOther,
                files = current.recentFiles + sortedMd + sortedOther,
                starredUris = remainingStarred
            )
        }
    }

    /**
     * 删除选中的文件。
     * - deleteWithFile = true：物理删除文件本身；
     * - deleteWithFile = false（默认）：仅从软件文件列表移除，文件保留在设备上。
     *
     * 删除成功后直接从内存列表移除，避免大量文件时全量重扫 MediaStore 导致卡顿。
     */
    fun deleteSelectedFiles(urisOverride: List<String>? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            val uris = urisOverride ?: state.selectedFiles.toList()
            val removedUris = try {
                if (state.deleteWithFile) {
                    fileRepository.deleteFiles(uris).also {
                        // 物理删除后清理隐藏记录，防止 URI 复用导致误隐藏
                        fileRepository.unhideFiles(it.toList())
                    }
                } else {
                    fileRepository.hideFiles(uris)
                    uris.toSet()
                }
            } catch (e: SecurityConsentRequiredException) {
                // 先落盘已成功删除的部分：从内存列表移除 + 清理隐藏记录 + 更新选中集，
                // 否则已删文件仍显示在列表中（UI 与磁盘状态不一致）
                if (e.partialSucceeded.isNotEmpty()) {
                    fileRepository.unhideFiles(e.partialSucceeded.toList())
                    // 已删除文件同步移出「最近打开」队列
                    preferencesManager.removeRecentUris(e.partialSucceeded)
                    // 已删除文件同步移出「星标」集合，防止残留失效 URI
                    preferencesManager.removeStarredUris(e.partialSucceeded)
                    _uiState.update { current ->
                        current.copy(
                            recentFiles = current.recentFiles.filterNot { it.uri in e.partialSucceeded },
                            mdFiles = current.mdFiles.filterNot { it.uri in e.partialSucceeded },
                            otherFiles = current.otherFiles.filterNot { it.uri in e.partialSucceeded },
                            files = current.files.filterNot { it.uri in e.partialSucceeded },
                            selectedFiles = current.selectedFiles - e.partialSucceeded
                        )
                    }
                }
                pendingSecurityOperation = e.pendingOperation
                _uiState.update {
                    it.copy(
                        pendingSecurityRequest = PendingSecurityRequest(e.intentSender),
                        showDeleteConfirmDialog = false
                    )
                }
                return@launch
            }

            // 删除/隐藏成功 → 同步移出「最近打开」队列，保证分区互斥
            if (removedUris.isNotEmpty()) {
                preferencesManager.removeRecentUris(removedUris)
                // 已删除/隐藏文件同步移出「星标」集合
                preferencesManager.removeStarredUris(removedUris)
            }

            _uiState.update { current ->
                // 授权重试路径（urisOverride != null）仅移除本次删除项，保留其余选中状态；
                // 正常路径（用户主动删除当前选中集）退出多选
                val remainingSelected = current.selectedFiles - removedUris
                val isRetry = urisOverride != null
                current.copy(
                    recentFiles = current.recentFiles.filterNot { it.uri in removedUris },
                    mdFiles = current.mdFiles.filterNot { it.uri in removedUris },
                    otherFiles = current.otherFiles.filterNot { it.uri in removedUris },
                    files = current.files.filterNot { it.uri in removedUris },
                    isSelectionMode = isRetry && remainingSelected.isNotEmpty(),
                    selectedFiles = if (isRetry) remainingSelected else emptySet(),
                    showDeleteConfirmDialog = false,
                    deleteWithFile = false
                )
            }
        }
    }

    /** 切换"删除时同时删除对应文件"勾选项 */
    fun setDeleteWithFile(checked: Boolean) {
        _uiState.update { it.copy(deleteWithFile = checked) }
    }

    fun showDeleteConfirm() {
        // 每次打开对话框默认不勾选"同时删除对应文件"
        _uiState.update { it.copy(showDeleteConfirmDialog = true, deleteWithFile = false) }
    }

    fun dismissDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirmDialog = false, deleteWithFile = false) }
    }

    fun showRenameDialog(file: MarkdownFile) {
        _uiState.update { it.copy(showRenameDialog = true, renameTargetFile = file) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false, renameTargetFile = null) }
    }

    fun renameFile(newName: String) {
        val target = _uiState.value.renameTargetFile ?: return
        // 防止重复提交
        if (_uiState.value.isRenaming) return

        // 方案2：允许修改后缀；后缀变化（忽略大小写）时先弹确认框
        val originalExt = target.fileName.substringAfterLast('.', "")
        val newExt = newName.substringAfterLast('.', "")
        val extChanged = !originalExt.equals(newExt, ignoreCase = true)

        if (extChanged) {
            _uiState.update { it.copy(showRenameConfirmDialog = true, pendingRenameName = newName) }
        } else {
            doRename(newName)
        }
    }

    fun confirmRename() {
        val pending = _uiState.value.pendingRenameName
        _uiState.update { it.copy(showRenameConfirmDialog = false, pendingRenameName = "") }
        if (pending.isNotEmpty()) doRename(pending)
    }

    /**
     * 推导重命名成功后的 uri：file:// 路径重命名会改变 uri（StorageRepository.renameFileOnDisk 用
     * `File(parentDir, fullNewName)` + renameTo），需按新文件名在旧父目录下重建，与列表 key
     * （`Uri.fromFile(file)`）对齐；content:// 原地 update DISPLAY_NAME，uri 不变，直接返回原 uri。
     * @param newName 必须为 `renameFile` 落盘的最终文件名（内部已 `trim()`，故调用方须传 `newName.trim()`）
     */
    private fun resolveRenamedUri(oldUri: String, newName: String): String {
        return try {
            val uri = Uri.parse(oldUri)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val oldFile = File(uri.path ?: return oldUri)
                val parent = oldFile.parentFile ?: return oldUri
                Uri.fromFile(File(parent, newName)).toString()
            } else {
                oldUri
            }
        } catch (_: Exception) {
            oldUri
        }
    }

    fun dismissRenameConfirm() {
        _uiState.update { it.copy(showRenameConfirmDialog = false, pendingRenameName = "") }
    }

    private fun doRename(newName: String, targetUri: String? = null) {
        val target = _uiState.value.renameTargetFile
        val uri = targetUri ?: target?.uri ?: return
        if (_uiState.value.isRenaming) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRenaming = true) }
            val result = fileRepository.renameFile(uri, newName)
            result.fold(
                onSuccess = {
                    // 重命名成功后迁移星标/最近打开 key：file:// 路径重命名会改变 uri，
                    // 需把旧 uri 的星标/最近项搬移到新 uri；content:// 原地 update 不改 uri，传同名即 no-op。
                    val newUri = resolveRenamedUri(uri, newName.trim())
                    preferencesManager.migrateUri(uri, newUri)
                    _uiState.update {
                        it.copy(
                            isRenaming = false,
                            showRenameDialog = false,
                            renameTargetFile = null,
                            isSelectionMode = false,
                            selectedFiles = emptySet()
                        )
                    }
                    loadFiles()
                },
                onFailure = { e ->
                    if (e is SecurityConsentRequiredException) {
                        pendingSecurityOperation = e.pendingOperation
                        _uiState.update {
                            it.copy(
                                isRenaming = false,
                                pendingSecurityRequest = PendingSecurityRequest(e.intentSender)
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isRenaming = false,
                                showRenameDialog = false,
                                renameTargetFile = null,
                                errorMessage = e.message ?: "重命名失败"
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * 用户响应系统文件授权对话框后的回调。
     * granted = true 时重试之前被拦截的重命名/删除操作。
     */
    fun onSecurityConsentResult(granted: Boolean) {
        val operation = pendingSecurityOperation
        pendingSecurityOperation = null
        _uiState.update { it.copy(pendingSecurityRequest = null) }
        if (!granted || operation == null) return
        when (operation) {
            is PendingFileOperation.Rename -> doRename(operation.newName, operation.uri)
            is PendingFileOperation.Delete -> deleteSelectedFiles(operation.uris)
        }
    }

    /** 主动取消待授权请求 */
    fun dismissSecurityRequest() {
        pendingSecurityOperation = null
        _uiState.update { it.copy(pendingSecurityRequest = null) }
    }

    fun showFileDetails(file: MarkdownFile) {
        _uiState.update { it.copy(showFileDetailsDialog = true, detailTargetFile = file) }
    }

    fun dismissFileDetails() {
        _uiState.update { it.copy(showFileDetailsDialog = false, detailTargetFile = null) }
    }

    /**
     * 创建新文件（异步）
     * 创建成功后自动设置 navigateToEditorUri 触发导航
     */
    fun createNewFile(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(showNewFileDialog = false) }
            val uri = fileRepository.createFile(fileName)
            if (uri != null) {
                loadFiles()
                _uiState.update { it.copy(navigateToEditorUri = uri) }
            } else {
                _uiState.update { it.copy(errorMessage = "创建文件失败") }
            }
        }
    }

    /** 消费导航事件（导航后重置，避免重复跳转） */
    fun onNavigateToEditorHandled() {
        _uiState.update { it.copy(navigateToEditorUri = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ==================== 搜索 ====================

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    // ==================== 导入文件 ====================

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val newUri = fileRepository.importFile(uri)
            if (newUri != null) {
                loadFiles()
                _uiState.update { it.copy(navigateToEditorUri = newUri) }
            } else {
                val fileName = fileRepository.getFileName(uri.toString()) ?: "未知文件"
                val ext = fileName.substringAfterLast('.', "?")
                _uiState.update {
                    it.copy(errorMessage = "不支持的文件类型：.$ext（$fileName）")
                }
            }
        }
    }
}