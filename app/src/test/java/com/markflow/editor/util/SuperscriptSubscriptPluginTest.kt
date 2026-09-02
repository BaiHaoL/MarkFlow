package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SuperscriptPlugin / SubscriptPlugin 单元测试
 *
 * 覆盖场景：
 * - LaTeX 公式区间（$...$ / $$...$$）内的 ^ 和 ~ 不被误识别为上下标语法
 * - 普通上标 ^text^ / 下标 ~text~ 功能不受影响
 * - 孤立 $（如 $100）不破坏后续处理
 */
class SuperscriptSubscriptPluginTest {

    private val superscript = SuperscriptPlugin()
    private val subscript = SubscriptPlugin()

    // ==================== 上标插件 ====================

    @Test
    fun `superscript_latex_inline_formula_unchanged`() {
        val md = "勾股定理：\$\$a^2 + b^2 = c^2\$\$"
        assertEquals("双美元公式区间应原样保留", md, superscript.processMarkdown(md))
    }

    @Test
    fun `superscript_latex_single_dollar_unchanged`() {
        val md = "公式 \$a^2 + b^2 = c^2\$ 结束"
        assertEquals("单美元公式区间应原样保留", md, superscript.processMarkdown(md))
    }

    @Test
    fun `superscript_block_latex_unchanged`() {
        val md = "\$\$\nx^2 + y^2 = z^2\n\$\$"
        assertEquals("块级公式区间应原样保留", md, superscript.processMarkdown(md))
    }

    @Test
    fun `superscript_plain_syntax_still_works`() {
        val result = superscript.processMarkdown("x^2^ 和 a^test^")
        assertEquals("普通上标语法应正常工作", "x<sup>2</sup> 和 a<sup>test</sup>", result)
    }

    @Test
    fun `superscript_after_latex_still_works`() {
        val result = superscript.processMarkdown("公式 \$\$a^2\$\$ 后跟 x^2^")
        assertEquals("公式区间外的上标语法应正常处理", "公式 \$\$a^2\$\$ 后跟 x<sup>2</sup>", result)
    }

    @Test
    fun `superscript_isolated_dollar_does_not_break`() {
        val result = superscript.processMarkdown("价格 \$100 且 a^2^")
        assertEquals("孤立美元符号不应破坏后续上标处理", "价格 \$100 且 a<sup>2</sup>", result)
    }

    // ==================== 下标插件 ====================

    @Test
    fun `subscript_latex_inline_formula_unchanged`() {
        val md = "关系 \$x~y\$ 表示"
        assertEquals("公式区间内的波浪线应原样保留", md, subscript.processMarkdown(md))
    }

    @Test
    fun `subscript_plain_syntax_still_works`() {
        val result = subscript.processMarkdown("水是 H~2~O")
        assertEquals("普通下标语法应正常工作", "水是 H<sub>2</sub>O", result)
    }

    @Test
    fun `subscript_after_latex_still_works`() {
        val result = subscript.processMarkdown("公式 \$\$a^2\$\$ 后跟 H~2~O")
        assertEquals("公式区间外的下标语法应正常处理", "公式 \$\$a^2\$\$ 后跟 H<sub>2</sub>O", result)
    }
}
