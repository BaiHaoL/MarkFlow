package com.markflow.editor.ui.navigation

/**
 * 导航路由定义
 * 使用 sealed class 确保类型安全的导航
 */
sealed class Screen(val route: String) {
    /**
     * 文件列表首页
     */
    data object FileList : Screen("file_list")

    /**
     * 编辑/预览页
     * @param fileUri 文件的 URI 字符串（需要 URL 编码传递）
     */
    data object Editor : Screen("editor/{fileUri}") {
        /**
         * 构建带参数的完整路由路径
         */
        fun createRoute(fileUri: String): String {
            val encoded = try {
                java.net.URLEncoder.encode(fileUri, "UTF-8")
            } catch (_: Exception) {
                fileUri
            }
            return "editor/$encoded"
        }
    }
}