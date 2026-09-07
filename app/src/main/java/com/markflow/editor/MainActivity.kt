package com.markflow.editor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.markflow.editor.data.local.PreferencesManager
import com.markflow.editor.data.repository.FileRepository
import com.markflow.editor.domain.model.FileType
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

    @Inject
    lateinit var fileRepository: FileRepository

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
                        },
                        onFileOpened = { uri -> fileRepository.notifyFileOpened(uri) }
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
                val uri = intent.data
                // 源头校验：未注册扩展名 / 无扩展名的文件禁止打开（从源头阻止）
                if (uri != null && isUnsupportedFile(uri)) {
                    showUnsupportedFileToast(uri)
                    return null
                }
                uri?.let {
                    // 如果是 content:// 协议，复制到应用内部存储
                    if (it.scheme == "content") copyContentUriToLocal(it) else it.toString()
                }
            }
            Intent.ACTION_SEND -> {
                // 优先处理文件分享（EXTRA_STREAM 携带 content:// URI），
                // 回退到纯文本分享（EXTRA_TEXT 携带文本内容）
                val streamUri = getStreamUriFromIntent(intent)
                if (streamUri != null) {
                    if (isUnsupportedFile(streamUri)) {
                        showUnsupportedFileToast(streamUri)
                        return null
                    }
                    copyContentUriToLocal(streamUri)
                } else {
                    handleSharedText(intent)
                }
            }
            else -> null
        }
    }

    /**
     * 判断文件 URI 是否"不支持打开"（未注册扩展名或无扩展名），应拒绝。
     *
     * 无法获取文件名（某些 content provider 不返回 DISPLAY_NAME 且路径不含扩展名）时
     * 返回 false 放行——交给编辑器兜底（copyContentUriToLocal 会补默认 .md 名，
     * 且大文件仍受 loadFile 的分页保护）。
     */
    private fun isUnsupportedFile(uri: android.net.Uri): Boolean {
        val name = resolveFileName(uri) ?: return false
        return !FileType.isSupported(name)
    }

    /** 从 URI 解析文件名：DISPLAY_NAME 优先，路径猜测兜底，最后取 lastPathSegment */
    private fun resolveFileName(uri: android.net.Uri): String? {
        getFileNameFromUri(uri)?.let { return it }
        guessFileNameFromUri(uri)?.let { return it }
        return uri.lastPathSegment?.takeIf { it.contains('.') }
    }

    private fun showUnsupportedFileToast(uri: android.net.Uri) {
        val name = resolveFileName(uri) ?: "该文件"
        Toast.makeText(this, "不支持的文件类型：$name", Toast.LENGTH_SHORT).show()
    }

    /**
     * 从 ACTION_SEND 的 Intent 中提取 EXTRA_STREAM（文件分享的 content:// URI）。
     *
     * API 33+ 需要类型化 getParcelableExtra，旧版本用非类型化版本。
     */
    @Suppress("DEPRECATION")
    private fun getStreamUriFromIntent(intent: Intent): android.net.Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    /**
     * 将 content:// URI 的内容复制到应用内部存储
     *
     * 解决问题：微信等应用发送的文件 URI 是临时的 content:// 链接，
     * 直接传递给编辑器可能因权限过期而无法读取。
     * 这里先将内容复制到 app 私有目录，确保后续可稳定访问。
     *
     * 外部传入的文件名会先经过 [sanitizeExternalFileName] 净化，防止路径穿越。
     */
    private fun copyContentUriToLocal(uri: android.net.Uri): String? {
        return try {
            val destDir = java.io.File(filesDir, "imported").apply { mkdirs() }
            val rawName = getFileNameFromUri(uri) ?: guessFileNameFromUri(uri) ?: ""
            val fileName = sanitizeExternalFileName(rawName, destDir)
                ?: "imported_${System.currentTimeMillis()}.md"
            // 同名不静默覆盖：生成 name(1).ext 形式的唯一文件名
            val destFile = uniqueDestFile(destDir, fileName)

            // 先写临时文件、完成后 rename：复制中断不会留下残缺文件被列表扫出
            val tmpFile = java.io.File(destDir, ".${destFile.name}.tmp")
            try {
                val input = contentResolver.openInputStream(uri) ?: return null
                input.use { ins ->
                    tmpFile.outputStream().use { output ->
                        ins.copyTo(output)
                    }
                }
                if (!tmpFile.renameTo(destFile)) {
                    // rename 失败的兜底：拷贝后删除临时文件
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }

            destFile.toURI().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy external file", e)
            null
        }
    }

    /**
     * 生成不冲突的目标文件名：已存在同名文件时依次尝试 name(1).ext、name(2).ext…，
     * 避免不同来源的同名文件互相静默覆盖。
     */
    private fun uniqueDestFile(dir: java.io.File, name: String): java.io.File {
        var candidate = java.io.File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists() && i <= 999) {
            candidate = java.io.File(dir, "$base($i)$ext")
            i++
        }
        if (candidate.exists()) {
            candidate = java.io.File(dir, "${base}_${System.currentTimeMillis()}$ext")
        }
        return candidate
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
     *
     * 标题同样经过 [sanitizeExternalFileName] 净化，防止路径穿越或生成隐藏文件。
     */
    private fun handleSharedText(intent: Intent): String? {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val sharedTitle = intent.getStringExtra(Intent.EXTRA_TITLE) ?: "分享内容"

        return try {
            val destDir = java.io.File(filesDir, "shared").apply { mkdirs() }
            val fileName = sanitizeExternalFileName("$sharedTitle.md", destDir)
                ?: "shared_${System.currentTimeMillis()}.md"
            val file = uniqueDestFile(destDir, fileName)
            file.writeText(sharedText)
            file.toURI().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save shared text", e)
            null
        }
    }

    /**
     * 净化外部传入的文件名，防止路径穿越和写入预期目录之外。
     *
     * - 只保留 basename（去掉路径分隔符）
     * - 拒绝空名、"."、".."、隐藏文件（以点开头的文件名）
     * - 长度限制 128 字符
     * - 最终用 canonical path 校验目标文件确实落在 [destDir] 内
     *
     * @return 净化后的文件名；若无法净化则返回 null，调用方应使用默认文件名
     */
    private fun sanitizeExternalFileName(name: String, destDir: java.io.File): String? {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        if (base.isBlank() ||
            base.contains('/') ||
            base.contains('\\') ||
            base == "." ||
            base == ".." ||
            base.startsWith(".")
        ) {
            return null
        }
        val limited = if (base.length > 128) base.take(128) else base
        return try {
            val canonicalFile = java.io.File(destDir, limited).canonicalPath
            val canonicalDir = destDir.canonicalPath
            if (canonicalFile.startsWith(canonicalDir + java.io.File.separator)) limited else null
        } catch (_: Exception) {
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