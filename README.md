# MarkFlow

Markdown 编辑器，专为 Android 设计。支持 Markdown 及多种文本文件的浏览、编辑、预览与管理，提供流畅的写作体验。

## 功能特性

### 文件管理
- 自动扫描文档目录（`Documents/`、`Download/`）中的 Markdown 及文本文件，不扫描全盘
- 自动排除视频 / 音频 / 图片等媒体文件（如 `.ts` 视频），仅显示文本类文件
- **三区互斥列表（从上到下）**：「最近打开」→「Markdown」→「其他」，每个文件只出现在一个分区
  - **「最近打开」**：FIFO-5 队列（长度常量可调）。任何方式打开文件（列表点开 / 外部打开 / 分享 / 新建 / 导入）都会入队——已在队则置顶、不在则插队首、超 5 个挤出最旧者；被挤出的文件按类型回到「Markdown」或「其他」。队列顺序不受排序方式影响
  - **「Markdown」**：`.md` / `.markdown` 文件（排除已在最近队列中的）
  - **「其他」**：其余文本类文件（代码 / 配置 / 纯文本等，排除已在最近队列中的）
- 支持多选：批量分享、批量删除；多文件分享走 `ACTION_SEND_MULTIPLE`
- 排序：按修改时间 / 按文件名（作用于 Markdown 与其他分区）
- **星标收藏**：点击文件名左侧黄色星标即可收藏 / 取消。星标文件在「Markdown」与「其他」分区内**始终置顶**（星标组不被排序模式打破，组内顺序随排序模式改变）；「最近打开」分区不受星标影响（顺序仍按打开先后，仅显示星标标记）。删除 / 隐藏文件时自动同步清除其星标
- 单文件操作：重命名（可改后缀，后缀变化弹「更改扩展名」确认提示）、查看详情、分享
- 新建文件（自定义文件名 + 后缀选择，默认 `.md`，约 21 种后缀）
- 从手机导入已有文本文档（原子化写入 + 同名去重）
- 保存文件后自动后台刷新列表，无闪烁更新
- 外部文件来源统一净化文件名（防路径穿越）后导入应用私有目录

### 编辑器
- 编辑 / 预览模式切换：顶部**单图标互切**——编辑态显示"眼睛"（点击切到预览）、预览态显示"笔"（点击切回编辑）；滚动位置跨模式切换保留
- 语法高亮提示（代码块、标题、列表等，覆盖 11 种 Markdown 元素）；按文档大小分流：≤64K 字符同步即时计算，>64K 字符后台线程 + 80ms 防抖异步计算（输入不卡顿）；超长文档自动降级高亮（跳过列表/引用/分隔线）；围栏代码块内部屏蔽所有高亮规则
- 撤销 / 重做（含光标位置追踪），仅编辑模式显示
- 自动保存（编辑停止输入 3 秒触发，不影响撤销/重做历史）
- 自动格式化：列表续行（无序/有序/复选框）、缩进保留、有序列表自动编号、空列表项清理；仅 `.md` 文件启用
- 手动保存 + 保存状态提示（未保存 / 保存中 / 已保存）
- 退出时未保存更改确认（「保存并退出」/「放弃更改」）
- **插入图片**：复制到 `.md` 同级 `images/` 目录（压缩长边 2048px、JPEG 质量 85、命名 `{时间戳}_{随机4位}.jpg`）；写入后自动放置 `.nomedia` 并触发媒体重扫，**复制来的图片不会出现在系统相册**；删除 `.md` 时自动清理该目录下不再被任何现存 `.md` 引用的自动命名图片副本（用户自放的图片绝不误删）
- 编辑工具栏图标顺序：**搜索 → 撤销 → 反撤销 → 插入图片**（均为编辑模式专属）
- 分段编辑兼容手写输入法（保留输入法组合区间，多笔正常成字）
- 选中文本时自动隐藏键盘，避免复制框弹出输入法
- 搜索：关键字匹配高亮、点击结果精确跳转并居中、关闭后保持当前位置不被光标拉回

### 预览
- Markdown 渲染（Markwon）
- 代码块语法高亮（Prism4j，覆盖 30 种语言，含自定义 bash / dockerfile / diff / typescript / toml 语法）
- 表格、任务列表、删除线、上下标（`~下标~` `^上标^`）
- LaTeX 数学公式：行内 `$...$` 与块级 `$$...$$`；行内公式带垂直对齐补偿，与中文文字视觉中心对齐
- 图片加载（Coil）；相对路径图片基于 `.md` 物理路径解析；缺失图片回退灰色占位
- 不安全的图片 URL 自动过滤（如 Mi Notes 自定义 URI）
- 亮色 / 暗色主题切换（点击标题栏切换）

### 大文件支持（≥512KB，统一阈值）
- 大文本与大 Markdown **统一按 512KB 分档**，全部走「分页只读浏览 + 分段编辑」，**不全文载入内存**（避免超大文档渲染崩溃与卡顿）
- 浏览：连续分页只读、按需加载并回收远端分块；支持手动切换编码；阅读位置记忆（全局字节偏移锚点，防抖落盘，退出自动保存）
- 编辑：长按某段进入该段的单块可写编辑（分页内核保证单行超长按字节切块、内存受控）
- 大 Markdown 打开默认预览分页浏览，行为与大 TXT 完全一致；超大 md 不再触发全文渲染/高亮崩溃

### 支持的文件类型
- **Markdown**（`.md` `.markdown`）：完整编辑 + 预览 + TOC
- **有语法高亮**（`.sh` `.bash` `.zsh` `.py` `.c` `.h` `.cpp` `.cc` `.cxx` `.hpp` `.hxx` `.java` `.kt` `.kts` `.js` `.mjs` `.ts` `.tsx` `.go` `.rs` `.swift` `.dart` `.php` `.rb` `.lua` `.sql` `.json` `.jsonc` `.yaml` `.yml` `.toml` `.xml` `.html` `.htm` `.css` `.scss` `.less` `.ini` `.conf` `.cfg` `.properties` `.env` `.dockerfile` `.docker` `.diff` `.patch`）：编辑模式 + 代码卡片预览
- **纯文本**（`.txt` `.log` `.text`）：仅编辑模式

### 跨应用支持
- 从文件管理器或其他应用打开文本类文件
- 从其他应用分享文本 / 文件到 MarkFlow（自动复制到私有目录后打开）
- 自动处理 `content://` / `file://` URI
- 支持无标准 MIME 类型的文件（如 `.toml`）
- 未注册扩展名 / 无扩展名的文件源头拒绝打开

### 其他
- 沉浸式模式（全屏编辑）
- 目录大纲（TOC）展示（仅查看，条目暂不可跳转；大 Markdown 隐藏）
- 目录 / 阅读位置记忆

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | 100% Kotlin |
| UI 框架 | Jetpack Compose + Material3 |
| 架构 | MVVM + Clean Architecture |
| 依赖注入 | Hilt（编译器走 KSP） |
| 异步处理 | Kotlin Coroutines + Flow |
| Markdown 渲染 | Markwon（latex / tables / tasklist / strikethrough / superscript / subscript 插件） |
| 语法高亮 | Prism4j（kapt 生成语法定义） |
| LaTeX 渲染 | JLaTeXMath |
| 图片加载 | Coil |
| 导航 | Navigation Compose |
| 文件访问 | Scoped Storage / MediaStore / DocumentFile API |

## 项目结构

```
com.markflow.editor
├── data/
│   ├── local/          # 本地偏好存储（排序、主题、最近打开队列）
│   └── repository/     # 文件 I/O 仓库（扫描、读写、MediaStore/DocumentFile、编码检测、大文件分页）
├── domain/
│   ├── model/          # 领域模型（FileType/FormatRegistry/SortMode/ThemeMode/EditorMode/MarkdownFile 等）
│   └── util/           # 领域工具（FileSorter、UndoRedoManager）
├── ui/
│   ├── components/     # 可复用组件（BottomActionBar、CodeBlockCard、MarkdownPreview、SearchBar、MarkdownSyntaxHighlighter、VerticalAlignedLatexSpan）
│   ├── navigation/     # 导航图与路由定义
│   ├── screens/
│   │   ├── editor/     # 编辑器页面 + ViewModel（含大文件分页只读 / 分段编辑）
│   │   └── filelist/   # 文件列表页面 + ViewModel（三区互斥分区）
│   └── theme/          # Material3 主题（浅色/深色手动切换）
├── util/               # 工具（Markwon 配置、Markdown 解析、TOC、语法高亮、自动格式化、上下标/LaTeX 对齐插件、编码检测、大文本分页读取、Prism4j 自定义语法）
├── io/noties/prism4j/languages/  # 自实现的 Prism4j 语法（bash, diff, dockerfile, toml, typescript）
├── MainActivity.kt     # 主 Activity（Intent 分发 / 分享接入）
└── MarkFlowApp.kt      # Application 入口（Hilt）
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

> 若构建报 Kotlin daemon 连接失败（`AccessDeniedException` / `Could not connect to Kotlin compile daemon`），先 `.\gradlew.bat --stop` 再重试。

### Debug 与 Release 区别

| | Debug | Release |
|---|---|---|
| 代码混淆 (R8) | 关闭 | 开启 |
| 资源压缩 | 关闭 | 开启 |
| 应用 ID | `com.markflow.editor.debug` | `com.markflow.editor` |
| 版本名 | `1.2.0-debug` | `1.2.0` |
| 签名 | Debug 签名（自动） | Release 签名（keystore） |
| 可调试 | 是 | 否 |
| 日志 | 完整 | R8 移除 `Log.v/d/i`，仅保留 `Log.w/e`（排障用） |

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

Release 构建需要签名密钥。签名文件存放在**用户家目录** `~/.markflow/`（不在项目仓库内，避免密钥入库）：

```properties
# 文件位置：~/.markflow/keystore.properties
storeFile=markflow-release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

- `storeFile` 支持相对路径（相对于 `~/.markflow/`）或绝对路径
- 若 `~/.markflow/keystore.properties` 不存在、缺少必需键或 keystore 文件不存在，Release 打包会**直接失败**（不会静默回退到 debug 签名）；不影响 Debug 构建与 IDE 同步

### 日志策略
- Release 用 R8（`-assumenosideeffects`）移除 `Log.v/d/i`，仅保留 `Log.w/e/wtf`
- App 源码中的日志**全部为异常分支**（文件读写失败、MediaStore 异常、公式渲染失败等），**正常运行无错误时不产生任何日志**；错误日志量级极小且属有用排障信息，不会占用设备存储（logcat 为内存环形缓冲，不持续写盘）

## 最低要求

- Android 8.0 (API 26) 及以上
