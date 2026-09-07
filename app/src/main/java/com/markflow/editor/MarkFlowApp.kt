package com.markflow.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import dagger.hilt.android.HiltAndroidApp

/**
 * MarkFlow Application 入口
 * 标注 @HiltAndroidApp 以启用 Hilt 依赖注入
 */
@HiltAndroidApp
class MarkFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(memoryTrimCallback)
    }

    /**
     * 内存吃紧回调：低内存/中端机（荣耀 X50 等 MagicOS 激进回收）上，
     * 系统通过 onTrimMemory / onLowMemory 通知应用释放缓存，降低被 LMK 杀进程
     * / OOM 的概率。此处释放 Markwon 渲染实例缓存（预览时按需重建）。
     */
    private val memoryTrimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            ) {
                com.markflow.editor.util.MarkwonConfig.clearCache()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
            com.markflow.editor.util.MarkwonConfig.clearCache()
        }
    }
}