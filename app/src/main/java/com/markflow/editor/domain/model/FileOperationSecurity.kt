package com.markflow.editor.domain.model

import android.app.PendingIntent
import android.content.IntentSender

/**
 * 需要用户授权才能继续的文件操作。
 *
 * Android 11+ 对非本应用创建的文件进行重命名/删除时可能抛出 [RecoverableSecurityException]，
 * 应用需通过 [IntentSender] 向用户申请一次性或长期授权。
 */
sealed class PendingFileOperation {
    /** 重命名操作 */
    data class Rename(val uri: String, val newName: String) : PendingFileOperation()

    /** 删除操作 */
    data class Delete(val uris: List<String>) : PendingFileOperation()
}

/**
 * 包装 [RecoverableSecurityException]，把系统提供的 [IntentSender] 带到 UI 层。
 *
 * [partialSucceeded]：批量操作在抛出授权异常前已成功完成的部分（如批量删除中
 * 已物理删除的 URI 集合），UI 层必须先把这部分从列表移除，避免 UI 与磁盘状态不一致。
 */
class SecurityConsentRequiredException(
    val intentSender: IntentSender,
    val pendingOperation: PendingFileOperation,
    val partialSucceeded: Set<String> = emptySet()
) : Exception("需要用户授权才能继续该文件操作")
