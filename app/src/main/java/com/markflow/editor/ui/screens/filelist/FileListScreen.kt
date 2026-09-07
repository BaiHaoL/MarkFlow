package com.markflow.editor.ui.screens.filelist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markflow.editor.domain.model.FileType
import com.markflow.editor.domain.model.MarkdownFile
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.ui.components.BottomActionBar
import com.markflow.editor.util.resolveShareMimeType
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** 新建文件对话框后缀选择框/下拉面板的固定宽度，需容纳最长后缀（如 .yaml）+ 箭头图标 */
private val SUFFIX_SELECT_WIDTH = 106.dp

/**
 * 文件列表首页
 *
 * 交互逻辑：
 * - 单击文件 → 直接进入编辑器
 * - 长按文件 → 进入多选模式
 * - 点击标题 MarkFlow → 切换深色/浅色主题
 *
 * @param onNavigateToEditor 点击文件进入编辑器的回调
 * @param onThemeToggle 主题切换回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    onNavigateToEditor: (String) -> Unit,
    onThemeToggle: () -> Unit = {},
    viewModel: FileListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // 新建文件成功后自动跳转到编辑器
    LaunchedEffect(uiState.navigateToEditorUri) {
        uiState.navigateToEditorUri?.let { uri ->
            onNavigateToEditor(uri)
            viewModel.onNavigateToEditorHandled()
        }
    }

    // 从编辑器返回时无感刷新文件列表（不显示加载指示器，不阻塞 UI）
    LaunchedEffect(Unit) {
        viewModel.silentRefresh()
    }

    // 错误消息通过 SnackbarHost 显示
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    // Android 11+ 非本应用文件操作授权
    val securityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSecurityConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(uiState.pendingSecurityRequest) {
        uiState.pendingSecurityRequest?.let { request ->
            try {
                securityLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            } catch (e: Exception) {
                viewModel.dismissSecurityRequest()
            }
        }
    }

    // 新建文件对话框状态
    var newFileName by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedFileExt by remember { mutableStateOf(".md") }
    val fileExtOptions = listOf(
        ".md", ".txt", ".log", ".cpp", ".c", ".h", ".hpp",
        ".py", ".java", ".kt", ".js", ".ts", ".json", ".html",
        ".css", ".xml", ".yml", ".yaml", ".ini", ".sql", ".sh"
    )

    // 文件选择器：打开手机里的文档
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFile(it) }
    }

    // 多选模式下拦截返回键：退出多选而非退出应用
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    // 搜索状态（搜索框有焦点或有关键词）下拦截返回键：退出搜索而非退出应用
    val searchFocusManager = LocalFocusManager.current
    var isSearchFocused by remember { mutableStateOf(false) }
    val isSearchActive = isSearchFocused || uiState.searchQuery.isNotEmpty()
    BackHandler(enabled = isSearchActive) {
        if (uiState.searchQuery.isNotEmpty()) {
            viewModel.updateSearchQuery("")
        }
        searchFocusManager.clearFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                // 选择模式 TopAppBar：显示已选数量 + 全选 + 退出
                SelectionTopBar(
                    selectedCount = uiState.selectedFiles.size,
                    totalCount = uiState.files.size,
                    onSelectAll = { viewModel.toggleSelectAll() },
                    onExit = { viewModel.exitSelectionMode() }
                )
            } else {
                // 正常模式 TopAppBar
                TopAppBar(
                    title = {
                        Text(
                            text = "MarkFlow",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clickable { onThemeToggle() }
                        )
                    },
                    actions = {
                        // 打开文件
                        IconButton(onClick = {
                            openFileLauncher.launch(
                                arrayOf("text/*", "application/octet-stream")
                            )
                        }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "打开文件")
                        }
                        // 排序菜单
                        SortMenu(
                            currentSortMode = uiState.sortMode,
                            onSortSelected = { viewModel.toggleSortMode(it) }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            // 底部操作栏：仅在多选模式下显示（瞬时显示，无动画）
            if (uiState.isSelectionMode) {
                BottomActionBar(
                    selectedCount = uiState.selectedFiles.size,
                    onDelete = { viewModel.showDeleteConfirm() },
                    onShare = {
                        val selected = uiState.selectedFiles
                        if (selected.isEmpty()) return@BottomActionBar

                        val selectedFiles = uiState.files.filter { it.uri in selected }
                        val shareUris = ArrayList<Uri>().apply {
                            selectedFiles.forEach { add(resolveShareUri(context, it)) }
                        }

                        val shareIntent = if (selectedFiles.size == 1) {
                            Intent(Intent.ACTION_SEND).apply {
                                type = resolveShareMimeType(selectedFiles.first().fileName)
                                putExtra(Intent.EXTRA_SUBJECT, selectedFiles.first().fileName)
                                putExtra(Intent.EXTRA_STREAM, shareUris.first())
                            }
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                            }
                        }
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(shareIntent, "分享文件"))
                    },
                    onRename = {
                        // 仅单选时可重命名
                        val selected = uiState.selectedFiles
                        if (selected.size == 1) {
                            uiState.files.find { it.uri == selected.first() }
                                ?.let { viewModel.showRenameDialog(it) }
                        }
                    },
                    onDetails = {
                        // 仅单选时可查看详情
                        val selected = uiState.selectedFiles
                        if (selected.size == 1) {
                            uiState.files.find { it.uri == selected.first() }
                                ?.let { viewModel.showFileDetails(it) }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            // FAB 新建按钮：选择模式下隐藏（瞬时显示，无动画）
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    // edge-to-edge 下避让底部导航栏，防止三键导航压到按钮下沿
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建文件"
                    )
                }
            }
        }
    ) { paddingValues ->
        // 内容区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading && uiState.files.isEmpty()) {
                // 加载中
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // 统一下拉刷新：空列表和有文件均支持
                val pullRefreshState = rememberPullToRefreshState(
                    positionalThreshold = 50.dp
                )
                if (pullRefreshState.isRefreshing) {
                    LaunchedEffect(true) {
                        viewModel.refreshFiles()
                        delay(500)
                        pullRefreshState.endRefresh()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(pullRefreshState.nestedScrollConnection)
                ) {
                    if (uiState.files.isEmpty()) {
                        // 空状态：可滚动以支持下拉手势
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 400.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyState()
                            }
                        }
                    } else {
                        // 正常文件列表
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // 搜索栏（固定，不参与下拉刷新）
                            FileSearchBar(
                                query = uiState.searchQuery,
                                onQueryChange = { viewModel.updateSearchQuery(it) },
                                onFocusChange = { isSearchFocused = it }
                            )

                            // 根据搜索关键词过滤文件
                            val query = uiState.searchQuery
                            val filteredRecent = remember(uiState.recentFiles, query) {
                                if (query.isBlank()) uiState.recentFiles
                                else uiState.recentFiles.filter {
                                    it.fileName.contains(query, ignoreCase = true)
                                }
                            }
                            val filteredMd = remember(uiState.mdFiles, query) {
                                if (query.isBlank()) uiState.mdFiles
                                else uiState.mdFiles.filter {
                                    it.fileName.contains(query, ignoreCase = true)
                                }
                            }
                            val filteredOther = remember(uiState.otherFiles, query) {
                                if (query.isBlank()) uiState.otherFiles
                                else uiState.otherFiles.filter {
                                    it.fileName.contains(query, ignoreCase = true)
                                }
                            }

                            // 文件列表区域
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            focusManager.clearFocus()
                                        }
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = 8.dp,
                                            end = 8.dp,
                                            top = 8.dp,
                                            bottom = 80.dp
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // "最近打开" 分区
                                        if (filteredRecent.isNotEmpty()) {
                                            item(key = "section_recent") {
                                                SectionHeader(title = "最近打开")
                                            }
                                            items(
                                                items = filteredRecent,
                                                key = { "recent_${it.uri}" }
                                            ) { file ->
                                                FileListItem(
                                                    file = file,
                                                    isSelected = uiState.selectedFiles.contains(file.uri),
                                                    isSelectionMode = uiState.isSelectionMode,
                                                    onClick = {
                                                        if (uiState.isSelectionMode) {
                                                            viewModel.toggleFileSelection(file.uri)
                                                        } else {
                                                            onNavigateToEditor(file.uri)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!uiState.isSelectionMode) {
                                                            viewModel.enterSelectionMode(file.uri)
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                        // "Markdown" 分区
                                        if (filteredMd.isNotEmpty()) {
                                            item(key = "section_md") {
                                                SectionHeader(title = "Markdown")
                                            }
                                            items(
                                                items = filteredMd,
                                                key = { "md_${it.uri}" }
                                            ) { file ->
                                                FileListItem(
                                                    file = file,
                                                    isSelected = uiState.selectedFiles.contains(file.uri),
                                                    isSelectionMode = uiState.isSelectionMode,
                                                    onClick = {
                                                        if (uiState.isSelectionMode) {
                                                            viewModel.toggleFileSelection(file.uri)
                                                        } else {
                                                            onNavigateToEditor(file.uri)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!uiState.isSelectionMode) {
                                                            viewModel.enterSelectionMode(file.uri)
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                        // "其他" 分区（非 Markdown 文本文件）
                                        if (filteredOther.isNotEmpty()) {
                                            item(key = "section_other") {
                                                SectionHeader(title = "其他")
                                            }
                                            items(
                                                items = filteredOther,
                                                key = { "other_${it.uri}" }
                                            ) { file ->
                                                FileListItem(
                                                    file = file,
                                                    isSelected = uiState.selectedFiles.contains(file.uri),
                                                    isSelectionMode = uiState.isSelectionMode,
                                                    onClick = {
                                                        if (uiState.isSelectionMode) {
                                                            viewModel.toggleFileSelection(file.uri)
                                                        } else {
                                                            onNavigateToEditor(file.uri)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!uiState.isSelectionMode) {
                                                            viewModel.enterSelectionMode(file.uri)
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                        // 搜索无结果提示
                                        if (query.isNotBlank() && filteredRecent.isEmpty() && filteredMd.isEmpty() && filteredOther.isEmpty()) {
                                            item(key = "search_no_result") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(32.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "未找到匹配 \"$query\" 的文件",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    PullToRefreshContainer(
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    // ==================== 对话框 ====================

    // 新建文件命名对话框
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建文件") },
            text = {
                Column {
                    var suffixExpanded by remember { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFileName,
                            onValueChange = { newFileName = it },
                            label = { Text("名称") },
                            placeholder = { Text("文件名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        // 后缀框：与名称栏同为 OutlinedTextField（readOnly）保证等高，点击经 menuAnchor 展开
                        ExposedDropdownMenuBox(
                            expanded = suffixExpanded,
                            onExpandedChange = { suffixExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedFileExt,
                                onValueChange = {},
                                readOnly = true,
                                // 后缀 label 常驻显示；聚焦变色由 M3 原生机制接管（与"名称"框一致）
                                label = { Text("后缀") },
                                textStyle = MaterialTheme.typography.bodyLarge,
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "选择后缀",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .width(SUFFIX_SELECT_WIDTH)
                                    .menuAnchor()
                            )
                        }
                    }
                    // 贴底面板：顶部紧贴后缀框下方，限高纵向滚动
                    if (suffixExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .align(Alignment.End)
                                .width(SUFFIX_SELECT_WIDTH)
                                .heightIn(max = 180.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp)
                        ) {
                            fileExtOptions.forEach { ext ->
                                    val selected = selectedFileExt == ext
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                selectedFileExt = ext
                                                suffixExpanded = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 10.dp)
                                    ) {
                                        if (selected) {
                                            Text(
                                                text = "✓ ",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = ext,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFileName.ifBlank { "未命名" }
                        val fileName = if (name.endsWith(selectedFileExt, ignoreCase = true)) name else name + selectedFileExt
                        viewModel.createNewFile(fileName)
                        showCreateDialog = false
                        newFileName = ""
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    newFileName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // 重命名对话框 — 使用局部状态，对话框关闭时自动销毁，避免跨实例污染
    if (uiState.showRenameDialog && uiState.renameTargetFile != null) {
        val target = uiState.renameTargetFile!!
        var dialogText by remember { mutableStateOf(target.fileName) }
        var validationError by remember { mutableStateOf<String?>(null) }
        val trimmed = dialogText.trim()

        AlertDialog(
            onDismissRequest = {
                if (!uiState.isRenaming) viewModel.dismissRenameDialog()
            },
            title = { Text("重命名文件") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dialogText,
                        onValueChange = {
                            dialogText = it
                            validationError = null
                        },
                        label = { Text("新文件名") },
                        singleLine = true,
                        isError = validationError != null,
                        supportingText = validationError?.let { err ->
                            { Text(err, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isRenaming
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 前端基础校验
                        when {
                            trimmed.isEmpty() -> {
                                validationError = "文件名不能为空"
                            }
                            trimmed.any { it in setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') } -> {
                                validationError = "文件名包含非法字符"
                            }
                            trimmed == "." || trimmed.all { it == '.' } -> {
                                validationError = "文件名不能仅由点号组成"
                            }
                            trimmed.endsWith('.') -> {
                                validationError = "文件名不能以点号结尾"
                            }
                            else -> {
                                validationError = null
                                viewModel.renameFile(trimmed)
                            }
                        }
                    },
                    enabled = !uiState.isRenaming
                ) {
                    if (uiState.isRenaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissRenameDialog() },
                    enabled = !uiState.isRenaming
                ) {
                    Text("取消")
                }
            }
        )
    }

    // 后缀变化确认对话框（方案2：可改后缀，变化时先弹确认，类似文件管理器）
    if (uiState.showRenameConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRenameConfirm() },
            title = { Text("更改扩展名") },
            text = {
                Text("更改文件扩展名可能导致文件无法正常打开。\n若改为不受支持的后缀，文件可能不会显示在列表中。确定要继续吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRename() },
                    enabled = !uiState.isRenaming
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissRenameConfirm() },
                    enabled = !uiState.isRenaming
                ) {
                    Text("取消")
                }
            }
        )
    }

    // 删除确认对话框
    if (uiState.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text("确认删除") },
            text = {
                Column {
                    Text("确定要将选中的 ${uiState.selectedFiles.size} 个文件从列表中移除吗？")
                    Text(
                        text = if (uiState.deleteWithFile) {
                            "将同时删除设备上的文件，此操作不可恢复。"
                        } else {
                            "未勾选时仅从列表移除，文件本身保留。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { viewModel.setDeleteWithFile(!uiState.deleteWithFile) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.deleteWithFile,
                            onCheckedChange = { viewModel.setDeleteWithFile(it) }
                        )
                        Text(
                            text = "同时删除对应文件",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteSelectedFiles() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text("取消")
                }
            }
        )
    }

    // 文件详情对话框
    if (uiState.showFileDetailsDialog && uiState.detailTargetFile != null) {
        val file = uiState.detailTargetFile!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissFileDetails() },
            title = { Text("文件详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("文件名", file.fileName)
                    DetailRow("文件大小", file.formattedSize())
                    DetailRow(
                        "修改时间",
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date(file.lastModified))
                    )
                    if (file.filePath.isNotEmpty()) {
                        DetailRow("完整路径", file.filePath)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissFileDetails() }) {
                    Text("关闭")
                }
            }
        )
    }

}

// ==================== 子组件 ====================

/**
 * 选择模式 TopAppBar
 * 显示已选数量、全选按钮、退出按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onExit: () -> Unit
) {
    TopAppBar(
        title = {
            Text("已选 $selectedCount 项")
        },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, contentDescription = "退出选择")
            }
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text(if (selectedCount == totalCount) "取消全选" else "全选")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/**
 * 排序菜单
 */
@Composable
private fun SortMenu(
    currentSortMode: SortMode,
    onSortSelected: (SortMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "排序选项"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(mode.displayName)
                            if (mode == currentSortMode) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        if (mode != currentSortMode) {
                            onSortSelected(mode)
                        }
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 段落标题
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/**
 * 文件列表项
 *
 * 单击 → 进入编辑器（正常模式）/ 切换选中（选择模式）
 * 长按 → 进入多选模式
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: MarkdownFile,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    // 大文件判定（>512KB 的文本文件，含 md，统一阈值）→ 显示"大"角标。
    // 与 EditorViewModel.loadFile 的 isPaged 判定一致，保证列表"大"标记
    // 与实际打开行为（分页只读浏览 + 分段编辑）对齐。
    val isLargeFile = remember(file.fileName, file.fileSize) {
        file.fileSize > FileType.LARGE_FILE_THRESHOLD_BYTES
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选择模式下显示复选框
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            // 文件图标
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 12.dp)
            )

            // 文件信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLargeFile) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "大",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = dateFormat.format(Date(file.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = file.formattedSize(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 空状态提示
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = "暂无 Markdown 文件",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "点击右下角 + 按钮新建",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * 详情行
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // SelectionContainer 使详情值可长按选中复制（文件名/大小/时间/路径）
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 文件搜索栏
 * 在文件列表顶部提供文件名搜索过滤功能
 */
@Composable
private fun FileSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onFocusChanged { onFocusChange(it.isFocused) },
        placeholder = { Text("搜索文件…") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

/**
 * 将文件 URI 解析为可用于分享的 content URI。
 *
 * - MediaStore 的 content:// URI → 直接使用
 * - file:// URI 或 filePath → 通过 FileProvider 转换为 content URI
 */
private fun resolveShareUri(context: android.content.Context, file: MarkdownFile): Uri {
    val uri = Uri.parse(file.uri)
    // 已经是 content URI（MediaStore / SAF），直接分享
    if (uri.scheme == "content") {
        return uri
    }
    // file:// URI 或物理路径，通过 FileProvider 获取 content URI
    val fileObj = if (file.filePath.isNotEmpty()) {
        File(file.filePath)
    } else {
        File(uri.path ?: return uri)
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        fileObj
    )
}