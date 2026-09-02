package io.noties.prism4j.languages

import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * Bash / Shell 语法高亮
 *
 * 基于 Prism.js bash 语法定义翻译。
 * 关键设计原则：
 * - 所有模式使用 lookbehind=false，避免捕获组被排除导致高亮截断
 * - STRING 模式在 COMMENT 之前，确保字符串内的 # 不被误判为注释
 * - SHEBANG 在 COMMENT 之前，确保 #! 行不被误判为注释
 */
@Suppress("unused")
object Prism_bash {

    private val SHEBANG = Pattern.compile("^#![^\\r\\n]*")
    private val STRING_DOUBLE = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val STRING_SINGLE = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val BACKTICK = Pattern.compile("`[^`]*`")
    private val SHELL_VARIABLE = Pattern.compile(
        "(?:\\$\\w+|\\$\\{[^}]+\\})",
        Pattern.MULTILINE
    )
    private val COMMENT = Pattern.compile("#.*")
    private val KEYWORD = Pattern.compile(
        "\\b(?:if|then|else|elif|fi|for|while|in|do|done|case|esac|function|select|until|break|continue|return|exit|declare|local|readonly|unset|export|alias|eval|exec|trap|wait|shift|set|let|test|source|typeset|enable|disable|builtin|caller|hash|help|mapfile|read|readarray|printf|compgen|complete|getopts|logout|suspend|times|type|ulimit|unalias|bind|command|fc|time|echo|printf|cd|pwd|ls|mkdir|rmdir|cp|mv|rm|cat|touch|chmod|chown|ln|find|grep|awk|sed|sort|uniq|wc|head|tail|cut|tr|tee|diff|tar|gzip|zip|unzip|curl|wget|ssh|scp|rsync|kill|ps|top|df|du|mount|umount|chroot|env)\\b",
        Pattern.MULTILINE
    )
    private val NUMBER = Pattern.compile("\\b\\d+\\b")
    private val OPERATOR = Pattern.compile(
        "[|&;<>(){}\\[\\]!~+\\-*/%=,:?.]|&&|\\|\\||<<|>>|&>|>&|2>|2>&1|1>&2"
    )

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun create(prism4j: Prism4j): Prism4j.Grammar {
        return Prism4j.grammar(
            "bash",
            // shebang 必须在 comment 之前，否则 #! 会被 comment 匹配
            Prism4j.token("shebang", Prism4j.pattern(SHEBANG, false, false, "important")),
            // 字符串在注释之前，确保 "#..." 在字符串内不被误判为注释
            Prism4j.token("string", Prism4j.pattern(STRING_DOUBLE, false, false, "string")),
            Prism4j.token("string", Prism4j.pattern(STRING_SINGLE, false, false, "string")),
            Prism4j.token("backtick", Prism4j.pattern(BACKTICK, false, false, "string")),
            Prism4j.token("shell-variable", Prism4j.pattern(SHELL_VARIABLE, false, false, "variable")),
            Prism4j.token("comment", Prism4j.pattern(COMMENT, false, false, "comment")),
            Prism4j.token("keyword", Prism4j.pattern(KEYWORD, false, false, "keyword")),
            Prism4j.token("number", Prism4j.pattern(NUMBER, false, false, "number")),
            Prism4j.token("operator", Prism4j.pattern(OPERATOR))
        )
    }
}