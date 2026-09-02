package io.noties.prism4j.languages

import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * TOML 语法高亮
 * 基于 Prism.js toml 语法定义翻译
 */
@Suppress("unused")
object Prism_toml {

    private val COMMENT = Pattern.compile("#.*")
    private val TABLE_HEADER = Pattern.compile("^\\s*\\[+[^\\[\\]]*\\]+", Pattern.MULTILINE)
    private val KEY = Pattern.compile(
        "(?:^|(?<![^.\\w-]))[A-Za-z_][\\w-]*\\s*(?==)",
        Pattern.MULTILINE
    )
    private val STRING_DOUBLE = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val STRING_SINGLE = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val TRIPLE_DOUBLE_QUOTED = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"")
    private val TRIPLE_SINGLE_QUOTED = Pattern.compile("'''[\\s\\S]*?'''")
    private val DATE_TIME = Pattern.compile(
        "\\b\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:\\d{2})?)?\\b"
    )
    private val BOOLEAN = Pattern.compile("\\b(?:true|false)\\b", Pattern.CASE_INSENSITIVE)
    private val NUMBER = Pattern.compile(
        "(?:[+-]?(?:0[xX][\\da-fA-F]+(?:_[\\da-fA-F]+)*|0[oO][0-7]+(?:_[0-7]+)*|0[bB][01]+(?:_[01]+)*|\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?(?:[eE][+-]?\\d+(?:_\\d+)*)?))(?!\\w)",
        Pattern.MULTILINE
    )
    private val PUNCTUATION = Pattern.compile("[=.,\\[\\]{}]")

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun create(prism4j: Prism4j): Prism4j.Grammar {
        return Prism4j.grammar(
            "toml",
            Prism4j.token("comment", Prism4j.pattern(COMMENT, false, false, "comment")),
            Prism4j.token("table", Prism4j.pattern(TABLE_HEADER, false, false, "function")),
            Prism4j.token("key", Prism4j.pattern(KEY, false, false, "atrule")),
            Prism4j.token("triple-string", Prism4j.pattern(TRIPLE_DOUBLE_QUOTED, false, false, "string")),
            Prism4j.token("triple-string", Prism4j.pattern(TRIPLE_SINGLE_QUOTED, false, false, "string")),
            Prism4j.token("string", Prism4j.pattern(STRING_DOUBLE, false, false, "string")),
            Prism4j.token("string", Prism4j.pattern(STRING_SINGLE, false, false, "string")),
            Prism4j.token("datetime", Prism4j.pattern(DATE_TIME, false, false, "number")),
            Prism4j.token("boolean", Prism4j.pattern(BOOLEAN, false, false, "important")),
            Prism4j.token("number", Prism4j.pattern(NUMBER, false, false, "number")),
            Prism4j.token("punctuation", Prism4j.pattern(PUNCTUATION))
        )
    }
}