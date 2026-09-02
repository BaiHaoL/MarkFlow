package com.markflow.editor.util

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.annotations.PrismBundle

/**
 * Prism4j 语法高亮配置
 *
 * 通过 kapt 注解处理器自动生成 {@link MarkFlowGrammarLocator} 类，
 * 包含以下语言的语法定义：
 * - Kotlin / Java / Python / JavaScript / TypeScript / C / C++ / Rust / Go
 * - Swift / Ruby / Shell / SQL / JSON / XML / YAML / Markdown
 * - HTML / CSS / SCSS / Dockerfile / GraphQL
 */
@PrismBundle(
    includeAll = true,
    grammarLocatorClassName = ".MarkFlowGrammarLocator"
)
object Prism4jBundle