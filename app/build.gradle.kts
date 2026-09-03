import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kapt)
}

// 加载签名配置
// 签名文件必须放在仓库外（用户家目录 .markflow/），禁止驻留仓库根。
// 经 providers.fileContents 读取：注册为配置输入，文件变化时配置缓存自动失效。
val keystorePropertiesFile = File(System.getProperty("user.home"), ".markflow/keystore.properties")
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val keystoreProperties = Properties().apply {
    val text = providers.fileContents(
        objects.fileProperty().apply { set(keystorePropertiesFile) }
    ).asText.orNull
    if (text != null) load(text.reader())
}
val hasCompleteSigningProperties = keystorePropertiesFile.isFile && requiredSigningKeys.all {
    !keystoreProperties.getProperty(it).isNullOrBlank()
}

android {
    namespace = "com.markflow.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.markflow.editor"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasCompleteSigningProperties) {
                signingConfigs.create("release") {
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                    val configuredStoreFile = keystoreProperties.getProperty("storeFile")
                    storeFile = if (File(configuredStoreFile).isAbsolute) {
                        File(configuredStoreFile)
                    } else {
                        // storeFile 支持相对路径（相对于 keystore.properties 所在目录）
                        File(keystorePropertiesFile.parentFile, configuredStoreFile)
                    }
                    storePassword = keystoreProperties.getProperty("storePassword")
                }
            } else {
                // 不在配置期 error（否则 assembleDebug / IDE sync / 配置缓存全部受牵连）；
                // 文件缺失、缺键或 keystore 不存在在执行期由 validateReleaseSigning 拦截（见文件末尾）
                null
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 全局排除冲突的 annotations-java5（由 Prism4j 传递引入，与 Kotlin 内置 annotations 冲突）
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

// 执行期签名校验：仅在真正打包 release 时失败，
// 不阻塞 assembleDebug / IDE sync / ./gradlew tasks 等其他任务。
// 独立 Task 类型避免 doFirst 闭包捕获构建脚本对象，保持 configuration cache 兼容。
abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val keystorePropertiesPath: Property<String>

    @TaskAction
    fun validate() {
        val propertiesFile = File(keystorePropertiesPath.get())
        if (!propertiesFile.isFile) {
            throw GradleException(
                "Release signing config not found at $propertiesFile. " +
                    "Please place keystore.properties and the keystore file under ~/.markflow/"
            )
        }

        val properties = Properties().apply {
            propertiesFile.inputStream().use { load(it) }
        }
        val missingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .filter { properties.getProperty(it).isNullOrBlank() }
        if (missingKeys.isNotEmpty()) {
            throw GradleException(
                "keystore.properties 缺少必需键 ${missingKeys.joinToString()}（$propertiesFile）"
            )
        }

        val configuredStoreFile = properties.getProperty("storeFile")
        val storeFile = File(configuredStoreFile).let {
            if (it.isAbsolute) it else File(propertiesFile.parentFile, configuredStoreFile)
        }
        if (!storeFile.isFile) {
            throw GradleException("Release keystore file not found at $storeFile")
        }
    }
}

val validateReleaseSigning = tasks.register<ValidateReleaseSigningTask>("validateReleaseSigning") {
    keystorePropertiesPath.set(keystorePropertiesFile.absolutePath)
    // 签名校验成本极低，始终执行，避免 keystore 文件变化后任务被误判 up-to-date
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    // -------------------- AndroidX 核心 --------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    // -------------------- Compose BOM --------------------
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // -------------------- Hilt 依赖注入 --------------------
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // -------------------- Markwon Markdown 渲染 --------------------
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.markwon.image.coil)
    implementation(libs.markwon.html)
    implementation(libs.markwon.latex)

    // Prism4j 语法高亮 —— kapt 生成 GrammarLocator
    kapt(libs.prism4j.bundler)

    // -------------------- Coil 图片加载 --------------------
    implementation(libs.coil.compose)

    // -------------------- 测试 --------------------
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
}