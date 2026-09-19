package com.markflow.editor.ui.screens.editor

import android.content.Intent
import android.net.Uri
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import com.markflow.editor.util.resolveShareMimeType
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
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
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

    // 编辑区焦点能力载体：挂在 BasicTextField 上供需要时 requestFocus。
    // （不再自动聚焦——文件打开呈浏览态，点击文本才进入编辑并落光标；B′ 已保证长按不跳顶，
    //   故无需再靠"首载聚焦制造可见光标"来防跳顶。）
    val editFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // 拦截系统返回键：沉浸全屏中先退出全屏；分段编辑中先取消编辑；否则请求退出文件。
    // 2026-09-19 移除「无键盘有光标→先清光标再退出」分支：键盘收起已由 EditContentView 的
    // keyboardWasOpen effect 自动 clearFocus（见其注释），该层正常流程已失效，直接退出更合理。
    BackHandler {
        when {
            uiState.isImmersiveMode -> viewModel.toggleImmersiveMode()
            uiState.pagedEditingIndex != null -> viewModel.cancelPagedEdit()
            else -> viewModel.requestNavigateBack { onNavigateBack() }
        }
    }
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

    var textFieldValue by remember { mutableStateOf(TextFieldValue(text = "")) }
    var isInternalUpdate by remember { mutableStateOf(false) }
    // 跟踪"上一次输入/内容更新后"的 TextFieldValue，供 AutoTextFormatter 的列表续行/清理
    // 取正确的前文本。必须在撤销/重做等"直接覆盖 textFieldValue"的路径里同步（见下方 LaunchedEffect）。
    val previousTextFieldValue = remember { mutableStateOf(textFieldValue) }

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
            // 同步"上一次输入前文本"：撤销/重做直接覆盖 textFieldValue 时也必须同步
            // previousTextFieldValue，否则下次键入时 AutoTextFormatter 的列表续行/空项清理
            // 会拿到撤销前的陈旧前文（M2）
            previousTextFieldValue.value = textFieldValue
            // 应用后清除标记，避免后续内容更新重复使用
            if (uiState.pendingCursorPos != null) {
                viewModel.clearPendingCursor()
            }
        }
    }

    // 选中文本时的键盘策略：原则是"框选不改变键盘升降状态"。
    // - 键盘未升起(浏览态)时产生选区：仍照旧 hide(无害，且能压制长按偶发带起的 IME)，保持不弹。
    // - 键盘已升起(输入中)时产生选区：不再 hide → 键盘保持升起，不因框选而收起(用户场景 B)。
    // isImeVisible 经 rememberUpdatedState 供 LaunchedEffect 读取；不触碰 B′(pointerInput 就近落点)。
    val imeVisibleNow by rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(textFieldValue.selection.collapsed) {
        if (!textFieldValue.selection.collapsed && !imeVisibleNow) {
            keyboardController?.hide()
        }
    }

    // 编辑区 / 预览区各自的滚动位置（EDIT 与 PREVIEW 二选一，互不同屏、互不联动同步）
    val editScrollState = rememberScrollState()
    val previewScrollState = rememberScrollState()

    // 编辑区滚动位置由 editScrollState（EditorScreen 级 remember）跨 EDIT↔PREVIEW
    // 模式切换保留：EditContentView 销毁重建时 verticalScroll 重新附着同一 state，
    // value 不重置，无需额外快照。

    // 编辑区 TextLayoutResult，用于光标跟随、搜索跳转定位
    var editLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 搜索上下文点击跳转触发器（递增触发 LaunchedEffect）
    var searchJumpTrigger by remember { mutableIntStateOf(0) }

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
                                // 目录按钮：仅 Markdown 文件显示（大 md 分页只读不全文载入，无法生成目录）
                                if (uiState.isMarkdown && !uiState.isReadOnlyPaged) {
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

                // 主内容区域。键盘避让：不用 windowInsetsPadding(ime)（其随键盘动画逐帧变化，触发整篇
                // 滚动容器逐帧重排重绘 →「所有文件」键盘开/关卡顿，A/B-IME 2026-09-17 已证）。
                // 改「settle 后一次性垫入」：键盘弹起等高度稳定后单次加 bottom padding，仅一次
                // 布局，光标可顶到键盘上方且不卡；收起清零。
                var keyboardBottomPx by remember { mutableStateOf(0) }
                val editorView = LocalView.current
                // 一次性垫高上限：防某些时机读到异常大 IME insets 把内容区挤成近 0 高（文本全空白）
                val keyboardCapPx = with(LocalDensity.current) { 520.dp.roundToPx() }
                val keyboardScope = rememberCoroutineScope()
                // IME 高度实时感知（含「可见期间高度变化」，如 拼音⇄手写）：不再只按 imeVisibleNow 翻转一次性
                // 读取——那样键盘保持可见但高度切换（拼音⇄手写）永远捕获不到，光标会被拔高后的键盘盖住。
                // 改为监听视图全局布局（IME 显示/高度变化会触发窗口 insets 重排 → 全局布局回调），每次读一次
                // ime().bottom，settle 防抖（250ms）后一次性提交。只读不消费、不改任何布局：不随键盘动效逐帧
                // 重组（§5.1 前车之鉴），也不覆盖 Compose 自身的 insets 分发（防连累 navigationBarsPadding 等）。
                DisposableEffect(editorView) {
                    var settleJob: Job? = null
                    val lastCommitted = IntArray(1) // 当前已提交值，避免对同一高度重复提交/回跳
                    val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                        val raw = (ViewCompat.getRootWindowInsets(editorView)
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                            .coerceIn(0, keyboardCapPx)
                        settleJob?.cancel()
                        settleJob = keyboardScope.launch {
                            // settle 死区 250ms：键盘启动/高度切换动画（~100-300ms，且事件常呈爆发式
                            // 间断）期间即使 >80ms 无布局事件仍会在中间高度提交一次，光标跟随以
                            // keyboardOffsetPx 为 key 会先抬再落形成小幅上下抽动（2026-09-19 偶发实证）。
                            // 拉长到覆盖动画间歇，使键盘高度只提交一次「稳定终值」→ 单次抬升、无回摆。
                            delay(250)
                            if (raw != lastCommitted[0]) {
                                lastCommitted[0] = raw
                                keyboardBottomPx = raw
                            }
                        }
                    }
                    val vto = editorView.viewTreeObserver
                    vto.addOnGlobalLayoutListener(layoutListener)
                    onDispose {
                        settleJob?.cancel()
                        vto.removeOnGlobalLayoutListener(layoutListener)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 内容区保持满高（不随键盘压缩）：压缩高度（padding 或 Spacer 兄弟占位）会
                        // 令 verticalScroll 内容整棵从组合/无障碍树消失→屏幕空白（2026-09-18 A/B 实证，
                        // 文本只随键盘隐藏）。光标是否被键盘遮挡改由 EditContentView 的光标跟随
                        // 以 keyboardOffsetPx 抬升光标解决，见下方 EditContentView。
                ) {
                    when (uiState.editorMode) {
                        EditorMode.EDIT -> {
                            if (uiState.isReadOnlyPaged) {
                                // 大 txt / 大 md：分页浏览 + 长按分段编辑（不全文载入）
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
                                        val formatted = if (uiState.isMarkdown) {
                                            AutoTextFormatter.applyListFormatting(
                                                previousTextFieldValue.value, v
                                            )
                                        } else {
                                            v
                                        }
                                        previousTextFieldValue.value = formatted
                                        textFieldValue = formatted
                                        viewModel.updateContent(formatted.text, formatted.selection.start)
                                        isInternalUpdate = false
                                    },
                                    scrollState = editScrollState,
                                    // 大 md：编辑保留全文但禁用语法高亮（防 2.38MB 级文档高亮开销）
                                    isMarkdown = uiState.isMarkdown && !uiState.isLargeMd,
                                    // 占位按文件类型选择；空白文件才显示（大文件走分页只读，不经过本视图故天然不显示）
                                    placeholderText = PlaceholderTemplates.forExtension(uiState.fileExtension),
                                    isDarkTheme = isDarkTheme,
                                    searchQuery = uiState.searchQuery,
                                    searchMatches = uiState.searchMatchPositions,
                                    currentSearchIndex = uiState.currentSearchIndex,
                                    onLayoutResult = { editLayoutResult = it },
                                    focusRequester = editFocusRequester,
                                    keyboardOffsetPx = keyboardBottomPx
                                )
                            }
                        }
                        EditorMode.PREVIEW -> {
                            when {
                                uiState.isReadOnlyPaged || uiState.isLargeMd -> {
                                    // 大 txt / 大 md：分页只读预览（不全文载入，避免大文档渲染崩溃）
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

// ==================== 搜索跳转定位辅助 ====================

/**
 * 根据 TextLayoutResult 将指定字符偏移所在行居中滚动。
 * 供编辑模式搜索跳转 / 搜索上下文点击定位使用。
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
            // 编辑/预览切换：同一位置单图标，编辑显示眼睛、预览显示笔，点击互相切换
            IconButton(
                onClick = {
                    if (currentMode == EditorMode.EDIT) onSwitchToPreview() else onSwitchToEdit()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (currentMode == EditorMode.EDIT) {
                        Icons.Default.Visibility
                    } else {
                        Icons.Default.Create
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
 * - 暴露 TextLayoutResult 供外部（光标跟随、搜索跳转定位）使用
 *
 * @param searchQuery 搜索关键词
 * @param searchMatches 搜索匹配项的字符偏移列表
 * @param currentSearchIndex 当前高亮的匹配项索引
 * @param onLayoutResult TextLayoutResult 回调
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EditContentView(
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    isMarkdown: Boolean = false,
    /** 空白文件占位文本；null = 不显示占位（按文件类型由调用方传入） */
    placeholderText: String? = null,
    isDarkTheme: Boolean = false,
    searchQuery: String = "",
    searchMatches: List<Int> = emptyList(),
    currentSearchIndex: Int = -1,
    onLayoutResult: (TextLayoutResult) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** 键盘高度(px)：光标跟随把"安全可见底边"上移到 viewport-top 上方固定偏移，保证光标不被键盘遮挡 */
    keyboardOffsetPx: Int = 0
) {
    // 语法高亮：仅在 Markdown 文件且非搜索状态时启用
    val markdownHighlighter = remember(isDarkTheme) {
        MarkdownSyntaxHighlighter(
            if (isDarkTheme) MarkdownSyntaxHighlighter.SyntaxColors.dark()
            else MarkdownSyntaxHighlighter.SyntaxColors.light()
        )
    }
    // 离开组合时取消未完成的异步高亮计算，防对脱离组合的状态写入
    DisposableEffect(markdownHighlighter) {
        onDispose { markdownHighlighter.cancelPending() }
    }

    // 异步高亮版本号：后台高亮计算完成后自增，此处用 by 委托读取 Int 值建立组合层依赖。
    //
    // 必须用 by 委托（或 .value）读取——若用 = 赋值，highlightVersion 变量拿到的是
    // State<Int> 对象引用而非值。State 对象引用永远不变，放进 remember key 后 key 恒定，
    // 永不触发重组 → 异步高亮完成后 filter 不会重跑 → 高亮永远不上屏（"长按后才有高亮"
    // 根因：长按改变 selection 触发 BasicTextField 重组碰巧重跑 filter，但正常打开
    // 不会）。by 委托在 Composable 层建立 State 订阅，cacheVersion 变化即触发重组。
    val highlightVersion by markdownHighlighter.highlightVersion

    // 搜索高亮 / 语法高亮：搜索优先
    //
    // highlightVersion 纳入 remember key：异步高亮完成后版本号自增 → by 委托读到新值 →
    // 此处重组 → 创建一个新的 VisualTransformation 包装实例 → BasicTextField 检测到 VT 实例
    // 变化后重新调用 filter → 此时缓存已就绪 → 命中 → 高亮正确上屏。
    //
    // 不能直接返回 markdownHighlighter：BasicTextField 对同一 VT 实例引用会跳过
    // filter 重跑，必须每次 highlightVersion 变化时创建新包装实例强制重跑。
    val visualTransformation = remember(
        searchQuery, searchMatches, currentSearchIndex, isMarkdown, isDarkTheme, highlightVersion
    ) {
        when {
            searchQuery.isNotEmpty() && searchMatches.isNotEmpty() ->
                SearchHighlightTransformation(searchMatches, currentSearchIndex, searchQuery.length)
            isMarkdown -> {
                val inner = markdownHighlighter
                object : VisualTransformation {
                    override fun filter(text: AnnotatedString): TransformedText = inner.filter(text)
                }
            }
            else -> VisualTransformation.None
        }
    }

    // 缓存 layout 结果，始终更新以保持搜索跳转的准确性
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 搜索激活及关闭后锁定光标跟随：只要用户没有再次输入/移动光标，
    // 关闭搜索栏引发的视图/滚动变化都不会把文本拉回光标处，保证"保持当前页面不动"。
    var wasSearchActive by remember { mutableStateOf(false) }
    var suppressCursorFollowUntilEdit by remember { mutableStateOf(false) }
    // 【X-H6 B′ 修复(2026-09-05 根因)】pointerInput(Unit) 的 suspend 块只随 key=Unit 变化重启、
    // 不随重组更新，闭包会捕获【首次组合时】的 textFieldValue(空文本)而永远读到陈旧值，导致
    // B′ 落点因 text.isEmpty() 恒真而永不执行。用 rememberUpdatedState 使 B′ 始终读到最新值。
    val currentTextFieldValue by rememberUpdatedState(textFieldValue)
    // 首获聚焦对齐：(2026-09-18 真机) 记录最近一次 down 时外层 verticalScroll 的滚动量，
    // 首次聚焦时先把外层滚动对齐到该处，使聚焦 reveal 判定时光标已在 TextField 自身可视区
    // （近似），reveal no-op 而不再拉外层跳顶。见下方 onFocusChanged 注释。
    var lastDownV by remember { mutableIntStateOf(0) }  // 本次 down 时外层滚动量
    // 首获聚焦在 onFocusChanged 回调内（非 suspend，无现成 scope）发起延迟对齐滚动，
    // 需自建协程 scope；2026-09-19 二修后每次聚焦 gain 都对齐（见 onFocusChanged 注释）。
    val focusScope = rememberCoroutineScope()
    // 滚动容器可视高度(px)：空文件文本仅一行时，用它给 BasicTextField 设 minHeight 铺满
    // 整屏，使点击首行以下的空白也能落在文本控件上而聚焦；不能用 fillMaxHeight——
    // 外层 verticalScroll 给子节点的高度约束是无限大，会被拉成无穷高而运行时崩溃。
    val density = LocalDensity.current
    var editAreaHeightPx by remember { mutableIntStateOf(0) }
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
        // 【X-H6】长按框选"跳顶"根治见 BasicTextField 的 pointerInput（B′：长按前把折叠
        // 光标落到 down 点，消除视口外幽灵 offset 0）。此处不再需要主动拉回等兜底。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { editAreaHeightPx = it.height }
                .verticalScroll(scrollState)
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = editTextFieldValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    // 最小高度铺满可视区：空文件文本仅一行时让点击空白也能聚焦（见上 editAreaHeightPx）
                    .heightIn(min = with(density) { editAreaHeightPx.toDp() })
                    // 底部留白 + 键盘垫高（2026-09-19 Bug1 修复）：文本贴底无空白的根因是滚动内容
                    // 无底部留白——末行 curB 贴合视口底、且 v 已达 maxValue，键盘弹出时光标跟随想抬
                    // 也抬不动(maxValue 钳死)。此 padding 加在滚动内容上：① 让末行下方始终有呼吸空白
                    // （绝不让 lastDownV=maxValue 的末皮肤光标贴合底部）；② keyboardOffsetPx 追随实时
                    // 键盘高度，键盘弹起时滚动空间增到≥键盘高，光标可被抬到键盘上方。它只增大
                    // maxValue、不压缩视口高度，不会触发 §5.1「压缩内容区即空白」陷阱。
                    .padding(
                        bottom = with(density) {
                            (keyboardOffsetPx + 40.dp.roundToPx()).toDp()
                        }
                    )
                    .focusRequester(focusRequester)
                    .onFocusChanged { fs ->
                        // 【首获聚焦跳顶根治】(2026-09-18 真机定案)
                        // 根因：BasicTextField 聚焦 reveal 用「自身滚动 offset=0」判定光标是否可见；
                        // 外层 verticalScroll 已滚动(如 v=11140)且光标在屏外(y≈12164)时，reveal 判定其
                        // 不可见 → 一步把外层拉到错误位置(观测：末页 8129→2792)，随后我们复位又拉回。
                        // 与 setSelection 无关(原生 tap 已证自行落到点按处)。
                        // 修复(2026-09-19)：聚焦时立即把外层滚动对齐到本次点按前的滚动量(lastDownV)，
                        // 使 reveal 判定光标已在可视区(近似) → no-op；并在首帧后再 reconcile 一次，杜绝
                        // delay(48) 拼时序在主线程忙时输给 reveal 的末页回弹(见下方实现，勿回退成 delay)。
                        // 2026-09-19 二修：不再用 wasTextFieldFocused 限定"仅首次聚焦"——观测日志实证
                        // 浏览(失焦)滚动后重新点按聚焦时，reveal 同样会误判拉外层(实例 v=8256→5655 无
                        // 光标跟随参与)，只守首次聚焦盖不住。改为每次焦点 gain 都对齐，re-focus 同样
                        // 被护住；已聚焦态下打字移动光标无 focus 事件，不受影响。
                        if (fs.isFocused) {
                            focusScope.launch {
                                // 抢在聚焦 reveal 判定前，第一时间把外层滚动对齐到点按前的位置(lastDownV)：
                                // 原 delay(48) 拼时序在主线程忙(键盘实时监听)时输给 reveal(观测：reveal 落在
                                // focus+150ms、对齐却晚它 3ms → 先被拉走再拉回 → 末页回弹)。立即复位后，
                                // reveal 判定光标已在可视区 → no-op，不再拉外层。
                                scrollState.scrollTo(lastDownV.coerceIn(0, scrollState.maxValue))
                                // reveal 仍可能在首帧后按过期 offset=0 判定补拉一次外层；等一帧后再
                                // reconcile 到同一点按位置，即便内部被拉过也归位成同值，用户无感。
                                withFrameNanos { }
                                scrollState.scrollTo(lastDownV.coerceIn(0, scrollState.maxValue))
                            }
                        }
                    }
                    // 【X-H6 B′ 根治·长按前锚定落点】(2026-09-05 真机定案)
                    //
                    // 背景：BasicTextField 旧 API 在"折叠逻辑光标停在不随视口的幽灵 offset 0"时
                    // 直接长按框选，内部 reveal 会用该幽灵位置把视口滚回文章顶部(跳顶，Compose 官方
                    // 已知缺陷 #235693496 / CMP #4014)。用户实证"先点一下让光标落中段再长按就不跳"，
                    // 本逻辑即自动化的"先点一下"。
                    //
                    // 真正根因(非幽灵值本身)：pointerInput(Unit) 的 suspend 块只随 key=Unit 变化重启、
                    // 不随重组更新，直接捕获 textFieldValue 参数会拿到【首次组合】时的陈旧空文本，
                    // 使下方 text.isEmpty() 恒真、落点永不执行 → 长按仍走幽灵 offset0 → 跳顶。
                    // 用 rememberUpdatedState 的 currentTextFieldValue 读最新值后根治(见下方 pointerInput)。
                    //
                    // 做法：任何 down 落在文本区时，若当前是【折叠光标】且未停在该 down 的字符处，
                    // 先把逻辑光标设到 down 的字符 offset(就近)。TextField 随后长按 reveal 便基于
                    // 就近位置，不再从 offset 0 远跳顶。
                    //
                    // 关键保证：
                    //  1) awaitFirstDown(requireUnconsumed=false)【只观察不消费】，不破坏 TextField
                    //     自身的 tap/双击/长按/拖选手势；
                    //  2) 仅折叠态(collapsed)才干预，避免打断正在进行的框选拖动(非折叠)。
                    //  3) setSelection 触发的"光标跟随"会检查新光标(即 down 点，必在视口内，
                    //     因用户看得见才长按)→ 不触发 scrollTo，无副作用。
                    //
                    // 【驻留判定】(2026-09-17)：落点前置一个"按住"判定——手指按下后在窗口期
                    // (120ms)内未超出 touch slop 位移，才视为「按住」(长按/慢点按)并就近落点；
                    // 拖动滚动(手指快速位移)或快速点按则不落点。目的：修掉原实现"任何 down 都
                    // 落点"导致【触摸拖动滚动时光标被吸附到手指处 + selection 变更每次都整篇
                    // 重组 + cursor-follow(以 selection 为 key)重跑】的卡顿与误落点。120ms ≪
                    // 系统长按阈值(约 500ms)，长按前锚定不受影响；快速点按由 TextField 原生处理，
                    // 此处跳过无副作用。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPos = down.position
                            val slop = viewConfiguration.touchSlop
                            // 记录本次 down 时外层滚动量（供首获聚焦对齐用），无论快按/按住/拖动都记；
                            // 点按聚焦场景即这段时间的滚动位置。
                            lastDownV = scrollState.value
                            // 超时(120ms 内未位移超 slop → null)视为「按住」；true=位移/抬起
                            val movedOrReleased = withTimeoutOrNull(120L) {
                                var moved = false
                                while (!moved) {
                                    val change = awaitPointerEvent()
                                        .changes.firstOrNull { it.id == down.id } ?: continue
                                    if (change.pressed &&
                                        (change.position - startPos).getDistance() > slop
                                    ) {
                                        moved = true // 手指明显位移 → 拖动/滚动
                                    } else if (!change.pressed) {
                                        moved = true // 快速抬起(点按) → 原生处理
                                    }
                                }
                                moved
                            } ?: false
                            if (movedOrReleased) return@awaitEachGesture
                            // 按住：就近落点（用 rememberUpdatedState 的 currentTextFieldValue 而非
                            // 裸 textFieldValue，见上方 X-H6 说明）。
                            val layout = layoutResult
                            val text = currentTextFieldValue.text
                            val sel = currentTextFieldValue.selection
                            if (layout != null && text.isNotEmpty() && sel.collapsed) {
                                val off = layout.getOffsetForPosition(down.position)
                                    .coerceIn(0, text.length)
                                if (off != sel.start) {
                                    editTextFieldValueChange(
                                        currentTextFieldValue.copy(selection = TextRange(off))
                                    )
                                }
                            }
                            // 等待手势结束再处理下一个 down，避免 up 状态残留
                            waitForUpOrCancellation()
                        }
                    },
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
                        if (textFieldValue.text.isEmpty() && placeholderText != null) {
                            Text(
                                text = placeholderText.orEmpty(),
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
        // 键盘「显示→收起」跃迁时清除光标，真正失焦（≠仅隐藏），进轻浏览态。
        // 用户 2026-09-19 定案：触发源只认「软键盘从显示→收起」（不动点外部焦点逻辑）；
        // 收起后仅清光标、编辑工具栏（撤销/重做/搜索）保留，不整个退回浏览态。
        // 初始键盘即收起(keyboardOffsetPx=0)不做任何事；仅当曾在键盘开启(>0，含拼音⇄手写
        // 高度切换，全程 >0 不触发)后收起(=0)才 clearFocus。clearFocus 令 BasicTextField 真正失焦、
        // 光标消失，进入轻浏览态。返回键分层第三层（isEditorFocused）已于 2026-09-19 移除：
        // 键盘收起的清光标已由本 effect 统一接管，正常退出不再要求"先返回一次清光标"。
        val focusManager = LocalFocusManager.current
        var keyboardWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(keyboardOffsetPx) {
            if (keyboardOffsetPx > 0) {
                keyboardWasOpen = true
            } else if (keyboardWasOpen) {
                keyboardWasOpen = false
                focusManager.clearFocus()
            }
        }
        // 键盘开启时，把"安全可见底边"上移 keyboardOffsetPx，光标跟随将光标所在行抬到键盘上方，
        // 既不被键盘遮挡，也不压缩内容区（压缩会让内容空白）。
        // keyboardOffsetPx 纳入 key：键盘 settle 后（parent 已写入键盘高度）触发一次重新定位。
        LaunchedEffect(textFieldValue.selection, scrollState.maxValue, keyboardOffsetPx) {
            if (searchQuery.isNotEmpty()) return@LaunchedEffect
            if (suppressCursorFollowUntilEdit) return@LaunchedEffect
            if (skipCursorFollow) {
                skipCursorFollow = false
                return@LaunchedEffect
            }
            val layout = layoutResult ?: return@LaunchedEffect
            val selection = textFieldValue.selection
            val viewport = scrollState.viewportSize
            val safeBottom = (viewport - keyboardOffsetPx).coerceAtLeast(0)
            if (selection.collapsed && viewport > 0) {
                val cursorRect = layout.getCursorRect(selection.start)
                val viewportTop = scrollState.value.toFloat()
                if (cursorRect.top < viewportTop || cursorRect.bottom > viewportTop + safeBottom) {
                    // 光标在视口上方 → 滚动到光标行再留 1/3 视口；
                    // 光标被键盘遮（在安全底边之下）→ 将其底边上移到安全底边再留一点空隙
                    val target = if (cursorRect.top < viewportTop) {
                        (cursorRect.top - viewport / 3f)
                    } else {
                        (cursorRect.bottom - safeBottom + safeBottom * 0.1f)
                    }
                    scrollState.scrollTo(
                        target.coerceIn(0f, scrollState.maxValue.toFloat()).toInt()
                    )
                }
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
            type = resolveShareMimeType(fileName)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) {
        null
    }
}
