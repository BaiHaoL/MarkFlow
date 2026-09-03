package com.markflow.editor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 底部操作栏
 *
 * 显示逻辑：
 * - 单选（selectedCount == 1）→ 分享、重命名、详细信息、删除 均可用
 * - 多选（selectedCount > 1）→ 分享、删除可用（多文件分享），重命名/详细信息置灰不可点击
 *
 * 注：退出选择模式的「×」按钮位于顶部 SelectionTopBar，底部不再重复放置。
 *
 * @param selectedCount 已选中的文件数量
 * @param onDelete 删除回调
 * @param onShare 分享回调（单选/多选均有效）
 * @param onRename 重命名回调（仅单选时有效）
 * @param onDetails 详细信息回调（仅单选时有效）
 */
@Composable
fun BottomActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit
) {
    val isSingleSelection = selectedCount == 1

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // 分享（单选/多选均可用）
            TextButton(
                onClick = onShare,
                enabled = selectedCount > 0
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("分享")
            }

            // 重命名（仅单选时可用）
            TextButton(
                onClick = onRename,
                enabled = isSingleSelection
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("重命名")
            }

            // 详细信息（仅单选时可用）
            TextButton(
                onClick = onDetails,
                enabled = isSingleSelection
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("详细信息")
            }

            // 删除（始终可用）
            TextButton(
                onClick = onDelete,
                enabled = selectedCount > 0,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除")
            }
        }
    }
}