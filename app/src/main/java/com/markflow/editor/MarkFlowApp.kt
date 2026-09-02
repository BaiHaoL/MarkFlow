package com.markflow.editor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * MarkFlow Application 入口
 * 标注 @HiltAndroidApp 以启用 Hilt 依赖注入
 */
@HiltAndroidApp
class MarkFlowApp : Application()