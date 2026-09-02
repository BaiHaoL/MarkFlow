package com.markflow.editor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 搜索栏组件
 * 提供关键字搜索、高亮查找、上下文预览功能
 *
 * @param onSearchQueryChanged 搜索文本变化回调
 * @param onClose 关闭搜索栏回调
 * @param resultCount 搜索结果数量（匹配数）
 * @param currentIndex 当前高亮的匹配项索引
 * @param searchContext 当前匹配项的上下文预览文本
 * @param onNavigatePrevious 上一个匹配项
 * @param onNavigateNext 下一个匹配项
 */
@Composable
fun SearchBar(
    onSearchQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
    resultCount: Int = 0,
    currentIndex: Int = 0,
    searchContext: String = "",
    onNavigatePrevious: () -> Unit = {},
    onNavigateNext: () -> Unit = {},
    onContextClick: () -> Unit = {}
) {
    var searchText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // 自动获取焦点
    // 搜索框进场时输入字段可能尚未附着到组合树（如主题切换/Activity 重建），
    // requestFocus 会抛 IllegalStateException，需容错处理
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // 聚焦控件尚未初始化，忽略本次聚焦请求
        }
    }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = searchText,
                    onValueChange = { value ->
                        searchText = value
                        onSearchQueryChanged(value)
                    },
                    placeholder = { Text("搜索关键字…") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                if (searchText.isNotEmpty()) {
                    Text(
                        text = if (resultCount > 0) "${currentIndex + 1}/$resultCount" else "0/0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    IconButton(
                        onClick = onNavigatePrevious,
                        enabled = resultCount > 0
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一个")
                    }

                    IconButton(
                        onClick = onNavigateNext,
                        enabled = resultCount > 0
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一个")
                    }
                }

                IconButton(onClick = {
                    searchText = ""
                    onClose()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                }
            }

            // 上下文预览：当有搜索结果时显示当前匹配项周围的文本，点击跳转到对应位置
            if (searchText.isNotEmpty() && searchContext.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { onContextClick() },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = searchContext,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
