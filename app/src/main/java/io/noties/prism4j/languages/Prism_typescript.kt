package io.noties.prism4j.languages

import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * TypeScript 语法高亮
 *
 * 在 JavaScript 基础上扩展类型系统关键字。
 * 关键设计原则：
 * - 所有模式使用 lookbehind=false，避免捕获组被排除导致高亮截断
 * - 优先匹配完整语法结构（template-string、regex），再匹配通用模式
 * - 关键字列表覆盖 ES6+ 和 TypeScript 特有语法
 */
@Suppress("unused")
object Prism_typescript {

    private val TEMPLATE_STRING = Pattern.compile("`(?:[^`\\\\]|\\\\.)*`")
    private val STRING_DOUBLE = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val STRING_SINGLE = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val REGEX = Pattern.compile("/(?!\\*)[^\\[\\s/\\\\][^/\\\\\\r\\n]*/[gimsuy]*")
    private val MULTI_LINE_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val SINGLE_LINE_COMMENT = Pattern.compile("//[^\\r\\n]*")
    private val DECORATOR = Pattern.compile("@[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\(?")
    private val KEYWORD = Pattern.compile(
        "\\b(?:break|case|catch|continue|debugger|default|delete|do|else|finally|for|function|if|in|instanceof|new|return|switch|this|throw|try|typeof|var|void|while|with|class|const|enum|export|extends|import|super|implements|interface|let|package|private|protected|public|static|yield|async|await|from|as|type|namespace|declare|abstract|readonly|is|keyof|infer|never|unknown|asserts|intrinsic|global|module|require|of|get|set|any|boolean|string|number|symbol|void|object|bigint|undefined|null|true|false)\\b"
    )
    private val NUMBER = Pattern.compile(
        "(?:\\b0[xX][\\da-fA-F]+(?:n)?|\\b0[bB][01]+(?:n)?|\\b0[oO][0-7]+(?:n)?|\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?:n)?)",
        Pattern.MULTILINE
    )
    private val OPERATOR = Pattern.compile(
        "[+\\-*/%&|^~!<>=?:;,.(){}\\[\\]]|&&|\\|\\||<<|>>|>>>|===|!==|=>|\\.\\.\\."
    )

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun create(prism4j: Prism4j): Prism4j.Grammar {
        return Prism4j.grammar(
            "typescript",
            // 先匹配完整结构（template-string、regex、comment），再匹配通用 token
            Prism4j.token("template-string", Prism4j.pattern(TEMPLATE_STRING, false, false, "string")),
            Prism4j.token("regex", Prism4j.pattern(REGEX, false, false, "regex")),
            Prism4j.token("string", Prism4j.pattern(STRING_DOUBLE, false, false, "string")),
            Prism4j.token("string", Prism4j.pattern(STRING_SINGLE, false, false, "string")),
            Prism4j.token("comment", Prism4j.pattern(MULTI_LINE_COMMENT, false, false, "comment")),
            Prism4j.token("comment", Prism4j.pattern(SINGLE_LINE_COMMENT, false, false, "comment")),
            Prism4j.token("decorator", Prism4j.pattern(DECORATOR, false, false, "function")),
            Prism4j.token("keyword", Prism4j.pattern(KEYWORD, false, false, "keyword")),
            Prism4j.token("number", Prism4j.pattern(NUMBER, false, false, "number")),
            Prism4j.token("operator", Prism4j.pattern(OPERATOR))
        )
    }
}