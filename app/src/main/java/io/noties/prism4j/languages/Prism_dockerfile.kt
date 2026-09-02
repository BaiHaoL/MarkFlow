package io.noties.prism4j.languages

import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * Dockerfile 语法高亮
 *
 * 关键设计原则：
 * - 所有模式使用 lookbehind=false，避免捕获组被排除导致高亮截断
 * - KEYWORD 匹配行首指令关键字（FROM/RUN/COPY等），不匹配行内出现的同名普通词
 * - 字符串在注释之前，确保 "#..." 在字符串内不被误判为注释
 */
@Suppress("unused")
object Prism_dockerfile {

    private val STRING_DOUBLE = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val STRING_SINGLE = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val VARIABLE = Pattern.compile(
        "(?:\\$\\w+|\\$\\{[^}]+\\})",
        Pattern.MULTILINE
    )
    private val COMMENT = Pattern.compile("#.*")
    private val KEYWORD = Pattern.compile(
        "(?m)^\\s*(?:FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val FLAG = Pattern.compile("--[\\w-]+(?:=\\S+)?")

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun create(prism4j: Prism4j): Prism4j.Grammar {
        return Prism4j.grammar(
            "dockerfile",
            // 字符串在注释之前，确保 "#..." 在字符串内不被误判为注释
            Prism4j.token("string", Prism4j.pattern(STRING_DOUBLE, false, false, "string")),
            Prism4j.token("string", Prism4j.pattern(STRING_SINGLE, false, false, "string")),
            Prism4j.token("variable", Prism4j.pattern(VARIABLE, false, false, "variable")),
            Prism4j.token("comment", Prism4j.pattern(COMMENT, false, false, "comment")),
            Prism4j.token("keyword", Prism4j.pattern(KEYWORD, false, false, "keyword")),
            Prism4j.token("flag", Prism4j.pattern(FLAG, false, false, "parameter"))
        )
    }
}