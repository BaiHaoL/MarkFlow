package com.markflow.editor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.domain.model.ThemeMode
import com.markflow.editor.ui.navigation.MarkFlowNavGraph
import com.markflow.editor.ui.theme.MarkFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MarkFlow 主 Activity
 * 使用 Jetpack Compose 作为 UI 框架
 * 通过 @AndroidEntryPoint 启用 Hilt 注入
 *
 * 支持跨应用打开 .md 文件：
 * - ACTION_VIEW / ACTION_EDIT → 直接进入编辑器
 * - ACTION_SEND → 将分享的文本保存为文件后进入编辑器
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    companion object {
        private const val TAG = "MainActivity"
    }

    /**
     * 待处理的跨应用文件 URI，使用 Compose State 以便在 onNewIntent 中更新
     * 通过 onEditorUriConsumed 回调消费后置 null，防止返回文件列表时重新触发导航
     */
    private val pendingExternalUri = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 部分OEM ROM（小米/华为/OPPO等）的edge-to-edge实现可能抛异常，try-catch兜底
        try {
            enableEdgeToEdge()
        } catch (_: Exception) {
            Log.w(TAG, "enableEdgeToEdge failed, falling back to default")
        }

        // 提取首次启动时的外部文件 URI
        val initialUri = extractFileUriFromIntent(intent)?.also {
            intent.data = null
            intent.action = null
        }
        pendingExternalUri.value = initialUri

        setContent {
            // 主题模式状态：从持久化存储恢复，点击标题切换并保存
            var themeMode by remember { mutableStateOf(preferencesManager.getThemeMode()) }
            val externalFileUri by pendingExternalUri
            // 同步窗口背景色与主题，避免 Compose 渲染间隙暴露浅色窗口背景
            androidx.compose.runtime.SideEffect {
                val isDark = themeMode == ThemeMode.DARK
                val windowBg = if (isDark)
                    android.graphics.Color.parseColor("#202124")
                else
                    android.graphics.Color.parseColor("#FAFAFA")
                window.decorView.setBackgroundColor(windowBg)
            }

            MarkFlowTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    MarkFlowNavGraph(
                        navController = navController,
                        initialEditorUri = externalFileUri,
                        onEditorUriConsumed = { pendingExternalUri.value = null },
                        themeMode = themeMode,
                        onThemeToggle = {
                            val newMode = if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
                            preferencesManager.setThemeMode(newMode)
                            themeMode = newMode
                        }
                    )
                }
            }
        }
    }

    /**
     * 从外部 Intent 中提取文件 URI
     * 支持 ACTION_VIEW、ACTION_EDIT 和 ACTION_SEND
     *
     * 关键修复：微信等应用发送的 content:// URI 可能无法直接读取，
     * 因此先将文件内容复制到应用内部存储，返回本地文件路径。
     */
    private fun extractFileUriFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                // 文件管理器打开文件：data 就是文件 URI
                intent.data?.let { uri ->
                    // 如果是 content:// 协议，复制到应用内部存储
                    if (uri.scheme == "content") {
                        copyContentUriToLocal(uri)
                    } else {
                        uri.toString()
                    }
                }
            }
            Intent.ACTION_SEND -> {
                // 其他应用分享文本：提取文本内容，保存为临时文件
                if (intent.type == "text/plain") {
                    handleSharedText(intent)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * 将 content:// URI 的内容复制到应用内部存储
     *
     * 解决问题：微信等应用发送的文件 URI 是临时的 content:// 链接，
     * 直接传递给编辑器可能因权限过期而无法读取。
     * 这里先将内容复制到 app 私有目录，确保后续可稳定访问。
     */
    private fun copyContentUriToLocal(uri: android.net.Uri): String? {
        return try {
            val fileName = getFileNameFromUri(uri)
                ?: guessFileNameFromUri(uri)
                ?: "imported_${System.currentTimeMillis()}.md"
            val destDir = java.io.File(filesDir, "imported")
            destDir.mkdirs()
            val destFile = java.io.File(destDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            destFile.toURI().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy external file", e)
            null
        }
    }

    /**
     * 从 content:// URI 中获取原始文件名
     */
    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        } catch (_: Exception) { }
        return name
    }

    /**
     * 从 URI 路径中猜测文件名（当 ContentProvider 不返回 DISPLAY_NAME 时兜底）
     * 例如：content://com.android.externalstorage.documents/document/primary:Documents/data.toml
     */
    private fun guessFileNameFromUri(uri: android.net.Uri): String? {
        return try {
            val path = uri.path ?: uri.toString()
            // 尝试从路径中提取最后一个 / 之后的部分
            val lastSegment = path.substringAfterLast('/')
            if (lastSegment.isNotBlank() && lastSegment.contains('.')) {
                // 处理 primary:Documents/data.toml 这种格式，取冒号后的部分
                val afterColon = lastSegment.substringAfterLast(':')
                if (afterColon.isNotBlank()) afterColon else lastSegment
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 处理分享的文本：保存为临时文件并返回 URI
     */
    private fun handleSharedText(intent: Intent): String? {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val sharedTitle = intent.getStringExtra(Intent.EXTRA_TITLE) ?: "分享内容"

        return try {
            val fileName = "$sharedTitle.md"
            val file = java.io.File(filesDir, "shared/$fileName")
            file.parentFile?.mkdirs()
            file.writeText(sharedText)
            file.toURI().toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 单任务模式下，当应用已在前台时收到新 Intent 时调用
     * 不再使用 recreate()，而是直接更新 Compose State，触发导航重组
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = extractFileUriFromIntent(intent)
        if (uri != null) {
            pendingExternalUri.value = uri
        }
    }
}