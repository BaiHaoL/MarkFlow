package com.markflow.editor.ui.screens.filelist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.data.repository.FileRepository
import com.markflow.editor.domain.model.FileSource
import com.markflow.editor.domain.model.MarkdownFile
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
    val showDeleteConfirmDialog: Boolean = false,
    /** 删除时是否同时删除文件本身；false（默认）则仅从列表移除 */
    val deleteWithFile: Boolean = false,
    val showFileDetailsDialog: Boolean = false,
    val detailTargetFile: MarkdownFile? = null,
    val errorMessage: String? = null,
    val showNewFileDialog: Boolean = false,
    val pendingNewFileContent: String = "",
    /** 新建文件成功后的 URI，用于导航到编辑器 */
    val navigateToEditorUri: String? = null
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

    /** 全选 / 取消全选 */
    fun toggleSelectAll() {
        _uiState.update { state ->
            if (state.selectedFiles.size == state.files.size) {
                state.copy(selectedFiles = emptySet())
            } else {
                state.copy(selectedFiles = state.files.map { it.uri }.toSet())
            }
        }
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
     */
    fun deleteSelectedFiles() {
        viewModelScope.launch {
            val uris = _uiState.value.selectedFiles.toList()
            if (_uiState.value.deleteWithFile) {
                fileRepository.deleteFiles(uris)
                // 物理删除后清理隐藏记录，防止 URI 复用导致误隐藏
                fileRepository.unhideFiles(uris)
            } else {
                fileRepository.hideFiles(uris)
            }
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFiles = emptySet(),
                    showDeleteConfirmDialog = false,
                    deleteWithFile = false
                )
            }
            loadFiles()
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

        // 非 Markdown 文件若新名丢掉了扩展名，自动补回原后缀，避免重命名改坏后缀
        val originalExt = target.fileName.substringAfterLast('.', "")
        val effectiveName = if (!target.isMarkdown && newName.lastIndexOf('.') <= 0 && originalExt.isNotEmpty()) {
            "$newName.$originalExt"
        } else {
            newName
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRenaming = true) }
            val result = fileRepository.renameFile(target.uri, effectiveName)
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
                    _uiState.update {
                        it.copy(
                            isRenaming = false,
                            showRenameDialog = false,
                            renameTargetFile = null,
                            errorMessage = e.message ?: "重命名失败"
                        )
                    }
                }
            )
        }
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