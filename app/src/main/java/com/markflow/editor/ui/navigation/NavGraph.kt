package com.markflow.editor.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.markflow.editor.domain.model.ThemeMode
import com.markflow.editor.ui.screens.editor.EditorScreen
import com.markflow.editor.ui.screens.filelist.FileListScreen

/**
 * MarkFlow 主导航图
 * 目前仅包含两个页面：文件列表 → 编辑器
 *
 * @param navController 导航控制器
 * @param initialEditorUri 外部传入的文件 URI（跨应用打开时），为 null 则显示文件列表
 * @param onEditorUriConsumed URI 消费回调：导航到编辑器后立即调用，将 URI 置 null
 * @param themeMode 当前主题模式
 * @param onThemeToggle 主题切换回调（点击 MarkFlow 标题时触发）
 */
@Composable
fun MarkFlowNavGraph(
    navController: NavHostController,
    initialEditorUri: String? = null,
    onEditorUriConsumed: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.LIGHT,
    onThemeToggle: () -> Unit = {}
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
                    onEditorUriConsumed()
                    navController.navigate(Screen.Editor.createRoute(initialEditorUri))
                }
            }
            FileListScreen(
                onNavigateToEditor = { fileUri ->
                    navController.navigate(Screen.Editor.createRoute(fileUri))
                },
                onThemeToggle = onThemeToggle
            )
        }

        // 编辑器页面
        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("fileUri") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val rawUri = backStackEntry.arguments?.getString("fileUri") ?: ""
            val fileUri = try {
                java.net.URLDecoder.decode(rawUri, "UTF-8")
            } catch (_: Exception) {
                rawUri
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