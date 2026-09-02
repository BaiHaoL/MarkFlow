# ============================================================
# MarkFlow ProGuard 混淆规则
# ============================================================

# -------------------- Kotlin 基础 --------------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# -------------------- 日志移除（仅 release） --------------------
# release（R8 混淆）构建中移除 verbose/debug/info 级别日志，避免正常使用时
# 持续产出日志；保留 warn/error 便于排障。debug 构建不混淆，不受影响。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# -------------------- Hilt 依赖注入 --------------------
# 保留所有 Hilt 库类
-keep class dagger.hilt.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class javax.annotation.** { *; }

# 保留 Hilt 生成的组件类（在应用包名下，使用反射按名称查找）
-keep class com.markflow.editor.DaggerMarkFlowApp_HiltComponents_* { *; }
-keep class com.markflow.editor.MarkFlowApp_HiltComponents { *; }
-keep class com.markflow.editor.MarkFlowApp_HiltComponents$* { *; }
-keep class com.markflow.editor.Hilt_MarkFlowApp { *; }
-keep class com.markflow.editor.Hilt_MainActivity { *; }

# 保留 @Module 和 @InstallIn 注解的类
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @javax.inject.Inject class * { *; }
-keep @javax.inject.Singleton class * { *; }

# 保留 Hilt 注入的 ViewModel（构造函数通过反射调用）
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# 保留 Hilt 内部 FragmentContextWrapper 子类
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# -------------------- Markwon Markdown 渲染 --------------------
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }

# -------------------- JLaTeXMath 数学公式渲染 --------------------
# JLaTeXMath 引擎内部大量使用反射（Class.forName / getConstructor / newInstance /
# getMethod 等按字符串名称调用），R8 混淆会导致 release 版块级公式渲染失败。
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }

# -------------------- Prism4j 语法高亮 --------------------
-keep class io.noties.prism4j.** { *; }
-keep class * extends io.noties.prism4j.GrammarLocator { *; }
-keep class com.markflow.editor.util.Prism4jBundle { *; }
-keep class com.markflow.editor.util.MarkFlowGrammarLocator { *; }
-keep class com.markflow.editor.util.AliasedGrammarLocator { *; }

# -------------------- Coil 图片加载 --------------------
-keep class coil.** { *; }
-dontwarn coil.**

# -------------------- Compose 运行时 --------------------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# -------------------- 应用自身的类 --------------------
# 数据模型（通过 MarkdownFile 等序列化/反射使用）
-keep class com.markflow.editor.domain.model.** { *; }
# 应用入口
-keep class com.markflow.editor.MarkFlowApp { *; }
-keep class com.markflow.editor.MainActivity { *; }

# -------------------- 其他 --------------------
# 保留序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留 R 类中的资源 ID
-keepclassmembers class **.R$* {
    public static <fields>;
}