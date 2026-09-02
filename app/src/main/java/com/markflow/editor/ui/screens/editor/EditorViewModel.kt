package com.markflow.editor.ui.screens.editor

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.data.repository.FileRepository
import com.markflow.editor.domain.model.EditorMode
import com.markflow.editor.domain.model.FileType
import com.markflow.editor.domain.model.FileType.Companion.LARGE_FILE_THRESHOLD_BYTES

import com.markflow.editor.domain.util.UndoRedoManager
import com.markflow.editor.util.PagedTextSource
import com.markflow.editor.util.TocParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import javax.inject.Inject

/**
 * 编辑器 UI 状态
 */
data class EditorUiState(
    val fileUri: String = "",
    val fileName: String = "",
    val originalContent: String = "",
    val currentContent: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val editorMode: EditorMode = EditorMode.EDIT,
    val isImmersiveMode: Boolean = false,
    val showSearchBar: Boolean = false,
    val searchQuery: String = "",
    val searchMatchPositions: List<Int> = emptyList(),
    val currentSearchIndex: Int = -1,
    /** 当前匹配项的上下文预览（前后各 ~30 字符） */
    val searchContext: String = "",
    val showExitConfirmDialog: Boolean = false,
    val errorMessage: String? = null,
    val saveStatus: String = "",
    // 目录大纲
    val showToc: Boolean = false,
    val tocEntries: List<TocParser.TocEntry> = emptyList(),
    // 图片全屏
    val fullscreenImageUrl: String? = null,
    // 撤回/反撤回
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 撤回/反撤回后待应用的光标位置（非 null 时由 EditorScreen 应用后清除） */
    val pendingCursorPos: Int? = null,
    /** 最近已知的光标位置（由 EditorScreen 在每次 onValueChange 时更新） */
    val lastCursorPos: Int = 0,
    // 文件类型
    /** 是否为 Markdown 文件 */
    val isMarkdown: Boolean = true,
    /** 语法高亮名称（空字符串 = 无语法高亮，仅纯文本编辑） */
    val grammarName: String = "markdown",
    /** 是否为 .txt 文件（决定是否有纯文本预览切换） */
    val isTxt: Boolean = false,
    /** 当前文件的文本编码名（如 UTF-8 / GBK），供展示与手动切换；空 = 未识别 */
    val encodingName: String = "",
    // 大 TXT 只读分页阅读
    /** 是否为超大 .txt 文件（>0.5MB），走只读分页阅读，不全文载入 */
    val isReadOnlyPaged: Boolean = false,
    /** 已加载的分页内容缓存：块索引 → 块文本 */
    val pagedPages: Map<Int, String> = emptyMap(),
    /** 正在加载中的块索引集合，避免重复请求 */
    val pagedLoading: Set<Int> = emptySet(),
    /** 已确认的文件末尾块索引；null = 尚未确认。其后所有块视为已到底部 */
    val pagedEofAt: Int? = null,
    // 分段编辑（大 txt 点段编辑）
    /** 正在编辑的块索引；null = 未在编辑 */
    val pagedEditingIndex: Int? = null,
    /** 编辑框当前文本 */
    val pagedEditingText: String = "",
    /** 编辑框当前选区（驱动 BasicTextField，支持框选与光标定位） */
    val pagedEditSelection: TextRange = TextRange(0),
    /** 输入法组合区间（未确认的候选笔画/拼音）；重建 value 时保留以免手写/拼音被打断 */
    val pagedEditComposition: TextRange? = null,
    /** 编辑内容是否有改动（决定保存是否真正写回） */
    val pagedDirty: Boolean = false,
    /** 保存写回中 */
    val isPagedSaving: Boolean = false,
    /** 分段编辑（仅当前段）是否可撤回 */
    val pagedCanUndo: Boolean = false,
    /** 分段编辑（仅当前段）是否可反撤回 */
    val pagedCanRedo: Boolean = false,
    /** 待滚动跳转的全局字节偏移（恢复上次位置），UI 消费后清除 */
    val pagedJumpOffset: Long? = null
)

/**
 * 编辑器 ViewModel
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // ==================== 撤回/反撤回历史 ====================
    private val undoRedoManager = UndoRedoManager()
    private var isUndoRedoOp = false
    private var undoDebounceJob: Job? = null

    // ==================== 自动保存 ====================
    /** 3 秒防抖：停止输入 3 秒后自动保存，与撤销防抖（500ms）独立 */
    private var autoSaveJob: Job? = null

    // ==================== 大 TXT 只读分页阅读 ====================
    private var pagedReader: PagedTextSource? = null

    // ==================== 大 txt 阅读位置记录 / 书签 ====================
    /** 最近一次记录到磁盘的阅读位置（全局字节偏移锚点），退出编辑器时兜底写一次 */
    private var lastPagedScrollByteOffset: Long? = null
    /**
     * 最近一次"当前可视首个块索引"，在主线程同步赋值、不受协程取消影响。
     * 书签添加与退出落盘都基于它实时解析偏移，避免被滚动防抖协程竞态丢到顶部。
     */
    private var lastPagedScrollBlockIndex: Int = -1
    /** 阅读位置防抖保存任务：滚动中不频繁写盘 */
    private var scrollSaveJob: Job? = null

    // ==================== 分段编辑段级撤回（仅当前编辑会话有效） ====================
    /** 复用 md 编辑器的撤回管理器："会话合并"使连续输入作为一次原子操作整体撤回 */
    private val pagedUndoManager = UndoRedoManager()
    /** 会话合并防抖任务：超时后结束当前编辑会话（下一次按键开启新会话） */
    private var pagedSessionJob: Job? = null

    // ==================== 编码 ====================
    /** 手动指定的编码（null = 自动探测）；未手动指定或切文件时重置 */
    private var encodingOverride: Charset? = null

    companion object {
        /** 分页内容内存缓存上限（UTF-16 字符数，≈20MB），超出按 LRU 逐出最旧块，保证内存有界 */
        private const val MAX_CACHED_CHARS = 10_000_000

        /** 初始预加载块数 */
        private const val PRELOAD_CHUNKS = 3

        /** 阅前预取相邻块数（每块约 10KB，±2 块覆盖"上/下 15KB"预取目标） */
        private const val PREFETCH_CHUNKS = 2

        /** 阅读位置防抖写盘间隔（毫秒）：滚动停止该时长后才真正落盘 */
        private const val SCROLL_SAVE_DEBOUNCE_MS = 400L
    }

    // ==================== 文件加载 ====================

    fun loadFile(fileUri: String, resetEncoding: Boolean = true) {
        if (resetEncoding) {
            encodingOverride = null
        }
        val fileName = fileRepository.getFileName(fileUri) ?: "未命名"
        val fileType = FileType.resolve(fileName)
        val isMarkdown = fileType?.isMarkdown ?: false
        val grammarName = fileType?.grammarName ?: ""
        // 大 TXT 判定：扩展名为 .txt 且大小超过 0.5MB → 走只读分页阅读
        val isTxt = fileName.substringAfterLast('.', "").equals("txt", ignoreCase = true)
        val fileSize = fileRepository.getFileSize(fileUri) ?: 0L
        val isLargeTxt = isTxt && fileSize > LARGE_FILE_THRESHOLD_BYTES
        _uiState.update {
            it.copy(
                fileUri = fileUri,
                isLoading = true,
                fileName = fileName,
                isMarkdown = isMarkdown,
                grammarName = grammarName,
                isTxt = isTxt,
                encodingName = encodingOverride?.name() ?: "",
                isReadOnlyPaged = isLargeTxt,
                editorMode = if (isLargeTxt) EditorMode.PREVIEW else EditorMode.EDIT,
                currentContent = if (isLargeTxt) "" else it.currentContent,
                originalContent = if (isLargeTxt) "" else it.originalContent,
                hasUnsavedChanges = false,
                pagedEofAt = null,
                pagedPages = emptyMap(),
                pagedLoading = emptySet(),
                pagedEditSelection = TextRange(0),
                pagedEditComposition = null
            )
        }
        if (isLargeTxt) {
            // 恢复上次阅读位置（全局字节偏移锚点）
            val savedPos = preferencesManager.getPagedReadPosition(fileUri)
            _uiState.update {
                it.copy(pagedJumpOffset = savedPos)
            }
            loadPaged(fileUri, encodingOverride)
            return
        }
        viewModelScope.launch {
            try {
                // 文件读取在 IO 线程；手动指定编码（switchEncoding）时按指定解码
                val content = withContext(Dispatchers.IO) {
                    fileRepository.readContent(fileUri, encodingOverride?.name()) ?: ""
                }
                // 展示当前编码：优先手动指定，否则自动探测
                val encName = encodingOverride?.name()
                    ?: withContext(Dispatchers.IO) { fileRepository.detectEncodingName(fileUri) ?: "" }
                // TOC 解析在 Default 线程（CPU 密集型）
                val tocEntries = withContext(Dispatchers.Default) {
                    TocParser.parse(content)
                }
                // 清空撤回/反撤回历史
                undoRedoManager.reset()
                _uiState.update {
                    it.copy(
                        originalContent = content,
                        currentContent = content,
                        encodingName = encName,
                        isLoading = false,
                        hasUnsavedChanges = false,
                        tocEntries = tocEntries,
                        canUndo = false,
                        canRedo = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "无法加载文件: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 重建大 txt 分块内核。[preserveUntil] 非 null 表示来自"保存写回"场景：该索引之前的
     * 块未受写回影响，保留其缓存以避免阅读区整体抖落；该索引及之后的块重新加载。
     */
    private fun loadPaged(
        fileUri: String,
        charsetOverride: Charset?,
        preserveUntil: Int? = null
    ) {
        val source = PagedTextSource(
            open = { fileRepository.openSeekable(fileUri) },
            charsetOverride = charsetOverride
        )
        pagedReader = source
        clearPagedHistory()
        viewModelScope.launch {
            val encName = try {
                source.detectedCharsetName() ?: ""
            } catch (_: Exception) {
                ""
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    pagedEofAt = null,
                    // 保存写回场景保留受影响索引之前的块缓存（内容未变可安全复用）
                    pagedPages = if (preserveUntil != null) {
                        it.pagedPages.filterKeys { k -> k < preserveUntil }
                    } else {
                        emptyMap()
                    },
                    pagedLoading = if (preserveUntil != null) {
                        it.pagedLoading.filter { k -> k < preserveUntil }.toSet()
                    } else {
                        emptySet()
                    },
                    pagedEditingIndex = null,
                    pagedEditingText = "",
                    pagedEditSelection = TextRange(0),
                    pagedDirty = false,
                    isPagedSaving = false,
                    hasUnsavedChanges = false,
                    pagedCanUndo = false,
                    pagedCanRedo = false,
                    encodingName = encName,
                    saveStatus = "大文件"
                )
            }
            if (preserveUntil != null) {
                // 聚焦受影响区起始：重载保存块及后续几个块，使可视区尽快恢复为真实内容
                val start = preserveUntil.coerceAtLeast(0)
                for (i in start until (preserveUntil + PREFETCH_CHUNKS + 1)) {
                    ensurePagedPage(i)
                }
            } else {
                for (i in 0 until PRELOAD_CHUNKS) {
                    ensurePagedPage(i)
                }
            }
        }
    }

    /**
     * 确保 [index] 块已进入缓存（字节预算 LRU，超上限逐出最旧块），
     * 并预取上/下相邻块（约各 15KB）以消除滚动顿挫；已在缓存或加载中则跳过。
     */
    fun ensurePagedPage(index: Int) {
        val state = _uiState.value
        if (!state.isReadOnlyPaged || index < 0) return
        if (state.pagedEofAt != null && index >= state.pagedEofAt) return
        if (state.pagedPages.containsKey(index) || state.pagedLoading.contains(index)) return
        val reader = pagedReader ?: return
        _uiState.update { it.copy(pagedLoading = it.pagedLoading + index) }
        viewModelScope.launch {
            val chunk = try {
                reader.getChunk(index)
            } catch (_: Exception) {
                null
            }
            if (chunk == null) {
                // 读取失败或到达 EOF：记录文件末尾位置（取最小越界块）
                val cur = _uiState.value
                val eofAt = (cur.pagedEofAt ?: Int.MAX_VALUE).coerceAtMost(index)
                _uiState.update {
                    it.copy(
                        pagedLoading = it.pagedLoading - index,
                        pagedEofAt = eofAt
                    )
                }
                return@launch
            }
            val newCache = LinkedHashMap(_uiState.value.pagedPages)
            newCache[index] = chunk.text
            // 字节预算 LRU 逐出：从头（最旧）累计字符数，超过预算即移除
            var totalChars = 0
            val entryIterator = newCache.entries.iterator()
            while (entryIterator.hasNext()) {
                val entry = entryIterator.next()
                if (totalChars + entry.value.length > MAX_CACHED_CHARS) {
                    entryIterator.remove()
                } else {
                    totalChars += entry.value.length
                }
            }
            _uiState.update {
                it.copy(
                    pagedPages = newCache,
                    pagedLoading = it.pagedLoading - index
                )
            }
            // 预取上/下相邻块（约 15KB）
            for (delta in -PREFETCH_CHUNKS..PREFETCH_CHUNKS) {
                if (delta != 0) ensurePagedPage(index + delta)
            }
        }
    }

    // ==================== 分段编辑（大 txt） ====================

    /**
     * 进入 [index] 块的单页编辑：仅该块载入编辑框，其余保持只读浏览。
     * 若当前已编辑另一段且有改动（dirty），则先自动保存上一段，再进入新段。
     *
     * @param index 目标块索引
     * @param cursor 进入编辑时光标的初始位置（由 UI 长按位置换算，避免跳段首）；缺省为文本末尾
     */
    fun startPagedEdit(index: Int, cursor: Int = 0) {
        val state = _uiState.value
        if (!state.isReadOnlyPaged) return
        val targetText = state.pagedPages[index] ?: return
        if (state.pagedEditingIndex == index) return
        clearPagedHistory()
        if (state.pagedEditingIndex != null && state.pagedDirty) {
            // 上一段有未保存改动：先保存，成功后重建索引再进入新段
            saveAndSwitchEdit(state.pagedEditingIndex, index, cursor)
            return
        }
        doStartEdit(index, targetText, cursor)
    }

    /** 直接进入某段编辑（无待保存的上一段） */
    private fun doStartEdit(index: Int, text: String, cursor: Int) {
        _uiState.update {
            it.copy(
                pagedEditingIndex = index,
                pagedEditingText = text,
                pagedEditSelection = TextRange(cursor.coerceIn(0, text.length)),
                pagedEditComposition = null,
                pagedDirty = false,
                hasUnsavedChanges = true,
                pagedCanUndo = false,
                pagedCanRedo = false,
                saveStatus = "编辑中"
            )
        }
    }

    /**
     * 保存 [oldIndex] 段的改动后重建块索引，再切换编排编辑 [newIndex] 段。
     * 写回复用 [persistPagedEdit]（与手动保存同一路径），成功后 loadPaged 重建
     * （保留 [newIndex] 之前的块缓存，避免阅读区整体抖落），随后进入新段编辑。
     */
    private fun saveAndSwitchEdit(oldIndex: Int, newIndex: Int, cursor: Int) {
        val reader = pagedReader ?: return
        val newText = _uiState.value.pagedEditingText
        val fileUri = _uiState.value.fileUri
        _uiState.update { it.copy(isPagedSaving = true, saveStatus = "切换中…") }
        viewModelScope.launch {
            val ok = try {
                persistPagedEdit(reader, oldIndex, newText)
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                _uiState.update {
                    it.copy(isPagedSaving = false, saveStatus = "保存失败", hasUnsavedChanges = true)
                }
                return@launch
            }
            fileRepository.notifyFileChanged()
            loadPaged(fileUri, encodingOverride, preserveUntil = newIndex + 1)
            var tries = 0
            while (tries < 200) {
                val s = _uiState.value
                if (s.isReadOnlyPaged && s.pagedPages.containsKey(newIndex)) break
                if (s.pagedEofAt != null && newIndex >= s.pagedEofAt) break
                ensurePagedPage(newIndex)
                delay(30)
                tries++
            }
            val s = _uiState.value
            if (s.isReadOnlyPaged && s.pagedPages.containsKey(newIndex)) {
                doStartEdit(newIndex, s.pagedPages[newIndex]!!, cursor)
            } else {
                _uiState.update {
                    it.copy(
                        isPagedSaving = false,
                        pagedEditingIndex = null,
                        pagedEditingText = "",
                        pagedEditSelection = TextRange(0),
                        pagedDirty = false,
                        hasUnsavedChanges = false,
                        saveStatus = "大文件",
                        errorMessage = "目标段落加载失败，请重试"
                    )
                }
            }
        }
    }

    /** 编辑框内容/选区/组合变化：文本变化才进入撤回会话；仅移动光标/框选只更新选区，不压栈 */
    fun updatePagedEditText(text: String, selection: TextRange, composition: TextRange?) {
        if (_uiState.value.pagedEditingIndex == null) return
        val st = _uiState.value
        val oldText = st.pagedEditingText
        val oldCursor = st.pagedEditSelection.start
        if (text == oldText && selection == st.pagedEditSelection && composition == st.pagedEditComposition) return
        if (text != oldText) {
            // 与 md 一致：会话开始时立即推入起始状态（canUndo 立即可用），
            // 会话内继续编辑不推入，作为一次原子操作整体撤回
            pagedUndoManager.onContentChanged(oldText, oldCursor)
            pagedSessionJob?.cancel()
            pagedSessionJob = viewModelScope.launch {
                delay(500)
                pagedUndoManager.endSession()
            }
        }
        _uiState.update {
            it.copy(
                pagedEditingText = text,
                pagedEditSelection = selection,
                pagedEditComposition = composition,
                pagedDirty = if (text != oldText) true else it.pagedDirty,
                hasUnsavedChanges = if (text != oldText) true else it.hasUnsavedChanges,
                pagedCanUndo = pagedUndoManager.canUndo,
                pagedCanRedo = pagedUndoManager.canRedo
            )
        }
    }

    /** 段级撤回：会话内连续输入作为一次操作整体撤回（仅影响正在编辑的这段） */
    fun pagedUndo() {
        if (_uiState.value.pagedEditingIndex == null) return
        pagedSessionJob?.cancel()
        val st = _uiState.value
        val result = pagedUndoManager.undo(st.pagedEditingText, st.pagedEditSelection.start)
        if (result == null) {
            _uiState.update { it.copy(pagedCanUndo = false, pagedCanRedo = pagedUndoManager.canRedo) }
            return
        }
        _uiState.update {
            it.copy(
                pagedEditingText = result.content,
                pagedEditSelection = TextRange(result.cursor.coerceIn(0, result.content.length)),
                pagedEditComposition = null,
                pagedDirty = true,
                hasUnsavedChanges = true,
                pagedCanUndo = pagedUndoManager.canUndo,
                pagedCanRedo = pagedUndoManager.canRedo
            )
        }
    }

    /** 段级反撤回 */
    fun pagedRedo() {
        if (_uiState.value.pagedEditingIndex == null) return
        pagedSessionJob?.cancel()
        val st = _uiState.value
        val result = pagedUndoManager.redo(st.pagedEditingText, st.pagedEditSelection.start)
        if (result == null) {
            _uiState.update { it.copy(pagedCanRedo = false, pagedCanUndo = pagedUndoManager.canUndo) }
            return
        }
        _uiState.update {
            it.copy(
                pagedEditingText = result.content,
                pagedEditSelection = TextRange(result.cursor.coerceIn(0, result.content.length)),
                pagedEditComposition = null,
                pagedDirty = true,
                hasUnsavedChanges = true,
                pagedCanUndo = pagedUndoManager.canUndo,
                pagedCanRedo = pagedUndoManager.canRedo
            )
        }
    }

    private fun clearPagedHistory() {
        pagedSessionJob?.cancel()
        pagedUndoManager.reset()
    }

    /** 取消编辑：丢弃本次改动，回到只读浏览 */
    fun cancelPagedEdit() {
        clearPagedHistory()
        _uiState.update {
            it.copy(
                pagedEditingIndex = null,
                pagedEditingText = "",
                pagedEditSelection = TextRange(0),
                pagedEditComposition = null,
                pagedDirty = false,
                isPagedSaving = false,
                hasUnsavedChanges = false,
                pagedCanUndo = false,
                pagedCanRedo = false,
                saveStatus = "大文件"
            )
        }
    }

    /** 保存当前编辑块：file:// 随机写 / content:// 整文件重写；成功后重建内核 */
    fun savePagedEdit() {
        val state = _uiState.value
        val index = state.pagedEditingIndex ?: return
        val reader = pagedReader ?: return
        if (!state.pagedDirty) {
            cancelPagedEdit()
            return
        }
        val newText = state.pagedEditingText
        _uiState.update { it.copy(isPagedSaving = true, saveStatus = "保存中") }
        viewModelScope.launch {
            val ok = try {
                persistPagedEdit(reader, index, newText)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                fileRepository.notifyFileChanged()
                // 重建时保留保存块之前的块缓存，避免阅读区整体抖落跳到别处
                loadPaged(state.fileUri, encodingOverride, preserveUntil = index + 1)
            } else {
                _uiState.update {
                    it.copy(
                        isPagedSaving = false,
                        saveStatus = "保存失败",
                        hasUnsavedChanges = true
                    )
                }
            }
        }
    }

    /**
     * 写回编辑后的块。
     * - file://：RandomAccessFile 随机写；块字节数不变时原地覆盖，
     *   变化时从脏块起始重写"新块 + 后续所有块"并截断，成本与脏块之后长度成正比；
     * - content://：Documents/MediaStore 不支持随机写，按方案接受整文件覆盖，
     *   先逐块生成临时文件（脏块替换），再整体写回。
     */
    private suspend fun persistPagedEdit(
        reader: PagedTextSource,
        index: Int,
        newText: String
    ): Boolean {
        val charset = reader.charset() ?: Charsets.UTF_8
        val newBytes = newText.toByteArray(charset)
        val uri = _uiState.value.fileUri
        val isFile = Uri.parse(uri).scheme == "file"
        return if (isFile) {
            persistFileRandomWrite(reader, index, newBytes, uri)
        } else {
            persistContentRewrite(reader, index, newBytes, uri)
        }
    }

    private suspend fun persistFileRandomWrite(
        reader: PagedTextSource,
        index: Int,
        newBytes: ByteArray,
        uri: String
    ): Boolean = withContext(Dispatchers.IO) {
        val start = reader.chunkOffsetOf(index) ?: return@withContext false
        val oldLen = reader.chunkByteLengthOf(index) ?: return@withContext false
        val raf = fileRepository.openFileWriter(uri) ?: return@withContext false
        try {
            if (newBytes.size == oldLen) {
                // 字节长度不变：原地覆盖，成本 O(单块)
                raf.seek(start)
                raf.write(newBytes)
                raf.fd.sync()
                return@withContext true
            }
            // 长度变化：须重写"新块 + 其后所有块"。注意不能用"先写 newBytes 再读后续块"——
            // newBytes 可能覆盖后续块开头（写后读污染）。先把脏块之后整段落盘到临时文件，
            // 再拼接写回，避免内存整载尾部。
            val tail = File.createTempFile("markflow_tail", ".tmp")
            try {
                tail.outputStream().use { out ->
                    var k = index + 1
                    while (true) {
                        val next = reader.rawChunkBytes(k) ?: break
                        out.write(next)
                        k++
                    }
                }
                raf.seek(start)
                raf.write(newBytes)
                tail.inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                    }
                }
                raf.setLength(raf.filePointer)
                raf.fd.sync()
                true
            } finally {
                runCatching { tail.delete() }
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { raf.close() }
        }
    }

    private suspend fun persistContentRewrite(
        reader: PagedTextSource,
        index: Int,
        newBytes: ByteArray,
        uri: String
    ): Boolean = withContext(Dispatchers.IO) {
        val temp = try {
            File.createTempFile("markflow_save", ".tmp")
        } catch (_: Exception) {
            return@withContext false
        }
        try {
            temp.outputStream().use { out ->
                var k = 0
                while (true) {
                    val bytes = if (k == index) newBytes else reader.rawChunkBytes(k)
                    if (bytes == null) break
                    out.write(bytes)
                    k++
                }
            }
            fileRepository.overwriteFileFromTemp(uri, temp)
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ==================== 内容编辑 ====================

    /**
     * 更新编辑内容并追踪光标位置
     * @param newContent 新的文本内容
     * @param cursorPos 当前光标位置（用于撤回/反撤回时恢复光标）
     *
     * 撤回机制：会话开始时立即将会话起始状态推入 undo 栈，使 canUndo 立即可用。
     * 防抖（500ms）只用于结束会话，下次按键开启新会话。
     * 这样连续输入 "hello" 后撤回会一次性恢复到输入前状态。
     */
    fun updateContent(newContent: String, cursorPos: Int = _uiState.value.lastCursorPos) {
        val oldContent = _uiState.value.currentContent
        // 自动保存：任何内容变化（包括撤回/反撤回）都重启 3 秒定时器
        if (newContent != oldContent) {
            scheduleAutoSave()
        }
        if (!isUndoRedoOp && newContent != oldContent) {
            val oldCursor = _uiState.value.lastCursorPos
            // 会话开始时立即推入起始状态；会话内继续编辑不推入（属于同一原子操作）
            val stateChanged = undoRedoManager.onContentChanged(oldContent, oldCursor)
            // 防抖：500ms 无操作后结束会话，下次按键开启新会话
            undoDebounceJob?.cancel()
            undoDebounceJob = viewModelScope.launch {
                delay(500)
                undoRedoManager.endSession()
            }
            if (stateChanged) {
                updateUndoRedoState()
            }
        }
        // 仅在内容或光标实际变化时更新 StateFlow，减少不必要的重组
        val hasUnsaved = newContent != _uiState.value.originalContent
        _uiState.update { state ->
            if (state.currentContent == newContent && state.lastCursorPos == cursorPos && state.hasUnsavedChanges == hasUnsaved) {
                state
            } else {
                state.copy(
                    currentContent = newContent,
                    hasUnsavedChanges = hasUnsaved,
                    lastCursorPos = cursorPos
                )
            }
        }
    }

    // ==================== 撤回 / 反撤回 ====================

    /**
     * 撤回：恢复到上一个编辑状态（内容+光标）
     * 当前状态推入 redo 栈，从 undo 栈弹出上一个状态
     */
    fun undo() {
        // 取消防抖，结束当前会话
        undoDebounceJob?.cancel()
        val currentContent = _uiState.value.currentContent
        val currentCursor = _uiState.value.lastCursorPos

        val result = undoRedoManager.undo(currentContent, currentCursor) ?: return

        // 原子性恢复：内容和光标同时更新
        isUndoRedoOp = true
        _uiState.update {
            it.copy(
                currentContent = result.content,
                hasUnsavedChanges = result.content != it.originalContent,
                pendingCursorPos = result.cursor.coerceIn(0, result.content.length),
                lastCursorPos = result.cursor.coerceIn(0, result.content.length)
            )
        }
        isUndoRedoOp = false
        updateUndoRedoState()
        // 撤回改变了内容 → 重启自动保存定时器
        scheduleAutoSave()
    }

    /**
     * 反撤回：恢复已撤回的操作（内容+光标）
     * 当前状态推入 undo 栈，从 redo 栈弹出下一个状态
     */
    fun redo() {
        undoDebounceJob?.cancel()
        val currentContent = _uiState.value.currentContent
        val currentCursor = _uiState.value.lastCursorPos

        val result = undoRedoManager.redo(currentContent, currentCursor) ?: return

        // 原子性恢复：内容和光标同时更新
        isUndoRedoOp = true
        _uiState.update {
            it.copy(
                currentContent = result.content,
                hasUnsavedChanges = result.content != it.originalContent,
                pendingCursorPos = result.cursor.coerceIn(0, result.content.length),
                lastCursorPos = result.cursor.coerceIn(0, result.content.length)
            )
        }
        isUndoRedoOp = false
        updateUndoRedoState()
        // 反撤回改变了内容 → 重启自动保存定时器
        scheduleAutoSave()
    }

    /** EditorScreen 应用 pendingCursorPos 后调用，清除标记 */
    fun clearPendingCursor() {
        if (_uiState.value.pendingCursorPos != null) {
            _uiState.update { it.copy(pendingCursorPos = null) }
        }
    }

    private fun updateUndoRedoState() {
        _uiState.update {
            it.copy(canUndo = undoRedoManager.canUndo, canRedo = undoRedoManager.canRedo)
        }
    }

    fun saveFile() {
        val state = _uiState.value
        if (state.isSaving || !state.hasUnsavedChanges) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveStatus = "保存中…") }
            try {
                val success = fileRepository.saveContent(state.fileUri, state.currentContent)
                if (success) {
                    fileRepository.notifyFileChanged()
                    // TOC 解析移到后台线程
                    val tocEntries = withContext(Dispatchers.Default) {
                        TocParser.parse(state.currentContent)
                    }
                    _uiState.update {
                        it.copy(
                            originalContent = it.currentContent,
                            hasUnsavedChanges = false,
                            isSaving = false,
                            saveStatus = "已保存",
                            tocEntries = tocEntries
                        )
                    }
                    delay(3000)
                    _uiState.update { it.copy(saveStatus = "") }
                } else {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败，请重试") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败: ${e.message}") }
            }
        }
    }

    /**
     * 自动保存：静默写入磁盘，不更新 UI 状态（不显示"保存中"/"已保存"），
     * 不触碰撤回/反撤回历史栈。仅更新 originalContent 以保证后续撤回比较的准确性。
     */
    private suspend fun performAutoSave() {
        val state = _uiState.value
        // 无未保存内容或正在手动保存时跳过
        if (!state.hasUnsavedChanges || state.isSaving || state.fileUri.isEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                fileRepository.saveContent(state.fileUri, state.currentContent)
            }
            fileRepository.notifyFileChanged()
            // 更新基准线：后续撤回/反撤回通过比较 originalContent 判断 hasUnsavedChanges
            _uiState.update {
                if (it.currentContent == state.currentContent) {
                    it.copy(
                        originalContent = state.currentContent,
                        hasUnsavedChanges = false
                    )
                } else {
                    // 自动保存期间内容已变化，放弃本次保存（下次输入停止后会重试）
                    it
                }
            }
        } catch (_: Exception) {
            // 自动保存失败静默处理，不打扰用户
        }
    }

    /** 重启 3 秒自动保存定时器，统一供 updateContent / undo / redo 调用 */
    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(3_000)
            performAutoSave()
        }
    }

    // ==================== 编码切换 ====================

    /**
     * 手动切换文本编码（"auto" = 清除覆盖并重新自动探测）。
     * 存在未保存更改时拒绝切换，避免静默丢弃编辑内容。
     * 切换后重新按所选编码加载当前文件。
     */
    fun switchEncoding(name: String) {
        if (!_uiState.value.hasUnsavedChanges) {
            val cs = when {
                name.equals("auto", ignoreCase = true) -> null
                else -> runCatching { Charset.forName(name) }.getOrNull() ?: null
            }
            encodingOverride = cs
            loadFile(_uiState.value.fileUri, resetEncoding = false)
        }
    }

    // ==================== 模式切换 ====================

    fun switchToEdit() {
        _uiState.update { it.copy(editorMode = EditorMode.EDIT) }
    }

    fun switchToPreview() {
        if (!_uiState.value.isMarkdown && _uiState.value.grammarName.isEmpty() && !_uiState.value.isTxt) return
        // 大 txt：切换预览前取消未保存的分段编辑（与返回键一致，避免静默丢弃歧义）
        if (_uiState.value.pagedEditingIndex != null) cancelPagedEdit()
        _uiState.update {
            it.copy(
                editorMode = EditorMode.PREVIEW,
                showSearchBar = false,
                searchQuery = "",
                searchMatchPositions = emptyList(),
                currentSearchIndex = -1,
                searchContext = ""
            )
        }
    }

    fun toggleImmersiveMode() {
        _uiState.update { it.copy(isImmersiveMode = !it.isImmersiveMode) }
    }

    // ==================== 图片插入 ====================

    /**
     * 将选中的图片写入 .md 同级 images/ 目录，并把 Markdown 引用插入当前光标处。
     * 写入在 IO 线程，成功后读取最新光标位置插入，避免异步期间光标漂移。
     */
    fun insertImage(imageUri: String) {
        val state = _uiState.value
        if (!state.isMarkdown || state.isReadOnlyPaged) return
        viewModelScope.launch {
            val relativePath = withContext(Dispatchers.IO) {
                fileRepository.copyImageForMarkdown(imageUri, _uiState.value.fileUri)
            }
            if (relativePath == null) {
                _uiState.update { it.copy(errorMessage = "图片导入失败") }
                return@launch
            }
            val snippet = "![图片]($relativePath)"
            val cur = _uiState.value
            val content = cur.currentContent
            val pos = cur.lastCursorPos.coerceIn(0, content.length)
            val newContent = content.substring(0, pos) + snippet + content.substring(pos)
            updateContent(newContent, cursorPos = pos + snippet.length)
        }
    }

    // ==================== 目录大纲 ====================

    fun toggleToc() {
        _uiState.update { it.copy(showToc = !it.showToc) }
    }

    fun dismissToc() {
        _uiState.update { it.copy(showToc = false) }
    }

    // ==================== 图片全屏 ====================

    fun showFullscreenImage(imageUrl: String) {
        _uiState.update { it.copy(fullscreenImageUrl = imageUrl) }
    }

    fun dismissFullscreenImage() {
        _uiState.update { it.copy(fullscreenImageUrl = null) }
    }

    // ==================== 搜索功能 ====================

    fun showSearch() {
        _uiState.update {
            it.copy(showSearchBar = true)
        }
    }

    fun closeSearch() {
        _uiState.update {
            it.copy(
                showSearchBar = false,
                searchQuery = "",
                searchMatchPositions = emptyList(),
                currentSearchIndex = -1,
                searchContext = ""
            )
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    searchQuery = query,
                    searchMatchPositions = emptyList(),
                    currentSearchIndex = -1,
                    searchContext = ""
                )
            }
            return
        }
        val content = _uiState.value.currentContent
        val positions = mutableListOf<Int>()
        var startIndex = 0
        while (true) {
            val index = content.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break
            positions.add(index)
            startIndex = index + query.length
        }
        val context = if (positions.isNotEmpty()) {
            extractContext(content, positions[0], query.length)
        } else ""
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchMatchPositions = positions,
                currentSearchIndex = if (positions.isNotEmpty()) 0 else -1,
                searchContext = context
            )
        }
    }

    fun navigateToPreviousMatch() {
        _uiState.update { state ->
            if (state.searchMatchPositions.isEmpty()) return@update state
            val newIndex = if (state.currentSearchIndex <= 0)
                state.searchMatchPositions.lastIndex
            else
                state.currentSearchIndex - 1
            val context = extractContext(
                state.currentContent,
                state.searchMatchPositions[newIndex],
                state.searchQuery.length
            )
            state.copy(currentSearchIndex = newIndex, searchContext = context)
        }
    }

    fun navigateToNextMatch() {
        _uiState.update { state ->
            if (state.searchMatchPositions.isEmpty()) return@update state
            val newIndex = if (state.currentSearchIndex >= state.searchMatchPositions.lastIndex)
                0
            else
                state.currentSearchIndex + 1
            val context = extractContext(
                state.currentContent,
                state.searchMatchPositions[newIndex],
                state.searchQuery.length
            )
            state.copy(currentSearchIndex = newIndex, searchContext = context)
        }
    }

    /**
     * 提取匹配项周围的上下文文本（前后各 ~30 字符）
     */
    private fun extractContext(content: String, matchStart: Int, matchLen: Int): String {
        val contextRadius = 30
        val start = (matchStart - contextRadius).coerceAtLeast(0)
        val end = (matchStart + matchLen + contextRadius).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end).replace("\n", " ") + suffix
    }

    // ==================== 返回拦截 ====================

    fun requestNavigateBack(onApproved: () -> Unit) {
        if (_uiState.value.hasUnsavedChanges) {
            _uiState.update { it.copy(showExitConfirmDialog = true) }
        } else {
            onApproved()
        }
    }

    fun confirmDiscard(onApproved: () -> Unit) {
        _uiState.update { it.copy(showExitConfirmDialog = false) }
        // 推迟到下一帧，让 Compose 先移除弹窗再执行导航
        viewModelScope.launch(Dispatchers.Main) {
            onApproved()
        }
    }

    fun cancelExit() {
        _uiState.update { it.copy(showExitConfirmDialog = false) }
    }

    fun saveAndExit(onApproved: () -> Unit) {
        viewModelScope.launch {
            // 先关闭弹窗，避免保存期间弹窗悬停
            _uiState.update { it.copy(showExitConfirmDialog = false) }
            val state = _uiState.value
            if (state.hasUnsavedChanges && !state.isSaving) {
                _uiState.update { it.copy(isSaving = true, saveStatus = "保存中…") }
                try {
                    val success = fileRepository.saveContent(state.fileUri, state.currentContent)
                    if (success) {
                        fileRepository.notifyFileChanged()
                        _uiState.update {
                            it.copy(
                                originalContent = it.currentContent,
                                hasUnsavedChanges = false,
                                isSaving = false,
                                saveStatus = "已保存",
                                tocEntries = TocParser.parse(it.currentContent)
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败，请重试") }
                        return@launch
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败: ${e.message}") }
                    return@launch
                }
            }
            onApproved()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ==================== 大 txt 阅读位置记录 / 书签 ====================

    /** UI 上报当前可视首个块索引：防抖换算为全局字节偏移并落盘（供下次恢复） */
    fun onPagedScrollPosition(blockIndex: Int) {
        if (blockIndex < 0) return
        if (!_uiState.value.isReadOnlyPaged) return
        if (pagedReader == null) return
        // 主线程同步记录最新可见块，作为书签与退出落盘的稳定依据
        lastPagedScrollBlockIndex = blockIndex
        scrollSaveJob?.cancel()
        scrollSaveJob = viewModelScope.launch {
            delay(SCROLL_SAVE_DEBOUNCE_MS)
            // 读取最新索引而非本次参数，避免滚动中旧 job 被取消后仍用过期块号落盘
            val idx = lastPagedScrollBlockIndex
            val off = withContext(Dispatchers.IO) { pagedReader?.chunkOffsetOf(idx) }
            if (off == null) return@launch
            lastPagedScrollByteOffset = off
            preferencesManager.savePagedReadPosition(_uiState.value.fileUri, off)
        }
    }

    /** 把全局字节偏移解析为块索引（恢复阅读位置用） */
    suspend fun resolveBlockIndex(byteOffset: Long): Int? = withContext(Dispatchers.IO) {
        pagedReader?.blockIndexForByteOffset(byteOffset)
    }

    /** UI 完成滚动跳转后清除待跳转偏移 */
    fun clearPagedJumpOffset() {
        if (_uiState.value.pagedJumpOffset != null) {
            _uiState.update { it.copy(pagedJumpOffset = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        undoDebounceJob?.cancel()
        autoSaveJob?.cancel()
        scrollSaveJob?.cancel()
        // 强兜底：滚动期间防抖 job 可能被清理而未把最新位置落盘，这里用
        // 主线程同步记录的"最后可见块索引"阻塞解析偏移并写盘，避免由于竞态
        // 把阅读位置丢回顶部。短阻塞（一次 seek）在退出场景可接受。
        val uri = _uiState.value.fileUri
        if (lastPagedScrollBlockIndex >= 0) {
            val reader = pagedReader
            if (reader != null) {
                val off = runCatching {
                    kotlinx.coroutines.runBlocking { reader.chunkOffsetOf(lastPagedScrollBlockIndex) }
                }.getOrNull()
                if (off != null) {
                    runCatching { preferencesManager.savePagedReadPosition(uri, off) }
                    return
                }
            }
        }
        lastPagedScrollByteOffset?.let {
            runCatching { preferencesManager.savePagedReadPosition(uri, it) }
        }
    }
}