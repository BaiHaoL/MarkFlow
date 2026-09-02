package com.markflow.editor.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UndoRedoManager 单元测试
 *
 * 覆盖场景：
 * - 会话内连续输入合并为一步撤回
 * - 慢速输入（会话结束）每个字符独立撤回
 * - 撤回后反撤回
 * - 撤回后继续编辑清空 redo 栈
 * - canUndo/canRedo 状态同步
 * - 栈溢出处理
 * - reset 清空历史
 */
class UndoRedoManagerTest {

    // ==================== 会话内连续输入（合并撤回） ====================

    @Test
    fun `single edit makes undo immediately available`() {
        val manager = UndoRedoManager()
        assertFalse(manager.canUndo)

        // 会话开始：推入起始状态，canUndo 立即变 true
        val changed = manager.onContentChanged(oldContent = "", oldCursor = 0)
        assertTrue(changed)
        assertTrue(manager.canUndo)
    }

    @Test
    fun `continuous typing in session merges into single undo step`() {
        val manager = UndoRedoManager()
        // 模拟快速输入 "hello"（同一会话，未调用 endSession）
        // 会话开始：起始状态 ""
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        // 会话内继续编辑：不推入栈
        val changed2 = manager.onContentChanged(oldContent = "h", oldCursor = 1)
        assertFalse(changed2)
        val changed3 = manager.onContentChanged(oldContent = "he", oldCursor = 2)
        assertFalse(changed3)
        val changed4 = manager.onContentChanged(oldContent = "hel", oldCursor = 3)
        assertFalse(changed4)
        val changed5 = manager.onContentChanged(oldContent = "hell", oldCursor = 4)
        assertFalse(changed5)

        // 撤回一次应恢复到会话起始状态 ""
        val result = manager.undo(currentContent = "hello", currentCursor = 5)
        assertEquals("", result?.content)
        assertEquals(0, result?.cursor)
        // 再撤回应无法进行（栈已空）
        assertNull(manager.undo(currentContent = "", currentCursor = 0))
    }

    @Test
    fun `undo after session ends restores to session start`() {
        val manager = UndoRedoManager()
        // 会话：起始 ""
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        // 会话结束
        manager.endSession()
        // 撤回恢复到 ""
        val result = manager.undo(currentContent = "hello", currentCursor = 5)
        assertEquals("", result?.content)
    }

    // ==================== 慢速输入（每步独立撤回） ====================

    @Test
    fun `slow typing produces separate undo steps per character`() {
        val manager = UndoRedoManager()
        // 慢速输入 'h'：会话1
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()
        // 慢速输入 'e'：会话2
        manager.onContentChanged(oldContent = "h", oldCursor = 1)
        manager.endSession()
        // 慢速输入 'l'：会话3
        manager.onContentChanged(oldContent = "he", oldCursor = 2)
        manager.endSession()

        // 当前内容 "hel"，撤回应恢复到 "he"
        val r1 = manager.undo(currentContent = "hel", currentCursor = 3)
        assertEquals("he", r1?.content)
        // 再撤回应恢复到 "h"
        val r2 = manager.undo(currentContent = "he", currentCursor = 2)
        assertEquals("h", r2?.content)
        // 再撤回应恢复到 ""
        val r3 = manager.undo(currentContent = "h", currentCursor = 1)
        assertEquals("", r3?.content)
        // 栈空
        assertNull(manager.undo(currentContent = "", currentCursor = 0))
    }

    // ==================== 撤回后反撤回 ====================

    @Test
    fun `redo restores undone content`() {
        val manager = UndoRedoManager()
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()

        // 撤回："hello" -> ""
        val undone = manager.undo(currentContent = "hello", currentCursor = 5)
        assertEquals("", undone?.content)
        assertFalse(manager.canUndo)
        assertTrue(manager.canRedo)

        // 反撤回："" -> "hello"
        val redone = manager.redo(currentContent = "", currentCursor = 0)
        assertEquals("hello", redone?.content)
        assertEquals(5, redone?.cursor)
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun `multiple undo then redo restores sequence`() {
        val manager = UndoRedoManager()
        // 三个独立会话："" -> "a" -> "ab" -> "abc"
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()
        manager.onContentChanged(oldContent = "a", oldCursor = 1)
        manager.endSession()
        manager.onContentChanged(oldContent = "ab", oldCursor = 2)
        manager.endSession()

        // 撤回两次："abc" -> "ab" -> "a"，undoStack 仍保留起始 ""
        manager.undo(currentContent = "abc", currentCursor = 3)
        manager.undo(currentContent = "ab", currentCursor = 2)
        assertTrue(manager.canUndo)
        assertTrue(manager.canRedo)

        // 反撤回一次："a" -> "ab"
        val redone = manager.redo(currentContent = "a", currentCursor = 1)
        assertEquals("ab", redone?.content)
        assertTrue(manager.canUndo)
        assertTrue(manager.canRedo)
    }

    // ==================== 撤回后继续编辑清空 redo 栈 ====================

    @Test
    fun `editing after undo clears redo stack`() {
        val manager = UndoRedoManager()
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()

        // 撤回
        manager.undo(currentContent = "hello", currentCursor = 5)
        assertTrue(manager.canRedo)

        // 继续编辑：清空 redo 栈
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        assertFalse(manager.canRedo)

        // 反撤回不可用
        assertNull(manager.redo(currentContent = "x", currentCursor = 1))
    }

    @Test
    fun `undo from within active session ends session and restores`() {
        val manager = UndoRedoManager()
        // 会话开始：起始 ""
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        // 会话内继续编辑（未 endSession）
        manager.onContentChanged(oldContent = "h", oldCursor = 1)
        manager.onContentChanged(oldContent = "he", oldCursor = 2)

        // 会话中直接撤回：应恢复到会话起始 ""
        val result = manager.undo(currentContent = "hel", currentCursor = 3)
        assertEquals("", result?.content)
        // 当前状态 "hel" 应进入 redo 栈
        assertTrue(manager.canRedo)
    }

    // ==================== 光标位置恢复 ====================

    @Test
    fun `undo restores cursor position`() {
        val manager = UndoRedoManager()
        // 起始光标在位置 5
        manager.onContentChanged(oldContent = "hello", oldCursor = 5)
        manager.endSession()

        // 编辑后光标在 11
        val result = manager.undo(currentContent = "hello world", currentCursor = 11)
        assertEquals("hello", result?.content)
        assertEquals(5, result?.cursor)
    }

    @Test
    fun `redo restores cursor position`() {
        val manager = UndoRedoManager()
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()

        // 撤回：记录当前光标 5
        manager.undo(currentContent = "hello", currentCursor = 5)
        // 反撤回：恢复光标 5
        val redone = manager.redo(currentContent = "", currentCursor = 0)
        assertEquals("hello", redone?.content)
        assertEquals(5, redone?.cursor)
    }

    // ==================== 栈溢出处理 ====================

    @Test
    fun `undo stack overflow discards oldest entry`() {
        val manager = UndoRedoManager(maxSteps = 3)
        // 推入 4 个会话起始状态，最早的一个应被丢弃
        manager.onContentChanged(oldContent = "0", oldCursor = 0)
        manager.endSession()
        manager.onContentChanged(oldContent = "1", oldCursor = 1)
        manager.endSession()
        manager.onContentChanged(oldContent = "2", oldCursor = 2)
        manager.endSession()
        manager.onContentChanged(oldContent = "3", oldCursor = 3)
        manager.endSession()

        // 当前 "4"，撤回应恢复到 "3"
        assertEquals("3", manager.undo(currentContent = "4", currentCursor = 4)?.content)
        assertEquals("2", manager.undo(currentContent = "3", currentCursor = 3)?.content)
        assertEquals("1", manager.undo(currentContent = "2", currentCursor = 2)?.content)
        // "0" 已被丢弃，无法撤回到 "0"
        assertNull(manager.undo(currentContent = "1", currentCursor = 1))
    }

    // ==================== reset ====================

    @Test
    fun `reset clears all history`() {
        val manager = UndoRedoManager()
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()
        manager.undo(currentContent = "hello", currentCursor = 5)
        assertTrue(manager.canRedo)

        manager.reset()
        assertFalse(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun `reset allows fresh session to start`() {
        val manager = UndoRedoManager()
        manager.onContentChanged(oldContent = "old", oldCursor = 3)
        manager.reset()

        // 新会话：起始 "new"
        manager.onContentChanged(oldContent = "new", oldCursor = 3)
        assertTrue(manager.canUndo)
        val result = manager.undo(currentContent = "newX", currentCursor = 4)
        assertEquals("new", result?.content)
    }

    // ==================== 边界情况 ====================

    @Test
    fun `undo on empty stack returns null`() {
        val manager = UndoRedoManager()
        assertNull(manager.undo(currentContent = "x", currentCursor = 1))
    }

    @Test
    fun `redo on empty stack returns null`() {
        val manager = UndoRedoManager()
        assertNull(manager.redo(currentContent = "x", currentCursor = 1))
    }

    @Test
    fun `endSession allows new session to start`() {
        val manager = UndoRedoManager()
        // 会话1
        manager.onContentChanged(oldContent = "", oldCursor = 0)
        manager.endSession()
        // 会话2：应推入新的起始状态
        val changed = manager.onContentChanged(oldContent = "a", oldCursor = 1)
        assertTrue(changed)

        // 撤回一次恢复到 "a"，再撤回恢复到 ""
        assertEquals("a", manager.undo(currentContent = "ab", currentCursor = 2)?.content)
        assertEquals("", manager.undo(currentContent = "a", currentCursor = 1)?.content)
    }

    @Test
    fun `continuous session does not push duplicate entries`() {
        val manager = UndoRedoManager()
        // 会话开始
        manager.onContentChanged(oldContent = "start", oldCursor = 5)
        // 会话内多次编辑，不应推入额外条目
        manager.onContentChanged(oldContent = "start1", oldCursor = 6)
        manager.onContentChanged(oldContent = "start12", oldCursor = 7)
        manager.onContentChanged(oldContent = "start123", oldCursor = 8)

        // 只能撤回一次，恢复到 "start"
        assertEquals("start", manager.undo(currentContent = "start1234", currentCursor = 9)?.content)
        assertNull(manager.undo(currentContent = "start", currentCursor = 5))
    }
}
