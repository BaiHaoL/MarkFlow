package com.markflow.editor.util

/**
 * 简单的可变引用容器，用于替代 Compose MutableState。
 * 写入不触发快照通知，避免滚动等高频更新导致不必要的重组。
 */
class Ref<T>(var value: T)