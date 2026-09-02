package com.markflow.editor.domain.model

/**
 * 文件列表排序模式
 * 支持按修改时间倒序和按文件名排序两种方式
 */
enum class SortMode(val displayName: String) {
    /**
     * 按修改时间倒序排列（最新修改在前）
     */
    BY_TIME_DESC("按修改时间排序"),

    /**
     * 按文件名字母顺序排列
     */
    BY_NAME("按文件名排序")
}