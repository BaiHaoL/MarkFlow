package com.markflow.editor.util

import io.noties.markwon.ext.latex.JLatexMathBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.parser.block.BlockParserFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断测试：验证块级 LaTeX 公式（$$...$$）能否被 commonmark 解析为 JLatexMathBlock
 *
 * 用于排查"块级公式只显示原文"问题：若此处不匹配，说明问题在块级匹配/预处理阶段；
 * 若匹配，说明问题在 JLaTeXMath 渲染阶段。
 */
class LatexBlockMatchingTest {

    private val parser: Parser = Parser.builder()
        .customBlockParserFactory(createBlockFactory())
        .build()

    private fun createBlockFactory(): BlockParserFactory {
        val factoryClass = Class.forName("io.noties.markwon.ext.latex.JLatexMathBlockParser\$Factory")
        return factoryClass.getDeclaredConstructor().newInstance() as BlockParserFactory
    }

    private fun containsLatexBlock(markdown: String): Boolean {
        val node = parser.parse(markdown)
        return containsLatexBlockRecursive(node)
    }

    private fun containsLatexBlockRecursive(node: Node): Boolean {
        if (node is JLatexMathBlock) return true
        var child = node.firstChild
        while (child != null) {
            if (containsLatexBlockRecursive(child)) return true
            child = child.next
        }
        return false
    }

    @Test
    fun `标准块级公式应匹配`() {
        val md = "$$\nx^2 + y^2 = z^2\n$$"
        assertTrue("标准 $$...$$ 块应匹配", containsLatexBlock(md))
    }

    @Test
    fun `矩阵转换后块级公式应匹配`() {
        val md = "$$\nA = \\left(\\begin{array}{cccc}\na_{11} & a_{12} & \\cdots & a_{1n} \\\\\na_{21} & a_{22} & \\cdots & a_{2n}\n\\end{array}\\right)\n$$"
        assertTrue("pmatrix 转换后的块应匹配", containsLatexBlock(md))
    }

    @Test
    fun `分段函数转换后块级公式应匹配`() {
        val md = "$$\nf(x) = \\left\\{\\begin{array}{ll}\nx^2, & x \\geq 0 \\\\\n-x^2, & x < 0\n\\end{array}\\right.\n$$"
        assertTrue("cases 转换后的块应匹配", containsLatexBlock(md))
    }

    @Test
    fun `多行对齐转换后块级公式应匹配`() {
        val md = "$$\n\\begin{array}{rl}\n\\nabla \\cdot \\mathbf{E} &= \\frac{\\rho}{\\epsilon_0} \\\\\n\\nabla \\times \\mathbf{E} &= -\\frac{\\partial \\mathbf{B}}{\\partial t}\n\\end{array}\n$$"
        assertTrue("aligned 转换后的块应匹配", containsLatexBlock(md))
    }

    /**
     * 模拟 MarkdownPreview 完整预处理管线：
     * preprocessImagePaths → preprocessLatexEnvironments → preprocessLatexInline
     * → SuperscriptPlugin.processMarkdown → SubscriptPlugin.processMarkdown
     * 验证真实渲染链中块级公式仍被匹配。
     */
    private fun processFullPipeline(markdown: String): String {
        val withImages = MarkwonConfig.preprocessImagePaths(markdown, "").text
        val withEnvs = MarkwonConfig.preprocessLatexEnvironments(withImages)
        val withInline = MarkwonConfig.preprocessLatexInline(withEnvs)
        val afterSup = SuperscriptPlugin().processMarkdown(withInline)
        return SubscriptPlugin().processMarkdown(afterSup)
    }

    @Test
    fun `完整管线后矩阵块应匹配`() {
        val md = "### 矩阵\n\n\$\$\nA = \\begin{pmatrix}\na_{11} & a_{12} & \\cdots & a_{1n} \\\\\na_{21} & a_{22} & \\cdots & a_{2n} \\\\\n\\vdots & \\vdots & \\ddots & \\vdots \\\\\na_{m1} & a_{m2} & \\cdots & a_{mn}\n\\end{pmatrix}\n\$\$"
        val processed = processFullPipeline(md)
        assertTrue("完整管线后矩阵块应匹配", containsLatexBlock(processed))
    }

    @Test
    fun `完整管线后行列式块应匹配`() {
        val md = "\$\$\n\\det(A) = \\begin{vmatrix}\na & b & c \\\\\nd & e & f \\\\\ng & h & i\n\\end{vmatrix} = aei + bfg + cdh - ceg - bdi - afh\n\$\$"
        val processed = processFullPipeline(md)
        assertTrue("完整管线后行列式块应匹配", containsLatexBlock(processed))
    }

    @Test
    fun `完整管线后分段函数块应匹配`() {
        val md = "\$\$\nf(x) = \\begin{cases}\nx^2, & x \\geq 0 \\\\\n-x^2, & x < 0\n\\end{cases}\n\$\$"
        val processed = processFullPipeline(md)
        assertTrue("完整管线后分段函数块应匹配", containsLatexBlock(processed))
    }

    @Test
    fun `完整管线后多行对齐块应匹配`() {
        val md = "\$\$\n\\begin{aligned}\n\\nabla \\cdot \\mathbf{E} &= \\frac{\\rho}{\\epsilon_0} \\\\\n\\nabla \\cdot \\mathbf{B} &= 0 \\\\\n\\nabla \\times \\mathbf{E} &= -\\frac{\\partial \\mathbf{B}}{\\partial t}\n\\end{aligned}\n\$\$"
        val processed = processFullPipeline(md)
        assertTrue("完整管线后多行对齐块应匹配", containsLatexBlock(processed))
    }
}
