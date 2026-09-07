package com.markflow.editor.ui.screens.editor

/**
 * 空白文件占位模板：为各种可创建的扩展名提供"打开空白文件时"的提示/骨架。
 *
 * 规则：
 * - 仅当文件内容为空时显示（由调用方按 `text.isEmpty()` 判定）。
 * - `forExtension(ext)` 返回占位纯文本；返回 null 表示该类型不显示占位（如未注册扩展名、无扩展名）。
 * - 占位为纯文本、不高亮，样式统一灰色 monospace（由渲染处决定）。
 * - `.md` 保留原有 Markdown 语法参考；其余按语言族分组提供"引导语 + 骨架"。
 */
object PlaceholderTemplates {

    /** Markdown 空白文件占位：语法参考（原有内容，保持不变） */
    val MARKDOWN: String = """
        |开始编写 Markdown 文档…
        |
        |语法参考：
        |# 一级标题
        |## 二级标题
        |**加粗文字**
        |*斜体文字*
        |- 无序列表项
        |1. 有序列表项
        |> 引用文本
        |```
        |代码块
        |```
        |[链接文字](https://example.com)
        |![图片描述](images/xxx.png)
    """.trimMargin()

    private val C_CPP = """
        |C 源文件：包含头文件并在此编写逻辑
        |#include <stdio.h>
        |int main(void) {
        |    // 在此编写代码
        |    return 0;
        |}
    """.trimMargin()

    private val PYTHON = """
        |Python 文件：输入代码从这里开始
        |def main():
        |    pass
        |if __name__ == "__main__":
        |    main()
    """.trimMargin()

    private val JAVA = """
        |Java 文件：定义一个公开类并编写逻辑
        |public class Main {
        |    public static void main(String[] args) {
        |        // 在此编写代码
        |    }
        |}
    """.trimMargin()

    private val KOTLIN = """
        |Kotlin 文件：从 main 函数开始编写
        |fun main() {
        |    // 在此编写代码
        |}
    """.trimMargin()

    private val JS_TS = """
        |JavaScript 文件：在此声明函数并调用
        |function main() {
        |    // 在此编写代码
        |}
        |main();
    """.trimMargin()

    private val HTML = """
        |HTML 文档：填写内容到 body 中
        |<!DOCTYPE html>
        |<html>
        |<head><meta charset="utf-8"></head>
        |<body>
        |    <!-- 在此编写内容 -->
        |</body>
        |</html>
    """.trimMargin()

    private val CSS = """
        |CSS 样式：为元素编写样式规则
        |/* 例如： */
        |body {
        |    margin: 0;
        |}
    """.trimMargin()

    private val JSON = """
        |JSON 数据：以对象或数组形式填写
        |{
        |  "key": "value"
        |}
    """.trimMargin()

    private val XML = """
        |XML 文档：以根元素包裹内容
        |<root>
        |    <item>值</item>
        |</root>
    """.trimMargin()

    private val YAML = """
        |YAML 数据：用缩进组织键值
        |key: value
        |list:
        |  - item1
    """.trimMargin()

    private val SHELL = """
        |Shell 脚本：声明解释器并编写命令
        |#!/bin/sh
        |echo "Hello"
    """.trimMargin()

    private val SQL = """
        |SQL 语句：在此编写查询或建表
        |SELECT * FROM table_name;
    """.trimMargin()

    private val INI = """
        |INI 配置：用节和 key=value 组织
        |[通用]
        |name = value
    """.trimMargin()

    private val TEXT = """
        |在此输入文本内容
    """.trimMargin()

    /** 扩展名（小写，不含点）→ 占位文本。未注册/无扩展名返回 null（不显示占位）。 */
    fun forExtension(ext: String): String? = when (ext) {
        "md", "markdown" -> MARKDOWN
        "c", "h", "cpp", "hpp" -> C_CPP
        "py" -> PYTHON
        "java" -> JAVA
        "kt" -> KOTLIN
        "js", "ts" -> JS_TS
        "html", "htm" -> HTML
        "css" -> CSS
        "json", "jsonc" -> JSON
        "xml" -> XML
        "yml", "yaml" -> YAML
        "sh", "bash", "zsh" -> SHELL
        "sql" -> SQL
        "ini", "conf", "cfg", "properties", "env" -> INI
        "txt", "log", "text" -> TEXT
        else -> null
    }
}