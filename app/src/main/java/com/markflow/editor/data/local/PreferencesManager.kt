package com.markflow.editor.data.local

import android.content.Context
import android.content.SharedPreferences
import com.markflow.editor.domain.model.SortMode
import com.markflow.editor.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences 管理器
 * 负责持久化用户偏好设置：排序模式、主题模式等
 *
 * @param context 应用上下文（由 Hilt 注入）
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== 排序模式 ====================

    /**
     * 获取当前排序模式，默认为按修改时间倒序
     */
    fun getSortMode(): SortMode {
        val name = prefs.getString(KEY_SORT_MODE, SortMode.BY_TIME_DESC.name)
            ?: SortMode.BY_TIME_DESC.name
        return try {
            SortMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            SortMode.BY_TIME_DESC
        }
    }

    /**
     * 保存排序模式
     */
    fun setSortMode(mode: SortMode) {
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    // ==================== 主题模式 ====================

    /**
     * 获取主题模式，默认为浅色
     */
    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.LIGHT.name)
            ?: ThemeMode.LIGHT.name
        return try {
            ThemeMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.LIGHT
        }
    }

    /**
     * 保存主题模式
     */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    // ==================== 隐藏文件（仅从列表移除，不删除文件） ====================

    /**
     * 获取已从列表隐藏的文件 URI 集合
     */
    fun getHiddenUris(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_URIS, emptySet()) ?: emptySet()
    }

    /**
     * 将文件 URI 加入隐藏集合（仅从列表移除，不删除文件）
     */
    fun addHiddenUris(uris: Collection<String>): Boolean {
        val current = getHiddenUris().toMutableSet()
        current.addAll(uris)
        return prefs.edit().putStringSet(KEY_HIDDEN_URIS, current).commit()
    }

    /**
     * 从隐藏集合移除文件 URI（物理删除后清理，防止 URI 复用导致误隐藏）
     */
    fun removeHiddenUris(uris: Collection<String>): Boolean {
        val current = getHiddenUris().toMutableSet()
        current.removeAll(uris)
        return prefs.edit().putStringSet(KEY_HIDDEN_URIS, current).commit()
    }

    // ==================== 大 txt 阅读位置与书签（按文件 uri 分 key） ====================

    /** 保存文件上次阅读位置（全局字节偏移锚点），退出编辑器后重开可恢复 */
    fun savePagedReadPosition(uri: String, byteOffset: Long) {
        prefs.edit().putLong(readPosKey(uri), byteOffset).apply()
    }

    /** 读取文件上次阅读位置；从未记过返回 null */
    fun getPagedReadPosition(uri: String): Long? {
        val key = readPosKey(uri)
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    private fun readPosKey(uri: String) = "read_pos_$uri"

    // ==================== 星标文件（无序集合；组内顺序交由全局排序决定） ====================

    /**
     * 获取星标文件 URI 集合（无序）。
     * 星标组始终在各自分区内置顶；组内顺序由全局 SortMode 决定，故无需维护打星时序。
     */
    fun getStarredUris(): Set<String> {
        return prefs.getStringSet(KEY_STARRED_URIS, emptySet()) ?: emptySet()
    }

    /**
     * 设置/取消指定文件的星标状态。
     * @param starred true=打星（加入集合），false=取消（移除）
     */
    fun setStarred(uri: String, starred: Boolean) {
        val current = getStarredUris().toMutableSet()
        if (starred) current.add(uri) else current.remove(uri)
        prefs.edit().putStringSet(KEY_STARRED_URIS, current).apply()
    }

    /**
     * 从星标集合移除指定文件（物理删除/隐藏时调用，避免残留失效 URI）。
     */
    fun removeStarredUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val current = getStarredUris().toMutableSet()
        current.removeAll(uris)
        prefs.edit().putStringSet(KEY_STARRED_URIS, current).apply()
    }

    /**
     * 重命名成功后将文件的星标与「最近打开」状态从旧 uri 迁移到新 uri。
     * 仅 file:// 路径（私有导入文件）重命名会改变 uri，content:// 原地 update 不变化、
     * 无需迁移（uri 相同时本方法为无操作）。oldUri == newUri 或均未命中时不做任何写盘。
     */
    fun migrateUri(oldUri: String, newUri: String) {
        if (oldUri == newUri) return
        // 星标
        val stars = getStarredUris().toMutableSet()
        if (oldUri in stars) {
            stars.remove(oldUri)
            stars.add(newUri)
            prefs.edit().putStringSet(KEY_STARRED_URIS, stars).apply()
        }
        // 最近打开队列
        val recents = getRecentUris().toMutableList()
        val idx = recents.indexOf(oldUri)
        if (idx >= 0) {
            recents[idx] = newUri
            persistRecentUris(recents)
        }
    }

    // ==================== 最近打开队列（「最近打开」分区，FIFO 有界队列） ====================

    /**
     * 读取最近打开的文件 URI 队列（按最近打开顺序，队首为最新）。
     * 返回的列表为主调用方排序依据，顺序即"最近优先"。
     */
    fun getRecentUris(): List<String> {
        val raw = prefs.getString(KEY_RECENT_URIS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 将文件 URI 入「最近打开」队列：若已在队列则移到队首，否则插入队首；
     * 超过 [RECENT_QUEUE_SIZE] 时挤出最旧条目。返回更新后（已修剪）的队列。
     */
    fun pushRecentUri(uri: String): List<String> {
        val list = getRecentUris().toMutableList().apply { remove(uri) }
        list.add(0, uri)
        val trimmed = if (list.size > RECENT_QUEUE_SIZE) list.subList(0, RECENT_QUEUE_SIZE).toList() else list
        persistRecentUris(trimmed)
        return trimmed
    }

    /**
     * 从「最近打开」队列移除指定文件（物理删除/隐藏时调用，避免残留失效条目）。
     */
    fun removeRecentUris(uris: Collection<String>): List<String> {
        if (uris.isEmpty()) return getRecentUris()
        val list = getRecentUris().toMutableList().apply { removeAll(uris) }
        persistRecentUris(list)
        return list
    }

    private fun persistRecentUris(list: List<String>) {
        prefs.edit().putString(KEY_RECENT_URIS, org.json.JSONArray(list).toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "markflow_preferences"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HIDDEN_URIS = "hidden_uris"
        private const val KEY_STARRED_URIS = "starred_uris"
        private const val KEY_RECENT_URIS = "recent_uris"

        /** 「最近打开」队列最大长度；语义为"文件被挤出最近打开前，有 N 次其它打开压过它"。可后续调整 */
        const val RECENT_QUEUE_SIZE = 5
    }
}