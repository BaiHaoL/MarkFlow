package com.markflow.editor.domain.util

/**
 * 撤回/反撤回管理器
 *
 * 设计要点：
 * - 会话开始时（首次按键）立即将会话起始状态推入 undo 栈，使 canUndo 立即可用
 * - 防抖只用于结束会话（重置 sessionStart），不再负责推入栈
 * - 这样解决了两个问题：
 *   1. canUndo 立即可用（无需等待防抖）
 *   2. 撤回时恢复到会话起始状态（完整撤回，而非仅 1-2 个字符）
 *
 * 会话定义：用户连续编辑（按键间隔 < 防抖阈值）属于同一会话，
 * 撤回时作为一个整体撤销。会话结束（防抖触发）后，下次按键开启新会话。
 *
 * 慢速输入场景（按键间隔 > 防抖阈值）：每个字符独立推入栈，
 * 撤回时逐字符回退，与主流编辑器行为一致。
 */
class UndoRedoManager(
    private val maxSteps: Int = 50
) {
    /** 撤回栈条目：记录内容快照及对应光标位置 */
    private data class Entry(val content: String, val cursor: Int)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** 当前编辑会话起始时的内容（null 表示当前不在编辑会话中） */
    private var sessionStartContent: String? = null
    private var sessionStartCursor: Int = 0

    /** 是否可撤回 */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** 是否可反撤回 */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 重置历史（加载新文件时调用） */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        sessionStartContent = null
    }

    /**
     * 记录内容变化。
     *
     * 会话开始时（sessionStartContent == null）立即将起始状态推入 undo 栈，
     * 并清空 redo 栈。会话内继续编辑不推入栈（属于同一原子操作）。
     *
     * @param oldContent 编辑前内容
     * @param oldCursor 编辑前光标位置
     * @return true 表示栈状态发生变化（需要同步 UI 的 canUndo/canRedo）
     */
    fun onContentChanged(oldContent: String, oldCursor: Int): Boolean {
        if (sessionStartContent == null) {
            // 会话开始：立即推入起始状态，使 canUndo 立即可用
            sessionStartContent = oldContent
            sessionStartCursor = oldCursor
            undoStack.addLast(Entry(oldContent, oldCursor))
            if (undoStack.size > maxSteps) undoStack.removeFirst()
            redoStack.clear()
            return true
        }
        // 会话内继续编辑，不推入栈
        return false
    }

    /** 结束当前会话（防抖触发或撤回/反撤回时调用） */
    fun endSession() {
        sessionStartContent = null
    }

    /**
     * 撤回：恢复到上一个编辑状态。
     *
     * 当前状态推入 redo 栈，从 undo 栈弹出上一个状态。
     *
     * @param currentContent 当前内容
     * @param currentCursor 当前光标位置
     * @return 撤回后的内容和光标，null 表示无法撤回
     */
    fun undo(currentContent: String, currentCursor: Int): Result? {
        sessionStartContent = null
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(Entry(currentContent, currentCursor))
        if (redoStack.size > maxSteps) redoStack.removeFirst()
        return Result(previous.content, previous.cursor)
    }

    /**
     * 反撤回：恢复已撤回的操作。
     *
     * 当前状态推入 undo 栈，从 redo 栈弹出下一个状态。
     *
     * @param currentContent 当前内容
     * @param currentCursor 当前光标位置
     * @return 反撤回后的内容和光标，null 表示无法反撤回
     */
    fun redo(currentContent: String, currentCursor: Int): Result? {
        sessionStartContent = null
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(Entry(currentContent, currentCursor))
        if (undoStack.size > maxSteps) undoStack.removeFirst()
        return Result(next.content, next.cursor)
    }

    /** 撤回/反撤回结果 */
    data class Result(val content: String, val cursor: Int)
}
