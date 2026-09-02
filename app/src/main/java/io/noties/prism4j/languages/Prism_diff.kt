package io.noties.prism4j.languages

import io.noties.prism4j.Prism4j
import java.util.regex.Pattern.MULTILINE
import java.util.regex.Pattern.compile

/**
 * Diff / Patch 语法高亮
 * 基于 Prism.js diff 语法定义翻译
 */
@Suppress("unused")
object Prism_diff {

    private val COORD = compile("^@@[\\-+\\d,\\s]+@@.*$", MULTILINE)
    private val INSERTED = compile("^\\+.*$", MULTILINE)
    private val DELETED = compile("^\\-.*$", MULTILINE)
    private val DIFF_HEADER = compile(
        "^(?:diff\\s|index\\s|---\\s|\\+\\+\\+\\s|old mode|new mode|deleted file mode|new file mode|copy from|copy to|rename from|rename to|similarity index|dissimilarity index|Binary files).*$",
        MULTILINE
    )

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun create(prism4j: Prism4j): Prism4j.Grammar {
        return Prism4j.grammar(
            "diff",
            Prism4j.token("coord", Prism4j.pattern(COORD, false, false, "function")),
            Prism4j.token("deleted", Prism4j.pattern(DELETED, false, false, "deleted")),
            Prism4j.token("inserted", Prism4j.pattern(INSERTED, false, false, "inserted")),
            Prism4j.token("diff", Prism4j.pattern(DIFF_HEADER, false, false, "bold"))
        )
    }
}