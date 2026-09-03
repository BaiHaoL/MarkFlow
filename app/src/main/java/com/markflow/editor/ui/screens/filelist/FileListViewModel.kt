package com.markflow.editor.ui.screens.filelist

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.data.repository.FileRepository
import com.markflow.editor.domain.model.FileSource
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.PendingFileOperation
import com.markflow.editor.domain.model.SecurityConsentRequiredException
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.domain.util.FileSorter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 文件列表页 UI 状态
 */
data class FileListUiState(
    /** Markdown 文件列表（应用内创建或设备存储中的 .md 文件） */
    val localFiles: List<MarkdownFile> = emptyList(),
    /** 非 Markdown 文本文件列表（如 .py, .sh, .json 等） */
    val otherFiles: List<MarkdownFile> = emptyList(),
    /** 导入文件列表（跨应用打开的文件） */
    val importedFiles: List<MarkdownFile> = emptyList(),
    /** 合并后的文件列表（用于多选、搜索等操作） */
    val files: List<MarkdownFile> = emptyList(),
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
            val sortedImported = FileSorter.sort(fileRepository.getImportedFiles(), sortMode)
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
                    val mdFiles = allFiles.filter { it.isMarkdown }
                    val nonMdFiles = allFiles.filter { !it.isMarkdown }
                    _uiState.update {
                        it.copy(
                            sortMode = sortMode,
                            localFiles = mdFiles,
                            otherFiles = nonMdFiles,
                            importedFiles = sortedImported,
                            files = sortedImported + mdFiles + nonMdFiles,
                            isLoading = false,
                            errorMessage = null
                        )
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
            // 导入文件本地扫描，需按当前 sortMode 排序
            val sortedImported = FileSorter.sort(fileRepository.getImportedFiles(), sortMode)
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
                    val mdFiles = allFiles.filter { it.isMarkdown }
                    val nonMdFiles = allFiles.filter { !it.isMarkdown }
                    _uiState.update {
                        it.copy(
                            sortMode = sortMode,
                            localFiles = mdFiles,
                            otherFiles = nonMdFiles,
                            importedFiles = sortedImported,
                            files = sortedImported + mdFiles + nonMdFiles,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
        }
    }

    // ==================== 排序切换 ====================

    fun toggleSortMode(mode: SortMode) {
        // 如果与当前排序模式相同，无需操作
        if (_uiState.value.sortMode == mode) return
        preferencesManager.setSortMode(mode)
        // 对内存中的两个分区重新排序，排序切换不需要重新扫描文件系统
        _uiState.update {
            val sortedImported = FileSorter.sort(it.importedFiles, mode)
            val sortedLocal = FileSorter.sort(it.localFiles, mode)
            val sortedOther = FileSorter.sort(it.otherFiles, mode)
            it.copy(
                sortMode = mode,
                importedFiles = sortedImported,
                localFiles = sortedLocal,
                otherFiles = sortedOther,
                files = sortedImported + sortedLocal + sortedOther
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

    /** 退出多选模式 */
    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedFiles = emptySet()) }
    }

    // ==================== 文件操作 ====================

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
                    _uiState.update { current ->
                        current.copy(
                            importedFiles = current.importedFiles.filterNot { it.uri in e.partialSucceeded },
                            localFiles = current.localFiles.filterNot { it.uri in e.partialSucceeded },
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

            _uiState.update { current ->
                // 授权重试路径（urisOverride != null）仅移除本次删除项，保留其余选中状态；
                // 正常路径（用户主动删除当前选中集）退出多选
                val remainingSelected = current.selectedFiles - removedUris
                val isRetry = urisOverride != null
                current.copy(
                    importedFiles = current.importedFiles.filterNot { it.uri in removedUris },
                    localFiles = current.localFiles.filterNot { it.uri in removedUris },
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