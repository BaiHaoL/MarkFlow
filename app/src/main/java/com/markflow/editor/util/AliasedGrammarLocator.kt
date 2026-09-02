package com.markflow.editor.util

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.languages.Prism_bash
import io.noties.prism4j.languages.Prism_diff
import io.noties.prism4j.languages.Prism_dockerfile
import io.noties.prism4j.languages.Prism_toml
import io.noties.prism4j.languages.Prism_typescript

/**
 * 带别名映射和自定义语言扩展的 GrammarLocator
 *
 * 包装 kapt 自动生成的 [MarkFlowGrammarLocator]，在其基础上：
 * 1. 添加语言别名映射（c++、sh、ts 等）
 * 2. 补充 kapt 无法自动发现的手写 Grammar（bash、dockerfile、diff、typescript、toml）
 *
 * kapt 的 @PrismBundle(includeAll=true) 仅扫描依赖 jar 中的 Prism_* 类，
 * 无法发现源码目录下手写的 Kotlin Grammar 文件，因此需要在此处手动注册。
 */
class AliasedGrammarLocator : GrammarLocator {

    private val delegate = MarkFlowGrammarLocator()

    private val aliases = mapOf(
        "c++" to "cpp",
        "c#" to "csharp",
        "py" to "python",
        "kt" to "kotlin",
        "golang" to "go",
        "yml" to "yaml",
        "sh" to "bash",
        "shell" to "bash",
        "zsh" to "bash",
        "docker" to "dockerfile",
        "patch" to "diff",
        "ts" to "typescript"
    )

    /** kapt 无法自动发现的自定义语言（不含别名） */
    private val customLanguages = setOf("bash", "dockerfile", "diff", "typescript", "toml")

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val mapped = aliases[language] ?: language
        // 先尝试委托给内置 GrammarLocator
        delegate.grammar(prism4j, mapped)?.let { return it }
        // 委托未命中时，尝试自定义 Grammar
        return createCustomGrammar(prism4j, mapped)
    }

    private fun createCustomGrammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        return when (language) {
            "bash" -> Prism_bash.create(prism4j)
            "dockerfile" -> Prism_dockerfile.create(prism4j)
            "diff" -> Prism_diff.create(prism4j)
            "typescript" -> Prism_typescript.create(prism4j)
            "toml" -> Prism_toml.create(prism4j)
            else -> null
        }
    }

    override fun languages(): Set<String> {
        return delegate.languages() + aliases.keys + customLanguages
    }
}
