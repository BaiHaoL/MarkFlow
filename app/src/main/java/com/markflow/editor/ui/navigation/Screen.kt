package com.markflow.editor.ui.navigation

/**
 * 导航路由定义
 * 使用 sealed class 确保类型安全的导航
 *
 * 编辑器 URI 不通过路由参数传递（X-H4 教训）：
 * 路由参数是 URI 路径段，需经编码/解码往返，Navigation 各版本对
 * %XX / + / # 的解码行为存在差异，含特殊字符的文件名
 * （如 "C++"、"# 开头"）易在往返中被破坏导致文件打不开（编辑器空白）。
 * 现改为 savedStateHandle 传递：字符串原样往返，不经任何编解码。
 */
sealed class Screen(val route: String) {
    /**
     * 文件列表首页
     */
    data object FileList : Screen("file_list")

    /**
     * 编辑/预览页（无路由参数）
     *
     * 目标 URI 通过列表页 backStackEntry 的 savedStateHandle 以
     * [EDITOR_FILE_URI_KEY] 传入，编辑器从 previousBackStackEntry 读取。
     */
    data object Editor : Screen("editor")

    companion object {
        /** savedStateHandle 中传递编辑器目标 URI 的 key */
        const val EDITOR_FILE_URI_KEY = "editor_file_uri"
    }
}
