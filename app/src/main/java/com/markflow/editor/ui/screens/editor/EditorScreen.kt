package com.markflow.editor.ui.screens.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.markflow.editor.domain.model.EditorMode
import com.markflow.editor.util.TocParser
import com.markflow.editor.ui.components.CodeBlockCard
import com.markflow.editor.domain.model.CodeBlockInfo
import com.markflow.editor.ui.components.MarkdownPreview
import com.markflow.editor.ui.components.MarkdownSyntaxHighlighter
import com.markflow.editor.ui.components.SearchBar
import com.markflow.editor.util.MarkwonConfig
import com.markflow.editor.util.AutoTextFormatter
import java.io.File

/** txt 编码手动切换的候选列表（不含"自动检测"，该项单独提供） */
private val encodingOptions = listOf("UTF-8", "GBK", "GB18030", "UTF-16LE", "UTF-16BE")

/**
 * 编辑器页面
 *
 * 支持两种视图模式：
 * - 编辑模式：直接编辑 Markdown 原文
 * - 预览模式：Markwon 渲染富文本
 *
 * @param fileUri 文件 URI
 * @param isDarkTheme 当前是否为深色主题
 * @param onNavigateBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    fileUri: String,
    isDarkTheme: Boolean = false,
    onNavigateBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 计算文件所在目录（用于解析相对路径图片）
    val baseDir = remember(uiState.fileUri) {
        if (uiState.fileUri.isEmpty()) {
            ""
        } else {
            MarkwonConfig.resolveBaseDir(context, uiState.fileUri)
        }
    }

    // 编辑区焦点管理：文件打开时自动聚焦到文本开头
    val editFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 图片选择器：选取图片后写入 .md 同级 images/ 目录并在光标处插入引用
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.insertImage(it.toString()) } }

    // 大 txt 分页阅读的 LazyListState：提升到 EditorScreen 层，跨模式切换/保存重建存活，
    // 使编辑一段后切换或保存重载时滚动位置不被重置到顶部。
    val pagedListState = rememberLazyListState()

    LaunchedEffect(fileUri) {
        viewModel.loadFile(fileUri)
        // 无自动恢复位置时重置到顶部；有恢复则交由 pagedJumpOffset 消费去跳转
        if (viewModel.uiState.value.pagedJumpOffset == null) {
            pagedListState.scrollToItem(0)
        }
    }

    // 消费待跳转偏移：恢复上次阅读位置 / 书签跳转（解析为块后滚动）
    LaunchedEffect(uiState.pagedJumpOffset) {
        val off = uiState.pagedJumpOffset ?: return@LaunchedEffect
        val block = viewModel.resolveBlockIndex(off)
        if (block != null) {
            viewModel.ensurePagedPage(block)
            pagedListState.scrollToItem(block)
        }
        viewModel.clearPagedJumpOffset()
    }

    // 上报当前可视首个块索引：防抖持久化，供下次打开恢复阅读位置
    LaunchedEffect(pagedListState, uiState.isReadOnlyPaged, fileUri) {
        if (!uiState.isReadOnlyPaged) return@LaunchedEffect
        snapshotFlow { pagedListState.firstVisibleItemIndex }
            .collect { viewModel.onPagedScrollPosition(it) }
    }

    // 拦截系统返回键：沉浸全屏中先退出全屏；分段编辑中先取消编辑；否则请求退出文件
    BackHandler {
        when {
            uiState.isImmersiveMode -> viewModel.toggleImmersiveMode()
            uiState.pagedEditingIndex != null -> viewModel.cancelPagedEdit()
            else -> viewModel.requestNavigateBack { onNavigateBack() }
        }
    }

    var textFieldValue by remember { mutableStateOf(TextFieldValue(text = "")) }
    var isInternalUpdate by remember { mutableStateOf(false) }

    // 标记是否已完成首次加载聚焦
    var hasInitialFocus by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.currentContent) {
        if (!isInternalUpdate && textFieldValue.text != uiState.currentContent) {
            val newContent = uiState.currentContent
            // 优先使用 ViewModel 计算的撤回/反撤回光标位置（基于内容差异定位变更区域）
            // 否则回退到保留当前光标位置（clamp 到新文本长度内）
            val cursorPos = uiState.pendingCursorPos
                ?.coerceIn(0, newContent.length)
                ?: textFieldValue.selection.start.coerceAtMost(newContent.length)
            textFieldValue = TextFieldValue(
                text = newContent,
                selection = TextRange(cursorPos)
            )
            // 文件首次加载完成后，将光标聚焦到文本开头，但不打开键盘
            if (!hasInitialFocus && newContent.isNotEmpty()) {
                hasInitialFocus = true
                textFieldValue = TextFieldValue(
                    text = newContent,
                    selection = TextRange(0)
                )
                try {
                    editFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {
                    // Activity 重建（主题切换等）时 FocusRequester 可能尚未附着到 Modifier
                }
                keyboardController?.hide()
            }
            // 应用后清除标记，避免后续内容更新重复使用
            if (uiState.pendingCursorPos != null) {
                viewModel.clearPendingCursor()
            }
        }
    }

    // 选中文本时隐藏键盘：非折叠选区 = 用户正在选择/复制，不需要 IME 输入
    LaunchedEffect(textFieldValue.selection.collapsed) {
        if (!textFieldValue.selection.collapsed) {
            keyboardController?.hide()
        }
    }

    // 滚动同步：编辑区和预览区
    val editScrollState = rememberScrollState()
    val previewScrollState = rememberScrollState()

    // 持续追踪编辑区有效滚动位置，跨 EDIT↔PREVIEW 模式切换存活。
    // EditContentView 销毁重建时 lastGoodScroll 从 scrollState.value 初始化
    // 可能读到 0（垂直滚动刚重新附着），导致补偿机制失效、选中文本跳顶。
    // 此处在 EditorScreen 层 snapshotFlow 持续捕获，但使用非 State 容器存储，
    // 避免滚动时每帧更新触发 EditorScreen → EditContentView 重组，导致
    // BasicTextField + VisualTransformation 对长文本重新布局，造成卡顿。
    val confirmedScrollPosRef = remember { com.markflow.editor.util.Ref(0) }
    LaunchedEffect(editScrollState) {
        snapshotFlow { editScrollState.value }
            .collect { v -> if (v > 0) confirmedScrollPosRef.value = v }
    }

    // 仅在切换到编辑模式时从 confirmedScrollPosRef 快照取值，
    // 作为 EditContentView 的 initialGoodScroll 参数，避免滚动时每帧重组。
    var initialGoodScroll by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiState.editorMode) {
        if (uiState.editorMode == EditorMode.EDIT) {
            initialGoodScroll = confirmedScrollPosRef.value
        }
    }

    // 编辑区 TextLayoutResult，用于光标跟随、搜索跳转、分屏行号同步
    var editLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 搜索上下文点击跳转触发器（递增触发 LaunchedEffect）
    var searchJumpTrigger by remember { mutableStateOf(0) }

    // 编码切换菜单（仅 txt 文件显示）
    var showEncodingMenu by remember { mutableStateOf(false) }

    // txt 文件才显示编码切换入口
    val isTxtFile = uiState.fileName.substringAfterLast('.', "").equals("txt", ignoreCase = true)

    // ==================== 搜索跳转：仅编辑模式自动跳转，预览模式仅高亮不跳转 ====================
    LaunchedEffect(uiState.currentSearchIndex, uiState.searchMatchPositions, editLayoutResult) {
        if (uiState.searchMatchPositions.isEmpty() || uiState.currentSearchIndex < 0) return@LaunchedEffect
        if (uiState.editorMode != EditorMode.EDIT) return@LaunchedEffect
        val pos = uiState.searchMatchPositions[uiState.currentSearchIndex]
        val layout = editLayoutResult ?: return@LaunchedEffect
        scrollToLineCenter(layout, pos, editScrollState)
    }

    // ==================== 搜索上下文点击跳转：编辑模式用 TextLayoutResult，预览模式由 MarkdownPreview 内部处理 ====================
    LaunchedEffect(searchJumpTrigger) {
        if (searchJumpTrigger == 0) return@LaunchedEffect
        if (uiState.searchMatchPositions.isEmpty() || uiState.currentSearchIndex < 0) return@LaunchedEffect
        val pos = uiState.searchMatchPositions[uiState.currentSearchIndex]
        if (uiState.editorMode == EditorMode.EDIT) {
            val layout = editLayoutResult ?: return@LaunchedEffect
            scrollToLineCenter(layout, pos, editScrollState)
        }
        // 预览模式：MarkdownPreview 通过 requestPositionTrigger 内部处理
    }

    // ==================== 主界面 ====================
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (!uiState.isImmersiveMode) {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = uiState.fileName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    // 始终显示保存状态：未保存(红) / 已保存(主色) / 保存中(瞬时状态)
                                    val statusText = when {
                                        uiState.saveStatus.isNotEmpty() -> uiState.saveStatus
                                        uiState.hasUnsavedChanges -> "未保存"
                                        else -> "已保存"
                                    }
                                    val statusColor = when {
                                        uiState.saveStatus.isNotEmpty() && uiState.isSaving ->
                                            MaterialTheme.colorScheme.primary
                                        uiState.hasUnsavedChanges ->
                                            MaterialTheme.colorScheme.error
                                        else ->
                                            MaterialTheme.colorScheme.primary
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    viewModel.requestNavigateBack { onNavigateBack() }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            actions = {
                                // 分享按钮：文件名右侧、目录按钮左侧
                                IconButton(
                                    onClick = {
                                        val shareIntent = buildShareIntent(context, uiState.fileUri, uiState.fileName)
                                        if (shareIntent != null) {
                                            context.startActivity(Intent.createChooser(shareIntent, "分享文件"))
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "分享文件",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                // 目录按钮：仅 Markdown 文件显示
                                if (uiState.isMarkdown) {
                                    IconButton(onClick = { viewModel.toggleToc() }) {
                                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录大纲")
                                    }
                                }
                                // 保存按钮（只读大文件隐藏）
                                if (!uiState.isReadOnlyPaged) {
                                    IconButton(onClick = { viewModel.saveFile() }, enabled = uiState.hasUnsavedChanges) {
                                        Icon(Icons.Default.Save, contentDescription = "保存")
                                    }
                                }
                                // 编码切换：仅 txt 文件显示（当前编码 + 下拉菜单）
                                if (isTxtFile) {
                                    Box {
                                        TextButton(onClick = { showEncodingMenu = true }) {
                                            Text(
                                                text = uiState.encodingName.takeIf { it.isNotBlank() }?.take(8) ?: "编码",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showEncodingMenu,
                                            onDismissRequest = { showEncodingMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("自动检测") },
                                                trailingIcon = {
                                                    if (uiState.encodingName.isBlank() ||
                                                        encodingOptions.none { it.equals(uiState.encodingName, true) }
                                                    ) {
                                                        Icon(Icons.Default.Check, contentDescription = null)
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.switchEncoding("auto")
                                                    showEncodingMenu = false
                                                }
                                            )
                                            encodingOptions.forEach { enc ->
                                                DropdownMenuItem(
                                                    text = { Text(enc) },
                                                    trailingIcon = {
                                                        if (uiState.encodingName.equals(enc, ignoreCase = true)) {
                                                            Icon(Icons.Default.Check, contentDescription = null)
                                                        }
                                                    },
                                                    onClick = {
                                                        viewModel.switchEncoding(enc)
                                                        showEncodingMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                IconButton(onClick = { viewModel.toggleImmersiveMode() }) {
                                    Icon(
                                        imageVector = if (uiState.isImmersiveMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = "沉浸式"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )

                        // 分段编辑操作栏：大 txt 编辑态显示"保存 / 取消"，并提供段级撤回/反撤回
                        if (uiState.isReadOnlyPaged && uiState.editorMode == EditorMode.EDIT && uiState.pagedEditingIndex != null) {
                            PagedEditActionBar(
                                isSaving = uiState.isPagedSaving,
                                canUndo = uiState.pagedCanUndo,
                                canRedo = uiState.pagedCanRedo,
                                onUndo = { viewModel.pagedUndo() },
                                onRedo = { viewModel.pagedRedo() },
                                onSave = { viewModel.savePagedEdit() },
                                onCancel = { viewModel.cancelPagedEdit() }
                            )
                        }

                        // 编辑/预览切换：所有文件统一显示；大 txt 隐藏全文工具（撤销/重做/搜索）
                        EditorModeSwitchBar(
                            currentMode = uiState.editorMode,
                            canUndo = uiState.canUndo,
                            canRedo = uiState.canRedo,
                            onUndo = { viewModel.undo() },
                            onRedo = { viewModel.redo() },
                            onSearch = { viewModel.showSearch() },
                            onSwitchToEdit = { viewModel.switchToEdit() },
                            onSwitchToPreview = { viewModel.switchToPreview() },
                            hasPreviewMode = uiState.isMarkdown || uiState.grammarName.isNotEmpty() || uiState.isTxt,
                            showTextTools = !uiState.isReadOnlyPaged,
                            showImageInsert = uiState.isMarkdown && !uiState.isReadOnlyPaged,
                            onInsertImage = { imagePickerLauncher.launch("image/*") }
                        )
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 搜索栏：仅在编辑模式显示
                if (uiState.showSearchBar && uiState.editorMode == EditorMode.EDIT) {
                    com.markflow.editor.ui.components.SearchBar(
                        onSearchQueryChanged = { viewModel.search(it) },
                        onClose = { viewModel.closeSearch() },
                        resultCount = uiState.searchMatchPositions.size,
                        currentIndex = uiState.currentSearchIndex,
                        searchContext = uiState.searchContext,
                        onNavigatePrevious = { viewModel.navigateToPreviousMatch() },
                        onNavigateNext = { viewModel.navigateToNextMatch() },
                        onContextClick = { searchJumpTrigger++ }
                    )
                }

                // 主内容区域：仅处理底部IME内边距，减少键盘动画期间的重组范围
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                ) {
                    // 用 ref 跟踪上一次的 TextFieldValue，确保 AutoTextFormatter 拿到正确的前值
                    val previousTextFieldValue = remember { mutableStateOf(textFieldValue) }

                    when (uiState.editorMode) {
                        EditorMode.EDIT -> {
                            if (uiState.isReadOnlyPaged) {
                                // 大 txt：分页浏览 + 长按分段编辑（不全文载入）
                                PagedReadContentView(
                                    listState = pagedListState,
                                    pages = uiState.pagedPages,
                                    loading = uiState.pagedLoading,
                                    eofAt = uiState.pagedEofAt,
                                    editingIndex = uiState.pagedEditingIndex,
                                    editingText = uiState.pagedEditingText,
                                    editSelection = uiState.pagedEditSelection,
                                    editingComposition = uiState.pagedEditComposition,
                                    editingEnabled = true,
                                    onLoadPage = { viewModel.ensurePagedPage(it) },
                                    onStartEdit = { index, cursor -> viewModel.startPagedEdit(index, cursor) },
                                    onEditTextChange = { text, selection, composition -> viewModel.updatePagedEditText(text, selection, composition) }
                                )
                            } else {
                                EditContentView(
                                    textFieldValue = textFieldValue,
                                    onValueChange = { v ->
                                        isInternalUpdate = true
                                        val formatted = AutoTextFormatter.applyListFormatting(
                                            previousTextFieldValue.value, v
                                        )
                                        previousTextFieldValue.value = formatted
                                        textFieldValue = formatted
                                        viewModel.updateContent(formatted.text, formatted.selection.start)
                                        isInternalUpdate = false
                                    },
                                    scrollState = editScrollState,
                                    initialGoodScroll = initialGoodScroll,
                                    isMarkdown = uiState.isMarkdown,
                                    isDarkTheme = isDarkTheme,
                                    searchQuery = uiState.searchQuery,
                                    searchMatches = uiState.searchMatchPositions,
                                    currentSearchIndex = uiState.currentSearchIndex,
                                    onLayoutResult = { editLayoutResult = it },
                                    focusRequester = editFocusRequester
                                )
                            }
                        }
                        EditorMode.PREVIEW -> {
                            when {
                                uiState.isReadOnlyPaged -> {
                                    // 大 txt：分页只读预览（不全文载入）
                                    PagedReadContentView(
                                        listState = pagedListState,
                                        pages = uiState.pagedPages,
                                        loading = uiState.pagedLoading,
                                        eofAt = uiState.pagedEofAt,
                                        editingIndex = null,
                                        editingText = "",
                                        editSelection = TextRange(0),
                                        editingEnabled = false,
                                        onLoadPage = { viewModel.ensurePagedPage(it) },
                                        onStartEdit = { _, _ -> },
                                        onEditTextChange = { _, _, _ -> }
                                    )
                                }
                                uiState.isMarkdown -> {
                                    MarkdownPreview(
                                        markdownText = uiState.currentContent,
                                        baseDir = baseDir,
                                        isDarkTheme = isDarkTheme,
                                        scrollState = previewScrollState,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                uiState.grammarName.isNotEmpty() -> {
                                    // 非 Markdown 文件但有语法高亮：显示代码 UI 卡片
                                    // 使用 LazyColumn 而非 Column + verticalScroll，避免滚动时
                                    // AndroidView 反复重组导致 TextView 重新测量、跳动
                                    val copyFeedback = remember { mutableStateOf(false) }
                                    val codeListState = rememberLazyListState()
                                    LazyColumn(
                                        state = codeListState,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        item(key = "code_block") {
                                            CodeBlockCard(
                                                language = uiState.grammarName,
                                                rawCode = uiState.currentContent,
                                                isDarkTheme = isDarkTheme,
                                                isCollapsed = false,
                                                showCopyFeedback = copyFeedback.value,
                                                onToggleCollapse = {},
                                                onCopyStart = {
                                                    copyFeedback.value = true
                                                    android.os.Handler(android.os.Looper.getMainLooper())
                                                        .postDelayed({ copyFeedback.value = false }, 1500)
                                                }
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    // 纯文本（含 txt 中小文件）：只读预览，显示实时未保存改动
                                    PlainTxtPreview(content = uiState.currentContent)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 沉浸式退出按钮
        if (uiState.isImmersiveMode) {
            FloatingActionButton(
                onClick = { viewModel.toggleImmersiveMode() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(40.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏", modifier = Modifier.size(20.dp))
            }
        }
    }

    // 目录大纲（ModalBottomSheet）——仅展示，不可点击跳转
    if (uiState.showToc) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissToc() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            TocSheet(
                entries = uiState.tocEntries,
                onDismiss = { viewModel.dismissToc() }
            )
        }
    }

    // ==================== 图片全屏对话框 ====================
    uiState.fullscreenImageUrl?.let { imageUrl ->
        Dialog(
            onDismissRequest = { viewModel.dismissFullscreenImage() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // 显式传递LifecycleOwner到Dialog窗口，兼容部分OEM ROM上Dialog的
            // ComposeView未正确继承ViewTreeLifecycleOwner的问题
            CompositionLocalProvider(LocalLifecycleOwner provides LocalLifecycleOwner.current) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                        .clickable { viewModel.dismissFullscreenImage() },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(false)
                            .build(),
                        contentDescription = "全屏图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = { viewModel.dismissFullscreenImage() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }

    // ==================== 对话框 ====================

    if (uiState.showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelExit() },
            title = { Text("未保存的更改") },
            text = { Text("有未保存的更改，确定要退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDiscard { onNavigateBack() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("放弃更改") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelExit() }) { Text("继续编辑") }
                    TextButton(onClick = { viewModel.saveAndExit { onNavigateBack() } }) { Text("保存并退出") }
                }
            }
        )
    }

    uiState.errorMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = { TextButton(onClick = { viewModel.clearError() }) { Text("关闭") } }
        ) { Text(message) }
    }
}

// ==================== 大 TXT 只读分页阅读 ====================

/** 分页阅读的虚拟 itemCount 上限（无限列表），远超实际块数即可 */
private const val MAX_PAGED_ITEMS = 1_000_000

/**
 * 大 TXT 只读分页内容视图
 *
 * 每个列表项对应一个字节块（约 10KB），按需惰性加载；块内容不驻留全部内存，
 * 由 ViewModel 以字节预算 LRU 管理。块缺失时显示加载占位并触发加载；
 * 文件末尾（[eofAt]）之后显示"已到底部"。
 *
 * 分段编辑：编辑态（[editingEnabled]=true）长按已加载块进入单块编辑
 * （[editingIndex] 命中时显示编辑框），其余块保持只读浏览；预览态为纯只读。
 * 采用固定大 itemCount 的"无限列表"：内核使用惰性块索引，
 * 不预扫全文，因此打开文件时总块数未知，滚动到底自然结束。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReadContentView(
    listState: LazyListState,
    pages: Map<Int, String>,
    loading: Set<Int>,
    eofAt: Int?,
    editingIndex: Int?,
    editingText: String,
    editSelection: TextRange,
    editingComposition: TextRange? = null,
    editingEnabled: Boolean,
    onLoadPage: (Int) -> Unit,
    onStartEdit: (Int, Int) -> Unit,
    onEditTextChange: (String, TextRange, TextRange?) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(count = MAX_PAGED_ITEMS, key = { it }) { index ->
            val text = pages[index]
            when {
                index == editingIndex -> {
                    // 正在编辑的块：单块编辑框（块约 10KB，编辑开销可控）。
                    // value 带 TextRange 选区，支持长按框选；光标/选区由状态驱动，不锁死在单点。
                    // 垂直内边距与只读 Text 保持一致，减少 item 切换时的布局跳变。
                    BasicTextField(
                        value = TextFieldValue(
                            text = editingText,
                            selection = editSelection.coerceIn(0, editingText.length),
                            composition = editingComposition
                        ),
                        onValueChange = { nv -> onEditTextChange(nv.text, nv.selection, nv.composition) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                text != null -> {
                    if (editingEnabled) {
                        // 编辑态：长按时用文本布局把触点换算为字符偏移，作为进入编辑后的初始光标位置
                        var layout by remember(index, text) { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .pointerInput(index, editingEnabled) {
                                    detectTapGestures(
                                        onTap = {},
                                        onLongPress = { offset ->
                                            val charOffset = layout?.getOffsetForPosition(offset) ?: 0
                                            onStartEdit(index, charOffset.coerceIn(0, text.length))
                                        }
                                    )
                                }
                        )
                    } else {
                        // 预览只读：支持长按框选文本后复制，同时保留垂直滚动
                        SelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                eofAt != null && index >= eofAt -> {
                    if (index == eofAt) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "已到底部",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        // 越过 EOF 的虚拟项：零高度占位，避免渲染空白滚动区
                        Spacer(modifier = Modifier.height(0.dp))
                    }
                }
                else -> {
                    if (index !in loading) {
                        LaunchedEffect(index) { onLoadPage(index) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分段编辑操作栏：大 txt 编辑态显示，提供段级"撤回 / 反撤回 / 保存 / 取消"
 */
@Composable
private fun PagedEditActionBar(
    isSaving: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isSaving) "保存中…" else "正在编辑当前段落（长按段落可编辑）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        // 段级撤回/反撤回：仅作用当前正在编辑的段
        IconButton(onClick = onUndo, enabled = !isSaving && canUndo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回")
        }
        IconButton(onClick = onRedo, enabled = !isSaving && canRedo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "反撤回")
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onCancel, enabled = !isSaving) {
            Text("取消")
        }
        Button(onClick = onSave, enabled = !isSaving) {
            Text("保存")
        }
    }
}

/**
 * 纯文本（含 txt 中小文件）只读预览。
 * 基于 [content]（内存实时内容，含未保存改动）按行虚拟化渲染，
 * 编辑态改动即时可见；等宽字体、自动换行，观感与 markdown 预览一致。
 */
@Composable
private fun PlainTxtPreview(content: String) {
    val lines = remember(content) { content.split('\n') }
    // 预览只读：SelectionContainer 支持长按框选文本复制，同时保留列表上下滚动
    SelectionContainer(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(count = lines.size, key = { i -> i }) { index ->
                Text(
                    text = lines[index].removeSuffix("\r"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ==================== 滚动同步辅助 ====================

/**
 * 根据 TextLayoutResult 将指定字符偏移所在行居中滚动。
 * 使用行中心（(lineTop + lineBottom) / 2）而非行顶部做居中计算，
 * 确保关键词视觉上位于屏幕垂直中央。
 *
 * @return 实际滚动到的位置，-1 表示跳过（viewport 无效或已在目标位置）
 */
private suspend fun scrollToLineCenter(
    layout: TextLayoutResult,
    charOffset: Int,
    scrollState: ScrollState
): Int {
    val viewport = scrollState.viewportSize
    if (viewport <= 0 || scrollState.maxValue <= 0) return -1

    val line = layout.getLineForOffset(charOffset.coerceIn(0, layout.layoutInput.text.length - 1))
    val lineTop = layout.getLineTop(line)
    val lineBottom = layout.getLineBottom(line)
    val lineCenter = (lineTop + lineBottom) / 2f

    val target = (lineCenter - viewport / 2f)
        .toInt()
        .coerceIn(0, scrollState.maxValue)

    scrollState.scrollTo(target)
    return target
}

// ==================== 子组件 ====================

/**
 * 模式切换栏
 * 包含：模式切换按钮（编辑/预览）+ 撤回/反撤回按钮（仅编辑模式）+ 搜索按钮（仅编辑模式）+ 模式状态文字
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorModeSwitchBar(
    currentMode: EditorMode,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSwitchToEdit: () -> Unit,
    onSwitchToPreview: () -> Unit,
    hasPreviewMode: Boolean = true,
    /** 是否显示全文编辑工具（撤销/重做/搜索）；大 txt 分段编辑不适用全文工具 */
    showTextTools: Boolean = true,
    /** 是否显示图片插入按钮（仅 Markdown 编辑模式） */
    showImageInsert: Boolean = false,
    /** 点击图片插入按钮回调 */
    onInsertImage: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (hasPreviewMode) {
            // 编辑/预览切换：同一位置单图标，编辑显示笔、预览显示眼睛，点击互相切换
            IconButton(
                onClick = {
                    if (currentMode == EditorMode.EDIT) onSwitchToPreview() else onSwitchToEdit()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (currentMode == EditorMode.EDIT) {
                        Icons.Default.Create
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (currentMode == EditorMode.EDIT) "切换到预览" else "切换到编辑",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            // 无预览模式：仅显示"编辑"图标
            Icon(
                imageVector = Icons.Default.Create,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 搜索图标：仅编辑模式显示，与撤回/反撤回间统一间距
            if (currentMode == EditorMode.EDIT && showTextTools) {
                IconButton(onClick = onSearch, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            // 撤回/反撤回按钮：仅编辑模式显示（全文工具）
            if (currentMode == EditorMode.EDIT && showTextTools) {
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回")
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "反撤回")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            // 图片插入按钮：仅 Markdown 编辑模式显示
            if (currentMode == EditorMode.EDIT && showImageInsert) {
                IconButton(onClick = onInsertImage, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "插入图片")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            val modeLabel = when {
                !hasPreviewMode -> "编辑模式"
                currentMode == EditorMode.EDIT -> "编辑模式"
                currentMode == EditorMode.PREVIEW -> "预览模式"
                else -> ""
            }
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 搜索高亮 VisualTransformation
 * 当前匹配项用橙色(#FFB74D)背景，其他匹配项用浅蓝(#81D4FA)背景
 */
private class SearchHighlightTransformation(
    private val matchPositions: List<Int>,
    private val currentIndex: Int,
    private val queryLength: Int
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        if (matchPositions.isEmpty() || queryLength == 0) {
            return androidx.compose.ui.text.input.TransformedText(text, OffsetMapping.Identity)
        }
        val builder = buildAnnotatedString {
            append(text.text)
            matchPositions.forEachIndexed { index, start ->
                val end = (start + queryLength).coerceAtMost(text.length)
                if (start in 0 until end && end <= text.length) {
                    val color = if (index == currentIndex) Color(0xFFFFB74D) else Color(0xFF81D4FA)
                    addStyle(SpanStyle(background = color), start, end)
                }
            }
        }
        return androidx.compose.ui.text.input.TransformedText(builder, OffsetMapping.Identity)
    }
}

/**
 * 编辑内容视图
 *
 * 特性：
 * - 搜索关键词高亮（VisualTransformation，不影响实际文本和光标）
 * - 光标跟随：键盘弹出或光标移动时自动滚动保持光标可见
 * - 暴露 TextLayoutResult 供外部（分屏同步、搜索跳转）使用
 *
 * @param searchQuery 搜索关键词
 * @param searchMatches 搜索匹配项的字符偏移列表
 * @param currentSearchIndex 当前高亮的匹配项索引
 * @param onLayoutResult TextLayoutResult 回调
 */
@Composable
private fun EditContentView(
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    initialGoodScroll: Int = 0,
    isMarkdown: Boolean = false,
    isDarkTheme: Boolean = false,
    searchQuery: String = "",
    searchMatches: List<Int> = emptyList(),
    currentSearchIndex: Int = -1,
    onLayoutResult: (TextLayoutResult) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    // 语法高亮：仅在 Markdown 文件且非搜索状态时启用
    val markdownHighlighter = remember(isDarkTheme) {
        MarkdownSyntaxHighlighter(
            if (isDarkTheme) MarkdownSyntaxHighlighter.SyntaxColors.dark()
            else MarkdownSyntaxHighlighter.SyntaxColors.light()
        )
    }

    // 搜索高亮 / 语法高亮：搜索优先
    val visualTransformation = remember(searchQuery, searchMatches, currentSearchIndex, isMarkdown) {
        when {
            searchQuery.isNotEmpty() && searchMatches.isNotEmpty() ->
                SearchHighlightTransformation(searchMatches, currentSearchIndex, searchQuery.length)
            isMarkdown -> markdownHighlighter
            else -> VisualTransformation.None
        }
    }

    // 缓存占位文本，避免每次重组都重新构建字符串
    val placeholderText = remember {
        buildString {
            appendLine("开始编写 Markdown 文档…")
            appendLine()
            appendLine("语法参考：")
            appendLine("# 一级标题")
            appendLine("## 二级标题")
            appendLine("**加粗文字**")
            appendLine("*斜体文字*")
            appendLine("- 无序列表项")
            appendLine("1. 有序列表项")
            appendLine("> 引用文本")
            appendLine("```")
            appendLine("代码块")
            appendLine("```")
            appendLine("[链接文字](https://example.com)")
            appendLine("![图片描述](images/xxx.png)")
        }
    }

    // 缓存 layout 结果，始终更新以保持搜索跳转的准确性
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // BasicTextField 首次触发的 bringIntoView 可能因 graphicsLayer 未稳定
    // 将外层 scrollState 异常归零；snapshotFlow 持续追踪有效滚动位置。
    // 初始值从外部 confirmedScrollPos 传入，跨模式切换存活。
    // 仅在选区折叠时更新，选区激活期间冻结，防止 bringIntoView 造成的
    // 异常滚动值污染 lastGoodScroll。
    var lastGoodScroll by remember { mutableIntStateOf(initialGoodScroll) }

    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value to textFieldValue.selection.collapsed }
            .collect { (v, collapsed) -> if (v > 0 && collapsed) lastGoodScroll = v }
    }

    // 复制框激活时 bringIntoView 可能改变 scrollState.value（不限于归零），
    // 将偏离量通过 graphicsLayer 在同帧补偿，消除位移；随后异步恢复实际值。
    val compensationOffset by remember {
        derivedStateOf {
            if (!textFieldValue.selection.collapsed && lastGoodScroll > 0) {
                val delta = scrollState.value - lastGoodScroll
                if (delta != 0) -delta.toFloat() else 0f
            } else 0f
        }
    }

    // 搜索激活及关闭后锁定光标跟随：只要用户没有再次输入/移动光标，
    // 关闭搜索栏引发的视图/滚动变化都不会把文本拉回光标处，保证"保持当前页面不动"。
    var wasSearchActive by remember { mutableStateOf(false) }
    var suppressCursorFollowUntilEdit by remember { mutableStateOf(false) }
    val editTextFieldValueChange: (TextFieldValue) -> Unit = { nv ->
        if (suppressCursorFollowUntilEdit) suppressCursorFollowUntilEdit = false
        onValueChange(nv)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // 外层 Box 负责滚动，避免 BasicTextField 内部 bringIntoView 误跳到顶部
        // graphicsLayer 补偿：bringIntoView 异常归零时，视觉层同步补偿偏移，消除闪烁
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .graphicsLayer { translationY = compensationOffset }
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = editTextFieldValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                onTextLayout = { result ->
                    layoutResult = result
                    onLayoutResult(result)
                },
                decorationBox = { innerTextField ->
                    Box {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = placeholderText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        // 光标跟随：仅在光标移动或视口大小变化（maxValue 变化）时触发
        // 使用 scrollState.viewportSize 替代 onGloballyPositioned，避免键盘动画期间每帧重组
        //
        // 注意：EditContentView 在 EDIT↔PREVIEW 切换时会移出/重新加入组合树，
        // scrollState.value 会被 Compose 保留，但 maxValue 变化会重新触发本
        // LaunchedEffect，导致光标跟随逻辑覆盖掉保留的滚动位置。
        // 因此首次触发时跳过，后续正常跟随光标。
        var skipCursorFollow by remember { mutableStateOf(true) }
        LaunchedEffect(searchQuery) {
            if (searchQuery.isNotEmpty()) {
                wasSearchActive = true
                suppressCursorFollowUntilEdit = true
            } else if (wasSearchActive) {
                // 刚关闭搜索：保持锁定，直到用户编辑/移动光标（见 editTextFieldValueChange）
                wasSearchActive = false
            }
        }
        LaunchedEffect(textFieldValue.selection, scrollState.maxValue) {
            if (searchQuery.isNotEmpty()) return@LaunchedEffect
            if (suppressCursorFollowUntilEdit) return@LaunchedEffect
            if (skipCursorFollow) {
                skipCursorFollow = false
                return@LaunchedEffect
            }
            val layout = layoutResult ?: return@LaunchedEffect
            val selection = textFieldValue.selection
            val viewport = scrollState.viewportSize
            if (selection.collapsed && viewport > 0) {
                val cursorRect = layout.getCursorRect(selection.start)
                val viewportTop = scrollState.value.toFloat()
                val viewportBottom = viewportTop + viewport.toFloat()
                if (cursorRect.top < viewportTop || cursorRect.bottom > viewportBottom) {
                    val target = (cursorRect.top - viewport / 3f)
                        .coerceIn(0f, scrollState.maxValue.toFloat())
                    scrollState.scrollTo(target.toInt())
                }
            }
        }

        // graphicsLayer 补偿生效后，异步将 scrollState 恢复到真实位置，
        // 使后续交互（光标跟随、搜索跳转等）使用正确的 scrollState.value
        LaunchedEffect(compensationOffset) {
            if (compensationOffset != 0f) {
                scrollState.scrollTo(lastGoodScroll.coerceAtMost(scrollState.maxValue))
            }
        }
    }
}

/**
 * 目录大纲内容
 */
@Composable
private fun TocSheet(
    entries: List<TocParser.TocEntry>,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("目录大纲", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无标题",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(entries) { entry ->
                    TocItem(entry = entry)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 目录条目
 */
@Composable
private fun TocItem(
    entry: TocParser.TocEntry
) {
    val indentDp = ((entry.level - 1) * 16).dp
    val fs = when (entry.level) {
        1 -> 16
        2 -> 15
        3 -> 14
        else -> 13
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + indentDp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    when (entry.level) {
                        1 -> MaterialTheme.colorScheme.primary
                        2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        3 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    RoundedCornerShape(3.dp)
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = fs.sp,
                fontWeight = if (entry.level <= 2) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 构建文件分享 Intent
 *
 * 将编辑器当前打开的文件解析为可分享的 content URI，
 * 支持 content:// 和 file:// 两种协议。
 *
 * @param context Android Context
 * @param fileUri 文件 URI 字符串
 * @param fileName 文件名（用于分享标题）
 * @return 分享 Intent，解析失败返回 null
 */
private fun buildShareIntent(
    context: android.content.Context,
    fileUri: String,
    fileName: String
): Intent? {
    if (fileUri.isEmpty()) return null
    return try {
        val uri = Uri.parse(fileUri)
        val shareUri: Uri = when {
            uri.scheme == "content" -> uri
            uri.scheme == "file" -> {
                val file = File(uri.path ?: return null)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }
            else -> return null
        }
        Intent(Intent.ACTION_SEND).apply {
            type = "text/*"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) {
        null
    }
}
