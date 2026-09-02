// MarkFlow 根项目构建脚本
// 声明全局插件但不应用，由子模块按需引用

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kapt) apply false
}