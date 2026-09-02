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

    companion object {
        private const val PREFS_NAME = "markflow_preferences"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HIDDEN_URIS = "hidden_uris"
    }
}