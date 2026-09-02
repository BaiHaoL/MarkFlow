package com.markflow.editor.ui.components

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.markflow.editor.util.Ref
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.markflow.editor.util.MarkwonConfig

/**
 * VS Code 风格代码块卡片组件
 *
 * 特性：
 * - 独立圆角卡片容器，自动适配深色/浅色模式
 * - 顶栏：左侧大写语言标签，右侧复制按钮 + 折叠按钮
 * - 复制按钮带成功反馈动画（图标切换为对勾，1.5s 后恢复）
 * - 折叠/展开动画，折叠态显示行数提示
 * - 代码区水平可滚动，语法高亮由 Prism4j 提供
 * - 复制原始代码（rawCode）与显示高亮文本严格分离
 *
 * @param language 语言标识（如 "kotlin", "bash"），为空时显示 "CODE"
 * @param rawCode 原始代码文本，用于复制到剪贴板
 * @param codeBlockId 代码块唯一标识，用于 LazyColumn 状态隔离
 * @param isDarkTheme 当前是否为深色主题
 * @param isCollapsed 当前是否折叠
 * @param showCopyFeedback 是否显示复制成功反馈
 * @param onToggleCollapse 折叠/展开回调
 * @param onCopyStart 复制开始回调（设置反馈状态）
 * @param modifier 修饰符
 */
@Composable
fun CodeBlockCard(
    language: String,
    rawCode: String,
    isDarkTheme: Boolean,
    isCollapsed: Boolean,
    showCopyFeedback: Boolean,
    onToggleCollapse: () -> Unit,
    onCopyStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 卡片配色（VS Code 风格）
    val cardBg = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val topBarBg = if (isDarkTheme) Color(0xFF2D2D2D) else Color(0xFFE8E8E8)
    val borderColor = if (isDarkTheme) Color(0xFF3C3C3C) else Color(0xFFD0D0D0)
    val textColor = if (isDarkTheme) Color(0xFFCCCCCC) else Color(0xFF333333)

    val displayLang = language.ifEmpty { "text" }
    val lineCount = rawCode.lines().size

    // 代码块专用 Markwon（透明背景，仅语法高亮）
    // 主题切换时 try-catch 保护，防止 Markwon/Prism4j 初始化异常导致整个组合树崩溃
    val codeMarkwon = remember(isDarkTheme) {
        try {
            MarkwonConfig.createForCodeBlock(context, isDarkTheme)
        } catch (_: Exception) {
            io.noties.markwon.Markwon.builder(context).build()
        }
    }

    // 构建代码块 Markdown 并渲染为 Spanned
    // 折叠时跳过计算：codeArea 不渲染，无需 codeSpanned
    // 主题切换时 try-catch 保护，防止 toMarkdown 异常导致组合树崩溃
    val codeSpanned: CharSequence? = if (!isCollapsed) {
        remember(rawCode, language, isDarkTheme) {
            try {
                val fenceLang = language.ifEmpty { "text" }
                val codeMd = "```$fenceLang\n$rawCode\n```"
                codeMarkwon.toMarkdown(codeMd)
            } catch (_: Exception) {
                android.text.SpannedString(rawCode)
            }
        }
    } else {
        null
    }

    // 代码块字体
    val codeTypeface = remember { Typeface.MONOSPACE }

    // 跟踪上一次渲染的 codeSpanned 引用，避免 AndroidView update 中
    // 无条件设置 text 导致 TextView 重新测量、引起滚动跳动。
    // 使用 Ref 而非 MutableState：写入不触发重组，避免首帧双重测量
    // 导致 LazyColumn 滚动位置跳动。
    val lastCodeSpanned = remember { Ref<CharSequence?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(borderColor)
            .padding(1.dp) // 边框效果
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
    ) {
        androidx.compose.foundation.layout.Column {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(topBarBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：语言标签
                Text(
                    text = displayLang,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 折叠状态行数提示
                if (isCollapsed) {
                    Text(
                        text = "$lineCount 行",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = textColor.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // 复制按钮
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("code", rawCode)
                        )
                        onCopyStart()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (showCopyFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (showCopyFeedback) "已复制" else "复制代码",
                        modifier = Modifier.size(16.dp),
                        tint = if (showCopyFeedback) Color(0xFF4CAF50) else textColor.copy(alpha = 0.7f)
                    )
                }

                // 折叠/展开按钮
                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (isCollapsed) "展开" else "折叠",
                        modifier = Modifier.size(18.dp),
                        tint = textColor.copy(alpha = 0.7f)
                    )
                }
            }

            // ---- 代码区（折叠） ----
            // 直接 if 控制显隐，不使用任何动画修饰符，彻底排除
            // AnimatedVisibility / animateContentSize 在 LazyColumn 中的
            // 首帧测量不稳定问题。
            if (!isCollapsed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                // txt 代码块（无语言标识）禁用文本选择：无语法高亮无需
                                // 选中特定 token，且 setTextIsSelectable 创建的内部 Editor
                                // 会拦截触摸事件与 LazyColumn 滚动冲突，导致滚动跳动
                                setTextIsSelectable(language.isNotEmpty())
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = false
                                overScrollMode = View.OVER_SCROLL_NEVER
                                gravity = Gravity.TOP or Gravity.START
                                typeface = codeTypeface
                                setLineSpacing(4f, 1.3f)
                                setPadding(0, 0, 0, 0)
                                setTextColor(
                                    if (isDarkTheme) {
                                        android.graphics.Color.parseColor("#D4D4D4")
                                    } else {
                                        android.graphics.Color.parseColor("#24292F")
                                    }
                                )
                                text = codeSpanned
                                // 标记已设置，避免 update 回调重复 setText 导致
                                // TextView 同帧二次测量、触发 LazyColumn 滚动跳动
                                lastCodeSpanned.value = codeSpanned
                            }
                        },
                        update = { textView ->
                            // 仅当 codeSpanned 引用变化时才更新，避免滚动时
                            // 反复设置 text 导致 TextView 重新测量、引起跳动
                            if (lastCodeSpanned.value !== codeSpanned) {
                                lastCodeSpanned.value = codeSpanned
                                try {
                                    textView.typeface = codeTypeface
                                    textView.setTextColor(
                                        if (isDarkTheme) {
                                            android.graphics.Color.parseColor("#D4D4D4")
                                        } else {
                                            android.graphics.Color.parseColor("#24292F")
                                        }
                                    )
                                    textView.text = codeSpanned
                                } catch (_: Exception) {
                                    textView.text = rawCode
                                }
                            }
                        },
                        modifier = Modifier
                    )
                }
            }
        }
    }
}