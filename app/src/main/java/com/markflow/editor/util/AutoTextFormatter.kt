package com.markflow.editor.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.markflow.editor.domain.model.AutoFormatPatterns

/**
 * Markdown 自动格式化工具
 *
 * 参考 Markor 的 AutoTextFormatter + ListHandler 设计，适配 Compose BasicTextField 环境。
 * 在编辑模式下自动处理：
 * 1. 列表续行：换行时自动补全无序列表（-、*、+）、有序列表（1.）、复选框（- [ ]）前缀
 * 2. 缩进保留：继承上一行的前导空白
 * 3. 空列表项清理：在空列表项行按 Enter 时移除列表标记
 * 4. 有序列表自动编号：换行后自动递增序号
 *
 * 格式规则通过 [AutoFormatPatterns] 注入，支持不同格式的自定义规则。
 */
object AutoTextFormatter {

    /** 默认使用 Markdown 格式化规则 */
    private val defaultPatterns = AutoFormatPatterns.MARKDOWN

    /** 纯缩进匹配：行首空白后跟非空白字符 */
    private val INDENT_PATTERN = Regex("""^(\s+)\S""")

    // ==================== 公共接口 ====================

    /**
     * 处理输入变更，自动格式化列表
     *
     * 在 EditorScreen 的 onValueChange 中调用，当检测到换行符插入时应用自动格式化。
     *
     * @param oldValue 变更前的 TextFieldValue
     * @param newValue 变更后的 TextFieldValue
     * @param patterns 格式化规则（可选，默认使用 Markdown 规则）
     * @return 格式化后的 TextFieldValue（无变更时返回 newValue）
     */
    fun applyListFormatting(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
        patterns: AutoFormatPatterns = defaultPatterns
    ): TextFieldValue {
        val oldText = oldValue.text
        val newText = newValue.text
        val oldCursor = oldValue.selection.start

        // 仅在插入换行符时触发
        if (newText.length <= oldText.length) return newValue
        if (newText.length - oldText.length != 1) return newValue
        if (newText[oldCursor] != '\n') return newValue

        // 获取插入位置之前的行
        val lineStart = newText.lastIndexOf('\n', oldCursor - 1).let { if (it < 0) 0 else it + 1 }
        val currentLine = newText.substring(lineStart, oldCursor)

        // 检测列表模式
        return formatListLine(newText, oldCursor, lineStart, currentLine, newValue.selection, patterns)
    }

    // ==================== 格式化逻辑 ====================

    private fun formatListLine(
        text: String,
        cursorPos: Int,
        lineStart: Int,
        currentLine: String,
        selection: TextRange,
        patterns: AutoFormatPatterns
    ): TextFieldValue {
        // 1. 有序列表
        patterns.prefixOrderedList.find(currentLine)?.let { match ->
            val indent = match.groupValues[1]
            val number = match.groupValues[2].toIntOrNull() ?: return@let
            val delimiter = match.groupValues[3]
            val content = match.groupValues[4]

            // 空列表项：清除标记并移除上一行标记
            if (content.isBlank()) {
                return clearEmptyListItem(text, cursorPos, lineStart)
            }

            // 正常续行：递增序号
            val nextNumber = number + 1
            val prefix = "${indent}${nextNumber}${delimiter} "
            val newText = text.substring(0, cursorPos + 1) + prefix + text.substring(cursorPos + 1)
            val newCursor = cursorPos + 1 + prefix.length
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // 2. 复选框
        patterns.prefixCheckBoxList.find(currentLine)?.let { match ->
            val indent = match.groupValues[1]
            val marker = match.groupValues[2]
            val content = match.groupValues[4]

            if (content.isBlank()) {
                return clearEmptyListItem(text, cursorPos, lineStart)
            }

            val prefix = "${indent}${marker} [ ] "
            val newText = text.substring(0, cursorPos + 1) + prefix + text.substring(cursorPos + 1)
            val newCursor = cursorPos + 1 + prefix.length
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // 3. 无序列表
        patterns.prefixUnorderedList.find(currentLine)?.let { match ->
            val indent = match.groupValues[1]
            val marker = match.groupValues[2]
            val content = match.groupValues[3]

            if (content.isBlank()) {
                return clearEmptyListItem(text, cursorPos, lineStart)
            }

            val prefix = "${indent}${marker} "
            val newText = text.substring(0, cursorPos + 1) + prefix + text.substring(cursorPos + 1)
            val newCursor = cursorPos + 1 + prefix.length
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // 4. 纯缩进续行：继承上一行的前导空白
        INDENT_PATTERN.find(currentLine)?.let { match ->
            val indent = match.groupValues[1]
            val newText = text.substring(0, cursorPos + 1) + indent + text.substring(cursorPos + 1)
            val newCursor = cursorPos + 1 + indent.length
            return TextFieldValue(text = newText, selection = TextRange(newCursor))
        }

        // 无匹配：返回原值
        return TextFieldValue(text = text, selection = selection)
    }

    /**
     * 清理空列表项：按 Enter 时移除当前列表标记并减少缩进
     *
     * 行为：
     * - 顶级列表项：移除列表标记，仅保留换行
     * - 子列表项：移除列表标记，缩进减少一级
     */
    private fun clearEmptyListItem(
        text: String,
        cursorPos: Int,
        lineStart: Int
    ): TextFieldValue {
        // 移除列表标记行（从 lineStart 到 cursorPos，即当前行的内容）
        val before = text.substring(0, lineStart)
        val after = text.substring(cursorPos + 1) // 跳过已插入的 \n
        val newText = before + after
        val newCursor = lineStart.coerceAtMost(newText.length)
        return TextFieldValue(text = newText, selection = TextRange(newCursor))
    }

    // ==================== 辅助方法 ====================
}