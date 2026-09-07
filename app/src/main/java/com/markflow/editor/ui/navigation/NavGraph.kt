package com.markflow.editor.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.markflow.editor.domain.model.ThemeMode
import com.markflow.editor.ui.screens.editor.EditorScreen
import com.markflow.editor.ui.screens.filelist.FileListScreen

/**
 * MarkFlow 主导航图
 * 目前仅包含两个页面：文件列表 → 编辑器
 *
 * 编辑器目标 URI 通过 savedStateHandle 传递（不经路由参数编解码）：
 * 列表页（或外部 URI 入口）将 URI 写入当前 backStackEntry 的
 * savedStateHandle[Screen.EDITOR_FILE_URI_KEY]，然后 navigate 到编辑器；
 * 编辑器从 previousBackStackEntry 读取。字符串原样往返，
 * 含 #、+、%、空格、中文等任意字符的文件名均安全。
 *
 * @param navController 导航控制器
 * @param initialEditorUri 外部传入的文件 URI（跨应用打开时），为 null 则显示文件列表
 * @param onEditorUriConsumed URI 消费回调：导航到编辑器后立即调用，将 URI 置 null
 * @param themeMode 当前主题模式
 * @param onThemeToggle 主题切换回调（点击 MarkFlow 标题时触发）
 * @param onFileOpened 文件打开记录回调（所有进入编辑器的路径统一在此上报「最近打开」）
 */
@Composable
fun MarkFlowNavGraph(
    navController: NavHostController,
    initialEditorUri: String? = null,
    onEditorUriConsumed: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.LIGHT,
    onThemeToggle: () -> Unit = {},
    onFileOpened: (String) -> Unit = {}
) {
    // 使用 remember 确保 isDarkTheme 仅在实际依赖值变化时才重新计算
    val isDarkTheme = remember(themeMode) {
        themeMode == ThemeMode.DARK
    }

    NavHost(
        navController = navController,
        startDestination = Screen.FileList.route,
        // 禁用所有页面切换动画 — 文件切换应与主题即时响应一致
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        // 文件列表首页
        composable(Screen.FileList.route) {
            LaunchedEffect(initialEditorUri) {
                if (initialEditorUri != null) {
                    // URI 写入列表页 entry 的 savedStateHandle 后跳转编辑器，
                    // 编辑器经 previousBackStackEntry 原样读取
                    navController.currentBackStackEntry?.savedStateHandle
                        ?.set(Screen.EDITOR_FILE_URI_KEY, initialEditorUri)
                    onFileOpened(initialEditorUri)
                    onEditorUriConsumed()
                    navController.navigate(Screen.Editor.route)
                }
            }
            FileListScreen(
                onNavigateToEditor = { fileUri ->
                    onFileOpened(fileUri)
                    navController.currentBackStackEntry?.savedStateHandle
                        ?.set(Screen.EDITOR_FILE_URI_KEY, fileUri)
                    navController.navigate(Screen.Editor.route)
                },
                onThemeToggle = onThemeToggle
            )
        }

        // 编辑器页面：从列表页 savedStateHandle 读取目标 URI（原样字符串）
        composable(Screen.Editor.route) { backStackEntry ->
            val fileUri = remember(backStackEntry) {
                navController.previousBackStackEntry?.savedStateHandle
                    ?.get<String>(Screen.EDITOR_FILE_URI_KEY) ?: ""
            }
            EditorScreen(
                fileUri = fileUri,
                isDarkTheme = isDarkTheme,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
