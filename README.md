# MarkFlow

Markdown 编辑器，专为 Android 设计。支持 Markdown 及多种文本文件的浏览、编辑、预览与管理，提供流畅的写作体验。

## 功能特性

### 文件管理
- 自动扫描设备上的 Markdown 文件（`.md` / `.markdown`）
- 支持按修改时间或文件名排序
- 文件分区：本地 / 最近打开 / 其他（非 Markdown 文本文件）
- 多选模式：批量删除
- 单文件操作：重命名、查看详情、分享
- 新建文件（自定义文件名 + 后缀选择，默认 `.md`）
- 从手机导入已有文本文档
- 保存文件后自动后台刷新列表，无闪烁更新

### 编辑器
- 编辑 / 预览模式切换（顶部单图标互切：编辑=笔、预览=眼睛，同一位置），滚动位置记忆（切换回编辑模式时保持原位置）
- 语法高亮提示（代码块、标题、列表等，覆盖 11 种 Markdown 元素）
- 撤销 / 重做（含光标位置追踪）
- 自动保存（编辑状态停止输入 3 秒后触发，不影响撤销/重做历史）
- 自动格式化：列表续行（无序/有序/复选框）、缩进保留、有序列表自动编号、空列表项清理
- 格式插件体系：Markdown、代码（语法高亮）、纯文本三种内置格式
- 手动保存，保存状态提示（未保存 / 保存中 / 已保存）
- 退出时未保存更改确认
- 插入图片（自动处理相对路径，复制到 `images/` 目录）
- 编辑模式专属功能：搜索、撤销 / 重做、插入图片按钮（图标顺序：搜索 → 撤销 → 反撤销 → 插入图片），工具按钮间隔统一
- 分段编辑兼容手写输入法（保留输入法组合区间，多笔正常成字）
- 选中文本时自动隐藏键盘，复制框不会触发输入法弹出

### 预览
- Markdown 渲染（基于 Markwon）
- 代码块语法高亮（Prism4j，30 种语言，含自定义 bash/dockerfile/diff/typescript/toml 语法）
- 表格、任务列表、删除线、上下标（~下标~ ^上标^）
- LaTeX 数学公式：行内 `$...$` 和块级 `$$...$$`；行内公式带垂直对齐补偿，与中文文字视觉中心对齐
- 图片加载（Coil）
- 不安全的图片 URL 自动过滤（如 Mi Notes 自定义 URI）
- 亮色 / 暗色主题切换

### 搜索
- 编辑模式下关键字搜索，匹配结果高亮
- 点击结果精确跳转并居中显示
- 关闭搜索后保持在当前搜索结果位置，不被光标拉回

### 多类型文件支持
- Markdown：`.md` `.markdown` — 完整编辑 + 预览
- 有语法高亮：`.sh` `.bash` `.zsh` `.py` `.c` `.h` `.cpp` `.cc` `.cxx` `.hpp` `.hxx` `.java` `.kt` `.kts` `.js` `.mjs` `.ts` `.tsx` `.go` `.rs` `.swift` `.dart` `.php` `.rb` `.lua` `.sql` `.json` `.jsonc` `.yaml` `.yml` `.toml` `.xml` `.html` `.htm` `.css` `.scss` `.less` `.dockerfile` `.docker` `.diff` `.patch` — 编辑模式 + 代码卡片预览
- 纯文本：`.txt` `.log` `.text` `.ini` `.conf` `.cfg` `.properties` `.env` — 仅编辑模式
- 大文本（>0.5MB）：自动分段只读预览，按需分页加载并回收远端分块；滚动定位记忆（全局字节偏移锚点，防抖落盘，退出自动保存）

### 跨应用支持
- 从文件管理器或其他应用打开文本类文件
- 从其他应用分享文本到 MarkFlow
- 自动处理 `content://` / `file://` URI 的文件复制
- 支持无标准 MIME 类型的文件（如 `.toml`）

### 其他
- 沉浸式模式（全屏编辑）
- 目录大纲（TOC）展示（仅查看，条目不可跳转）
- 点击标题切换主题

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | 100% Kotlin |
| UI 框架 | Jetpack Compose + Material3 |
| 架构 | MVVM + Clean Architecture |
| 依赖注入 | Hilt |
| 异步处理 | Kotlin Coroutines + Flow |
| Markdown 渲染 | Markwon |
| 语法高亮 | Prism4j |
| 图片加载 | Coil |
| 导航 | Navigation Compose |
| 文件访问 | Scoped Storage / MediaStore API |

## 项目结构

```
com.markflow.editor
├── data/
│   ├── local/          # 本地偏好存储（排序模式、主题模式）
│   └── repository/     # 文件 I/O 仓库
├── domain/
│   ├── model/          # 领域模型（EditorMode, FileType, SortMode, ThemeMode, MarkdownFile, MarkdownSection, FormatPlugin, FormatRegistry）
│   └── util/           # 领域工具（FileSorter, UndoRedoManager）
├── ui/
│   ├── components/     # 可复用组件（BottomActionBar, CodeBlockCard, MarkdownPreview, SearchBar, MarkdownSyntaxHighlighter, VerticalAlignedLatexSpan）
│   ├── navigation/     # 导航图与路由定义
│   ├── screens/
│   │   ├── editor/     # 编辑器页面 + ViewModel
│   │   └── filelist/   # 文件列表页面 + ViewModel
│   └── theme/          # Material3 主题（浅色/深色手动切换）
├── util/               # 工具类（Markwon 配置、Markdown 分段解析、目录解析、语法高亮、自动格式化、上下标/LaTeX 对齐插件、TOC 解析、编码检测、大文本分页读取、Prism4j 自定义语法）
├── io/noties/prism4j/languages/  # 自实现的 Prism4j 语法（bash, diff, dockerfile, toml, typescript）
├── MainActivity.kt     # 主 Activity
└── MarkFlowApp.kt      # Application 入口
```

## 构建

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 35
- Gradle 8.7+

### 构建命令

```powershell
# Debug 版本（未混淆，可调试）
.\gradlew.bat assembleDebug

# Release 版本（已混淆、已压缩、已签名）
.\gradlew.bat assembleRelease

# 清理构建产物
.\gradlew.bat clean
```

#### 主要区别：

| | Debug | Release |
|---|---|---|
| 代码混淆 (R8) | 关闭 | 开启 |
| 资源压缩 | 关闭 | 开启 |
| 应用 ID | `com.markflow.editor.debug` | `com.markflow.editor` |
| 版本名 | `1.2.0-debug` | `1.2.0` |
| 签名 | Debug 签名（自动） | Release 签名（keystore） |
| 可调试 | 是 | 否 |
| 日志输出 | 完整（含 verbose/debug） | 仅 error / warn（debug/info 日志被 R8 移除） |

### 产物位置

| 版本 | 路径 |
|------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

### LaTeX 渲染与混淆

JLaTeXMath 引擎内部大量使用反射（`Class.forName` / `getMethod` 等），release 混淆必须保留以下类，否则块级公式渲染会失败（NPE）：

```properties
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }
```

同时 `-keep class io.noties.markwon.** { *; }` 保留了 markwon-latex 的 drawable 类，保证行内公式垂直对齐修复（通过反射调用 `isBlock()` 区分行内/块级公式）在 release 中正常工作。

### Release 签名

Release 构建需要签名密钥。在项目根目录创建 `keystore.properties`：

```properties
storeFile=your-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

## 最低要求

- Android 8.0 (API 26) 及以上