package com.markflow.editor.domain.model

/**
 * 编辑器显示模式
 *
 * - EDIT：编辑模式，显示原始 Markdown 文本，可在 TextField 中直接输入
 * - PREVIEW：预览模式，显示 Markwon 渲染后的富文本（只读）
 */
enum class EditorMode {
    /** 编辑模式：直接编辑 Markdown 原文 */
    EDIT,

    /** 预览模式：Markwon 渲染的富文本（只读） */
    PREVIEW
}