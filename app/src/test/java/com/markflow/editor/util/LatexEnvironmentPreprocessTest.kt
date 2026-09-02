package com.markflow.editor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断测试：验证 preprocessLatexEnvironments 的环境转换是否生效。
 *
 * 若某个环境转换抛异常，整个函数会 catch 并返回原始 markdown（未转换），
 * 这正是块级公式显示原文的嫌疑根因。
 */
class LatexEnvironmentPreprocessTest {

    @Test
    fun `单独 pmatrix 应被转换`() {
        val md = "A = \\begin{pmatrix}\na_{11} & a_{12} \\\\\na_{21} & a_{22}\n\\end{pmatrix}"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        assertTrue("pmatrix 应转换为 array，实际: $result", result.contains("\\begin{array}"))
        assertTrue("转换后不应残留 pmatrix，实际: $result", !result.contains("pmatrix"))
    }

    @Test
    fun `单独 vmatrix 应被转换`() {
        val md = "\\det(A) = \\begin{vmatrix}\na & b & c \\\\\nd & e & f\n\\end{vmatrix}"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        assertTrue("vmatrix 应转换为 array，实际: $result", result.contains("\\begin{array}"))
        assertTrue("转换后不应残留 vmatrix，实际: $result", !result.contains("vmatrix"))
    }

    @Test
    fun `单独 cases 应被转换`() {
        val md = "f(x) = \\begin{cases}\nx^2, & x \\geq 0 \\\\\n-x^2, & x < 0\n\\end{cases}"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        assertTrue("cases 应转换为 array，实际: $result", result.contains("\\begin{array}"))
        assertTrue("转换后不应残留 cases，实际: $result", !result.contains("cases"))
    }

    @Test
    fun `单独 aligned 应被转换`() {
        val md = "\\begin{aligned}\n\\nabla \\cdot \\mathbf{E} &= \\frac{\\rho}{\\epsilon_0} \\\\\n\\nabla \\times \\mathbf{E} &= 0\n\\end{aligned}"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        assertTrue("aligned 应转换为 array，实际: $result", result.contains("\\begin{array}"))
        assertTrue("转换后不应残留 aligned，实际: $result", !result.contains("aligned"))
    }

    @Test
    fun `完整块级公式区域应全部转换`() {
        val md = "\$\$\nA = \\begin{pmatrix}\na_{11} & a_{12} & \\cdots & a_{1n} \\\\\na_{21} & a_{22} & \\cdots & a_{2n}\n\\end{pmatrix}\n\$\$\n\n\$\$\n\\det(A) = \\begin{vmatrix}\na & b & c \\\\\nd & e & f\n\\end{vmatrix}\n\$\$\n\n\$\$\nf(x) = \\begin{cases}\nx^2, & x \\geq 0 \\\\\n-x^2, & x < 0\n\\end{cases}\n\$\$\n\n\$\$\n\\begin{aligned}\n\\nabla \\cdot \\mathbf{E} &= \\frac{\\rho}{\\epsilon_0} \\\\\n\\nabla \\cdot \\mathbf{B} &= 0\n\\end{aligned}\n\$\$"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        // 若任一转换抛异常，整个函数回退为原始文本，4 个环境全部残留
        val residual = listOf("pmatrix", "vmatrix", "cases", "aligned")
            .filter { result.contains("\\begin{$it}") }
        assertTrue("以下环境未被转换: $residual。结果: $result", residual.isEmpty())
    }

    @Test
    fun `转换结果应包含正确 array 结构`() {
        val md = "A = \\begin{pmatrix}\na_{11} & a_{12} & a_{13} \\\\\na_{21} & a_{22} & a_{23}\n\\end{pmatrix}"
        val result = MarkwonConfig.preprocessLatexEnvironments(md)
        assertEquals(
            "列数应为 3（3 个 & + 1）",
            "A = \\left(\\begin{array}{ccc}\na_{11} & a_{12} & a_{13} \\\\\na_{21} & a_{22} & a_{23}\n\\end{array}\\right)",
            result
        )
    }
}
