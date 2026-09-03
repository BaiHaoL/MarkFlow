# AGENTS.md — MarkFlow 项目代理指南

本文件是 AI 编程代理（Agent）在此仓库工作时的**唯一权威指导文档**，长期规范、技术方案、进度与踩坑全部收敛于此，不再拆分多个 docs 文档。**修改任何代码前请先读全文**，重点「硬性约束」「已知陷阱」，避免重复踩坑。

## 1. 项目简介

MarkFlow 是一款 Android Markdown 编辑器：浏览 / 编辑 / 预览 / 管理 Markdown 及多种文本文件。基于 Jetpack Compose，用 Markwon 渲染 Markdown、Prism4j 做语法高亮、JLaTeXMath 渲染 LaTeX、Coil 加载图片，支持大 TXT 分段编辑与预览。

## 2. 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | **100% Kotlin，禁止 Java** |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM + Clean Architecture |
| 依赖注入 | Hilt |
| 异步 | Kotlin Coroutines + Flow |
| Markdown 渲染 | Markwon（latex / tables / tasklist / strikethrough / superscript / subscript 插件） |
| 语法高亮 | Prism4j（kapt 生成语法定义） |
| 图片加载 | Coil |
| 导航 | Navigation Compose |
| 文件访问 | Scoped Storage / MediaStore / DocumentFile API |

## 3. 构建

```powershell
.\gradlew.bat assembleDebug    # Debug，未混淆，日志完整
.\gradlew.bat assembleRelease  # Release，R8 混淆 + 资源压缩 + 签名
.\gradlew.bat clean            # 清理构建产物
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release.apk`
- 版本：`versionCode=2`、`versionName=1.2.0`（debug 后缀 `-debug`），见 `app/build.gradle.kts`
- **Gradle daemon 冲突**（Kotlin daemon 连接/AAPT2 导致 AccessDeniedException）：先执行 `.\gradlew.bat --stop` 重试
- 各产出过构建的都曾因 APK 大被误入库：勿把 `app/build/`、`.gradle/` 提交进 git

## 4. 硬性约束（必须遵守）

### 4.1 文件与图片
- Markdown 图片**必须相对路径**：`![alt](images/xxx.png)`，禁绝对路径
- 插入图片复制到 `.md` 同级 `images/` 目录，命名 `{unix_timestamp}_{random4}.jpg`（小写、无空格无中文），压缩长边至 2048px、JPEG 质量 85；按来源分流写：`content://` 文档用 MediaStore（`RELATIVE_PATH=images/`）插入、`file://` 用 File API
- 外部 `content://` URI 文件必须先复制到 `filesDir/imported/` 再打开（否则权限丢失）
- 非标准 MIME 文件（如 `.toml`）：同时处理 `content://` 与 `file://`

### 4.2 编辑器行为
- 标题栏显示保存状态（未保存 / 保存中 / 已保存）
- 退出编辑器（返回键 / 顶栏返回）必须弹未保存确认（「保存并退出」「放弃更改」，点击后立即关对话框再处理）
- 撤销 / 重做按钮**仅编辑模式显示**，位于模式切换按钮与模式状态文字之间
- **编辑模式图标顺序：搜索 → 撤销 → 反撤销 → 插入图片**；组间距统一 8.dp；工具栏高度紧凑（内边距上下 3.dp、分段按钮 34.dp）让位文本区
- 自动保存：编辑态 + 停输 3 秒 + 未锁定；**不得产生额外撤销历史项、不得影响撤销/重做历史**
- Markdown 列表自动格式化：无序/有序/复选框续行、缩进继承；空列表项回车自动清理
- 行首加光标不得弹键盘、不得改变滚动位置；光标定位用「不请求焦点」方式
- `BasicTextField` 需 `consumeBringIntoView()` 防长按选择时文本回弹顶部
- 编辑模式滚动位置用 `Ref` 容器保存，仅模式切换时 `LaunchedEffect(editorMode)` 快照
- 编辑/预览切换为**单图标互切**（编辑=`Icons.Default.Create` 笔 / 预览=`Icons.Default.Visibility` 眼睛，同一位置）；无预览模式时固定笔图标
- 搜索激活期间暂停光标跟随；关闭搜索后首帧也跳过光标跟随，避免视口被拉回光标处
- 新建文件：文件名输入 + 右侧后缀选择（只读不可手输、默认 `.md`、约 21 种，`max=180.dp` 纵向滚动小窗）；`sanitizeFileName` 已带扩展名保留原后缀、无扩展名才补 `.md`
- 重命名（方案2，可改后缀）：输入框预填完整文件名（含后缀），可修改后缀；后缀变化（忽略大小写）时先弹「更改扩展名」确认框（含"不受支持后缀可能不显示在列表"提示），确认后按新后缀重命名并刷新文件类型；无后缀即无后缀，不再自动补回原后缀；文件名不得以点号结尾；重命名 content:// URI 时同步更新 MediaStore/DocumentProvider 的 MIME_TYPE，避免系统因 MIME 与扩展名不匹配自动追加原扩展名（如 2255.cpp -> 2255.cpp.md）；MediaStore 扫描白名单必须覆盖全部受支持后缀（`FileType.ALL_EXTENSIONS` 动态生成 DISPLAY_NAME LIKE 子句），否则改后缀后文件会因 MIME 变化从列表消失
- 编辑占位（Markdown 语法参考）**仅 Markdown 空白文件显示**：`EditContentView` 用独立参数 `showMarkdownPlaceholder` 控制（传 `uiState.isMarkdown`），与语法高亮开关 `isMarkdown`（大 md 时为 `isMarkdown && !isLargeMd`）解耦；非 .md 空白文件（.txt/代码等）不显示占位，保持空白编辑区

### 4.3 大文本（>0.5MB）
详细设计与 Phase 状态见 **第 8 章**。此处仅列不可违反的行为约束：
-> 大 TXT 用**分页只读浏览**，编辑态隐藏全文工具（撤销/重做/搜索，`showTextTools`）并禁用自动保存；进入分段编辑仅单段可写
> 单行超长必须按**字节**切块，禁止整行持入内存
> 阅读位置锚点统一用**全局字节偏移**，按 `fileUri` 分 key 持久化；防抖 400ms 落盘 + `onCleared()` 强兜底
> `BasicTextField` 分段编辑重建 value 时**必须保留 `TextFieldValue.composition`**（否则手写输入法「一笔即字」无法成字）
> **退出保存必须区分普通文件与大 TXT 分段编辑**：大 TXT 下 `currentContent` 恒为 `""`，`saveAndExit` 必须保存 `pagedEditingText` 当前块，禁止直接 `saveContent(uri, "")`
> **大 .md（>1MB）直接当大 TXT 处理**：`isLargeMd` 时 `isReadOnlyPaged` 亦置 true，编辑与预览均走 `PagedReadContentView` 分页只读 + 分段编辑，**不再 `loadFullContent` 全文载入**（根除超长 md 全文载入/语法高亮导致的卡顿崩溃）；`isLargeMd` 仅保留用于区分"是否 Markdown"（如预览分支、TOC 等）；大 md 打开默认 PREVIEW（分页只读浏览），长按分段进入编辑，与超大 txt 行为完全一致；TOC 按钮对大 md 隐藏（分页只读不全文载入、无法生成目录）
> **外部文件导入必须先净化文件名**：`content://` / `file://` / `SEND` 等外部来源的文件名必须经 basename + 路径穿越校验，禁止直接拼接到 `filesDir/imported/` 或 `filesDir/shared/`

### 4.4 预览 / 渲染
- 代码块深色背景：围栏代码卡片底 `#1E1E1E`、卡片顶栏 `#2D2D2D`、文本段内代码块 `#2D2D2D`（三者现状如此，勿再写"统一 #2D2D2D"）；未指定语言块语言标签显示 `text` 且不可选中文本；预览模式下代码卡片仅横向滚动
- 非 Markdown 文件预览用 `LazyColumn` + `item(key)` 防滚动跳动；`MarkdownPreview` 用 `itemsIndexed` + `contentType` 助估尺寸
- 预览 TextView 禁用 overscroll、外部叠加滚动条（防右侧白条）
- 主题切换必须用 `effectiveDarkTheme` 参数（防 UI 卡死）
- TOC 仅 Markdown 显示（大 md 分页只读隐藏，因不全文载入无法生成目录）；点击 TOC 条目仅关面板不跳转
- `com.miui.notes` 权限图片 URL 忽略、仅显图片名
- 相对路径图片不存在时预览回退灰色占位 `图片{alt}`（`preprocessImagePaths` 收集 + `applyMissingImagePlaceholders` 施灰色 `ForegroundColorSpan`）

### 4.5 Intent 过滤器
- 必须含 `application/octet-stream`（无标准 MIME）、`text/*`、`application/json`
- 每个 pathPattern 过滤器 28 条（一级 16 + 二级 8 + 三级 4，VIEW 与 EDIT 各一份共 56 条）覆盖 `.toml/.ini/.cfg/.conf/.env/.properties/.yaml/.yml/.csv/.diff/.patch/.log/.sh/.bash/.zsh/.dockerfile`；已知局限：`.conf/.env/.properties/.csv/.log/.bash/.zsh/.dockerfile` 仅一级深度、pathPattern 大小写敏感（`.TOML` 不命中）
- **VIEW 过滤器禁止带 `BROWSABLE` category**：`BROWSABLE` 仅用于 `http/https` 深链，与 `file`/`content` scheme 组合会被浏览器借深链拉起应用并传入可控文件
- **SEND 过滤器 MIME 覆盖必须与 VIEW 一致**：接收外部文件分享（`ACTION_SEND` + `EXTRA_STREAM`）时，MIME 需覆盖 `text/*`、`application/json`、`application/octet-stream` 等，否则分享 .md/.toml 等文件时 MarkFlow 不出现在分享目标列表；代码层必须优先读 `EXTRA_STREAM`（文件 URI）走 `copyContentUriToLocal`，回退 `EXTRA_TEXT`（纯文本）走 `handleSharedText`，禁止只处理 `text/plain`
- **应用内"打开文件"按钮**（文件列表顶栏，`ActivityResultContracts.OpenDocument`）：MIME 过滤数组为 `text/*` + `application/octet-stream`（`text/*` 覆盖大部分文本，octet-stream 兜底）。曾考虑显式补充代码文件 MIME（.c/.cpp/.java/.py 等），用户 09-03 决定暂不实施，保持现状

### 4.6 架构约定
- ViewModel 管状态、Repository 管数据、Coroutines + Flow 异步
- `FormatPlugin` 接口定义格式扩展，`FormatRegistry` 统一注册；内置 Markdown（自动格式化）/ 代码（仅高亮）/ 纯文本
- 主题模式用 `ThemeMode` 枚举（不用裸字符串）
- Markdown 编辑语法高亮覆盖 11 种元素；按文档大小分流：≤16K 字符 filter 内同步计算（即时高亮），>16K 字符后台线程 + 80ms debounce 异步计算（LRU 缓存 8 项，计算完成自增快照版本号触发重组命中缓存上屏，组件销毁 `cancelPending()`）
- 文件列表：imported 与 local 各自按 sortMode 排序后合并；单击打开、长按多选（仅删除）
- **扫描范围（禁止全盘扫描）**：仅扫描 `Documents/`、`Download/` 文档目录（Android 10+ 用 `RELATIVE_PATH LIKE 'Documents/%' OR 'Download/%'`，Android 9- 用 `getExternalStoragePublicDirectory(DOCUMENTS/DOWNLOADS)`）；同时排除媒体 MIME（`video/%`、`audio/%`、`image/%`，SQL 层 + cursor 循环双保险，MIME 为 null 时放行），避免 .ts 视频等非文本文件混入列表；**MIME 为 null 的 `.ts` 后缀按视频兜底排除**，防止部分 ROM 把 `.ts` 视频识别为 TypeScript 文本
- **搜索后全选**：仅选中当前搜索过滤后的可见文件，不会选中全部文件
- **搜索状态返回键**：文件列表页搜索框有焦点或有关键词时，按系统返回键先退出搜索（清空关键词 + 收起键盘），不直接退出应用；多选模式返回键退出多选
- **多文件分享**：多选模式下分享使用 `ACTION_SEND_MULTIPLE`，单文件仍用 `ACTION_SEND`；编辑器内分享与文件列表分享统一按后缀推断 MIME（`resolveShareMimeType`）；`BottomActionBar` 分享按钮 `enabled = selectedCount > 0`（单选/多选均可用），重命名/详细信息仍仅单选可用
- **批量删除性能**：并发删除（每批 16 个）+ 删除成功后直接从内存列表移除，不再全量重扫 MediaStore，避免大量文件删除卡顿
- **Android 11+ 非本应用文件操作必须处理 `RecoverableSecurityException`**：重命名/删除 content:// URI 时捕获该异常，通过 `IntentSender` 向用户申请一次性/批量授权，授权成功后自动重试

## 5. 已知陷阱（务必避免）

### 5.1 渲染与 UI
- **预览图片上方空白根本原因**：预览 TextView `setLineSpacing(8f, mult)` 的 `mult`（>1.0，曾 1.35f）会按 replacement span（图片）行高等比放大 bottom，**仅当图片行后还有内容时**该行 bottom 被撑高（空约 0.4×图高），图片贴底绘制露空白。修复：`mult` 改为 `1.0f`，仅留固定像素 `add`。把图片行"规范成独立段落"不治本——真正变量是"图片行后是否有后续内容"
- **Markwon 相对路径图片**：必须自定义 image loader 基于 `.md` 物理路径解析
- **HtmlPlugin 与 `<sub>/<sup>`**：当前实现为上下标插件直接输出 `<sub>/<sup>` 标签、依赖 HtmlPlugin 渲染（早期记录"HtmlPlugin 不支持 `<sub>/<sup>`、用 Unicode 占位替代"已与此不符，孰对孰错待真机验证；若上下标不渲染再回退 Unicode 占位方案）
- **上下标插件**：逐字符扫描需跳过 LaTeX 公式区间（`$...$`/`$$...$$`），否则 `^`/`~` 误识别
- **AndroidView update 回调**：勿直接 `markwon.setMarkdown()`（嵌套布局），用 `textView.post{}` 推迟到下一帧
- **滚动位置计算**：勿用固定行高（如 24dp），用 `TextLayoutResult.getLineTop()` / `getLineForOffset()`
- **代码块卡片重复 setText**：update 回调需比较 `codeSpanned` 引用；factory setText 后立即记录 `lastCodeSpanned`，防 LazyColumn 跳动
- **避免 `AnimatedVisibility` 展开动画**（初始高 0 致跳动）：用 `if (!isCollapsed) + animateContentSize()`
- **避免 `mutableStateOf` 存 span 引用**：用 `Ref` 容器，防初始布局二次测量
- **`FocusRequester.requestFocus()`**：必须 try-catch `IllegalStateException`
- **动态取色**：部分国产 ROM 会抛异常，try-catch 并回退自定义配色
- **状态更新后导航**：用 `Dispatchers.Main`（非 immediate）确保对话框先关闭再导航

### 5.2 Markdown / LaTeX / R8
- **JLaTeXMath 反射**：大量字符串反射，R8 改名致块级公式 NPE。ProGuard 保留 `ru.noties.jlatexmath.**`、`org.scilab.forge.jlatexmath.**`；`isBlock()` 反射依赖 `-keep class io.noties.markwon.** { *; }`
- **行内公式对齐**：`fixFormulaSpanAlignment` 在 `setText` 同步调用，替换 `VerticalAlignedLatexSpan` 施偏移，比例 `INLINE_FORMULA_UPWARD_OFFSET_RATIO=0.35f`
- **`MarkwonConfig` 缓存**：按主题缓存 Markwon 实例（`@Volatile`），长文档渲染用 `Dispatchers.Default`

### 5.3 构建 / 编译
- **kapt 诡异编译错优先查源结构**：`duplicate class` / javac `TypeEnter` 多为同文件两个 companion object 污染 kapt stub。定位：临时移除 kapt 依赖（prism4j-bundler）→ `compileDebugKotlin` 暴露真实错误。勿先清缓存 / 改工具链
- **一 Kotlin 类只允许一个 companion object**；新常量并入既有 companion
- **构建配置实验需还原**：排查后把 `gradle.properties`（`kotlin.compiler.execution.strategy`、`kapt.incremental.apt`）与 `app/build.gradle.kts`（`kapt{}`、`kotlinOptions`、临时移除依赖）恢复原值；诊断日志（`*.log`、`build_log.txt`、`%TEMP%\mf_*.log`）及时删并入 `.gitignore`
- **`ColumnScope.align` 只接受 `Alignment.Horizontal`**；传 `CenterEnd` 编译不过，需 `Alignment.End`
- **Debug 通过 / Release 失败且报错在未修改文件**（如 `Unresolved reference` 指向从未改过的类）：多为 Release 增量编译/kapt stub 缓存损坏，先 `.\gradlew.bat clean assembleRelease` 重试，勿先改源码
- **图标集限制**：`Icons.AutoMirrored.*` 可用性随 icons 版本而定，不稳时退 `Icons.Default.Create`

### 5.4 状态持久化 / 文件
- **防抖落盘有竞态**：滚动中旧协程 cancel 后仍可能用过期参数写盘。正解 = 主线程同步记录最新状态 + 落盘前读最新值 + `onCleared()` 兜底
- **大 TXT 分段编辑的退出保存**：大 TXT 加载后 `currentContent=""`，`saveAndExit` 若直接 `saveContent(uri, currentContent)` 会把整文件覆盖为空。必须检测 `isReadOnlyPaged && pagedEditingIndex != null`，改走 `persistPagedEdit` 保存当前块 + `loadPaged` 重建；`confirmDiscard` 也需 `cancelPagedEdit` 清理编辑态
- **签名密钥与密码禁止驻留仓库根**：`keystore.properties` 和 `.keystore` 文件必须放在用户家目录（如 `~/.markflow/`）；`app/build.gradle.kts` 经 `providers.fileContents` 从该外部路径读取（配置缓存感知），release 签名文件缺失、缺少必需键或 keystore 不存在时**在执行期**（`validateReleaseSigning` → `packageRelease`）`GradleException` 失败——不阻塞 debug 构建/IDE sync；禁止静默回退 `signingConfigs.debug`
- **外部文件名不可信**：从 `content://` / `SEND` 等外部来源获取的 `DISPLAY_NAME`、`EXTRA_TITLE` 可能含 `../`、空名或隐藏文件名，必须先 `basename` 化 + 拒绝路径分隔符 + canonical path 校验，再拼接到应用私有目录

## 6. 日志策略

- **Release（R8）自动移除 `Log.v/d/i`**，保留 `Log.w/e/wtf`（proguard `-assumenosideeffects`）
- 因此**禁止把调试日志写进 `Log.d` 等低级别调用**排障——正式版不生效；需长期保留的错误日志用 `Log.e`

## 7. ProGuard 关键规则（app/proguard-rules.pro）

- 保留反射依赖：JLaTeXMath、Markwon、Prism4j、Coil、Hilt
- 保留 `com.markflow.editor.domain.model.**`（序列化/反射）
- 移除 release 日志；**改 ProGuard 后必须回归 release 构建**（混淆问题只在 release 出现）

## 8. 大 TXT（>0.5MB）技术方案

### 8.1 核心设计（已拍板）
- **乱码 / 崩溃 / 流畅是三个独立问题，用三套互不相干的机制解决，勿混为一谈**
- 大 txt 可编辑 = **分段编辑模型**：浏览连续分页只读、编辑时单块载入（Compose `BasicTextField` 天生持有整份文本，无法做 WPS 式 piece-table 无缝连续编辑）
- 字节预算分块 **~10KB/段**；单行超长（>200KB）独立成块且硬截断按编码单元对齐；预取上/下各 2 块；块缓存按累计字符 **≤10M（≈20MB）** 逐出（LinkedHashMap 插入序 FIFO，非严格 LRU，注释勿再写 LRU）
- 块索引 `List<Long>`（O(1) seek）；`content://` 必须用 PFD/AssetFileDescriptor 拿 fd seek，`openInputStream+skip` 会退化为 O(n) 禁用；超大文件用懒惰/稀疏索引，按需增量补建
- 编码：BOM 优先（`EF BB BF` UTF-8 / `FF FE` UTF-16LE / `FE FF` UTF-16BE）→ 严格 UTF-8 位模式 → GB18030/系统默认回退；UTF-8 校验须容忍采样结尾不完整序列；解码 RECOVER/REPLACE 不抛异常；提供手动切换编码入口
- 写回：`file://` 用 `RandomAccessFile` 随机写（长度不变原地覆盖；变化时"新块+尾部落盘临时文件拼接"）；`content://` 整文件临时文件覆盖（O(n)，异步+进度+脏段合并）
- 撤销：页面内 UndoRedoManager，**不做跨页全局撤销**

### 8.2 实施状态
- **Phase 1 乱码 ✅**：`EncodingDetector`（BOM/严格 UTF-8/GB 回退/REPLACE）+ 仓库/ViewModel/标题栏手动切换接入
- **Phase 2 分页内核 ✅**：`PagedTextSource`（~10KB + 懒惰块索引 + O(1) seek + 单行兜底）；`StorageRepository.openSeekable`（file→RAF / content→PFD.FileChannel）；VM 字节预算 LRU + ±2 块预取；无限 LazyColumn + EOF
- **Phase 3 编辑 ✅**：分段编辑状态机（startPagedEdit/updatePagedEditText/cancelPagedEdit/savePagedEdit）；file 随机写 / content 整文件覆盖；编码复用；单测 +6 共 90 全绿；release R8 通过
- **Phase 4 预览 ✅**：全 txt 编辑/预览切换（`EditorModeSwitchBar`）；小/中 txt 预览=`PlainTextPreview`；大 txt 编辑=分页浏览+长按分段、预览=分页只读；文件角标"大"。**保留 0.5MB 阈值**：小/中 txt 仍全文编辑
- **Phase 5 压测 ⏳ 待真机**（见 8.3，阻塞项：`adb devices` 长期为空）

### 8.3 Phase 5 大 TXT 压测方案
前置：Android 10+ 真机，USB 调试；`adb install -r app/build/outputs/apk/debug/app-debug.apk`

**数据矩阵**（用 `testdata\gen-testdata.ps1` 生成后 `adb push`）：

| 样本 | 大小 | 编码 | 用途 |
|---|---|---|---|
| small_utf8.txt | ~0.3MB | UTF-8 | 阈值内小文件回归 |
| cross_utf8.txt | ~1.0MB | UTF-8 | 跨 0.5MB 阈值 |
| mid_gbk.txt | ~10MB | GBK | 中文件 + GBK 乱码 |
| big_utf8.txt | ~50MB | UTF-8 | 滚动/跳块/内存 |
| bom_utf8.txt | 0.9KB | UTF-8 BOM | BOM 剥离 |
| utf16.txt | 0.7KB | UTF-16LE | BOM 探测 |
| longline.txt | 50KB | UTF-8 单行 | 单行超长兜底 |

两条路径都测：`file://`（push 到 `/sdcard/Download/`）与 `content://`（push 到 `/sdcard/Documents/MarkFlow/` 并触发媒体扫描）。

**关键用例 TC**：TC01 冷启动（50MB≤2s、无 OOM）· TC02 滚动流畅 · TC03 随机跳块（O(1)）· TC04 编码正确 · TC05 模式切换×10 · TC06/07 分段编辑保存（file/content 各一）· TC08 连续编辑 5 次无错位 · TC09 编码切换重载 · TC10 内存峰值（`dumpsys meminfo`，稳定在 LRU 预算内）· TC11 无 tmp 残留 · TC12 小文件回归。

**写回正确性判定**：保存后 `adb pull` 回 PC 与原始样本 `fc /b`/hash 比对，**仅被编辑段落对应字节变化，其余逐字节一致**。

## 9. 验证技巧

- **判断修复是否入包**：源码 mtime 早于 APK 构建时间可先快速确认；严格用 dex 符号检查
- **dex 验证**：读 APK 内 `classes*.dex`——检查目标字符串字面量是否存在；解析 method_id 表统计 `android/util/Log` 引用，`v/d/i` 应为 0、`e/w` 保留，据此验证 R8 日志移除与反射 keep 是否生效
- **R8 注意**：自定义类名可能被重命名，但功能代码与字符串字面量保留；字符串命中即证代码已进包

## 10. 版本与进度（当前 1.2.0）

### 10.1 里程碑（已落地，供追溯；细节见上文各章）
Phase 1–4 大 txt 全链路 · 图片插入（写同级 images/）· 行内 LaTeX 垂直对齐 · 阅读位置记忆 · 手写输入法 · 搜索位置保持 · 编辑栏单图标 + 图标顺序 · 新建保留后缀 · 重命名可改后缀+确认提示 · R8 反射保留与 release 日志清理 · git 历史重建为单根提交（`.git` 112MB→0.2MB）

### 10.2 本会话核心成果（09-03）
- **图片插入重写并重新加入**：学习参考 `docs/markor` 后定方案——写 `images/` 子目录、按来源分流、压缩 2048px/JPEG85、`{ts}_{rand4}.jpg`、光标处插入、`resolveBaseDir` 补 content:// RELATIVE_PATH 解析
- **预览图片上方空白修复**：根因 + 方案见 §5.1 第一条
- **签名确认**：keystore.properties 与 build.gradle.kts 键名完全匹配；SHA1 `27:DB:4C:7F:E1:B6:F8:3F:3D:DD:B3:1E:DA:32:93:62:99:57:C2:76` 与 APK 签名指纹一致
- **README 同步**：图标顺序修正为「搜索→撤销→反撤销→插入图片」、项目结构 util/components/domain 与代码对齐
- **重命名改后缀方案2 落地**：输入框预填完整文件名、可改后缀；后缀变化（忽略大小写）先弹「更改扩展名」确认框（含"不支持后缀可能不显示在列表"提示）；重命名路径不再补 .md（无后缀即无后缀）；文件名不得以点号结尾；改后缀后 loadFiles 按新后缀刷新类型（FormatRegistry）；同步更新 MediaStore/DocumentProvider MIME_TYPE，修复 .md 改其他后缀时被系统追加原扩展名的问题（如 2255.cpp -> 2255.cpp.md）；MediaStore 扫描白名单放宽为动态覆盖全部受支持后缀（FileType.ALL_EXTENSIONS），修复改后缀为 js/json/yaml/toml/sh/sql 时文件从列表消失的回归
- **扫描范围收紧（禁止全盘扫描）**：正式版列表出现大量 .ts 视频导致卡退，改为仅扫描 Documents/、Download/ 文档目录 + 排除 video/audio/image MIME（SQL 层 + cursor 双保险），详见 §4.6
- **搜索全选逻辑修复**：全选仅作用于搜索过滤后的可见文件
- **多文件分享**：多选分享支持 ACTION_SEND_MULTIPLE，单文件保持 ACTION_SEND 并推断 MIME
- **批量删除性能优化**：StorageRepository.deleteFiles 改为并发分批删除（每批 16），ViewModel 删除成功后直接从内存列表移除，避免全量重扫 MediaStore
- **"打开文件"按钮后缀方案讨论后放弃**：曾提出显式补充代码文件 MIME（.c/.cpp/.java/.py 等），用户决定暂不实施，保持 `text/*` + `application/octet-stream` 现状（见 §4.5）
- **修复大 TXT 分段编辑「保存并退出」清空文件 bug**：`EditorViewModel.saveAndExit` 原直接保存 `currentContent`（大 TXT 下恒为 `""`），现检测 `isReadOnlyPaged && pagedEditingIndex != null` 改走 `persistPagedEdit` 保存当前块 + `loadPaged` 重建；`confirmDiscard` 也增加 `cancelPagedEdit` 清理编辑态
- **A2 外部文件导入路径穿越防护**：`MainActivity.copyContentUriToLocal` / `handleSharedText` 新增 `sanitizeExternalFileName`，对外部 `DISPLAY_NAME` / `EXTRA_TITLE` 做 basename 化、拒绝路径分隔符/隐藏文件、canonical path 校验
- **A3/A12 签名密钥外置与构建安全**：`keystore.properties` + `markflow-release.keystore` 移至 `C:\Users\Lenovo\.markflow\`；`app/build.gradle.kts` 改为从 `~/.markflow/keystore.properties` 读取，release 签名缺失时 `error(...)` 失败而非回退 debug 签名
- **A8 Android 11+ `RecoverableSecurityException` 处理**：`StorageRepository.renameFileViaContentProvider` / `deleteFiles` 捕获该异常并包装为 `SecurityConsentRequiredException`；`FileListViewModel` 保存待操作并通过 `PendingSecurityRequest` 触发 UI 授权；`FileListScreen` 用 `StartIntentSenderForResult` 接收结果并自动重试
- **A9 `.ts` 视频 MIME null 兜底排除**：`StorageRepository.queryMediaStore` 在 cursor 循环中对 `MIME` 为 null 且后缀为 `.ts` 的文件按视频排除
- **A11 移除 VIEW 过滤器的 `BROWSABLE` category**：`AndroidManifest.xml` 中 VIEW 过滤器不再声明 `BROWSABLE`，避免浏览器深链拉起并传入可控文件
- **A19 编辑器分享 MIME 与文件列表统一**：提取 `resolveShareMimeType` 到 `ShareUtils.kt`，`EditorScreen.buildShareIntent` 与 `FileListScreen` 均按后缀推断 MIME
- **修复外部文件分享无法打开**：`ACTION_SEND` 原只处理 `text/plain` + `EXTRA_TEXT`（纯文本），文件分享（`EXTRA_STREAM` 携带 content:// URI）直接返回 null，manifest SEND 过滤器也只声明 `text/plain`。修复：SEND 过滤器 MIME 扩展为与 VIEW 一致；`extractFileUriFromIntent` 优先读 `EXTRA_STREAM` 走 `copyContentUriToLocal`，回退 `EXTRA_TEXT` 走 `handleSharedText`；新增 `getStreamUriFromIntent` 处理 API 33+ 类型化 getParcelableExtra
- **大 .md 预览降级（方案 2 落地）**：新增 `LARGE_MD_THRESHOLD_BYTES`（1MB）与 `isLargeMd` 状态；大 md（>1MB）预览走 `PagedReadContentView` 分页只读（复用大 TXT 内核，`editingEnabled=false`），编辑保留全文但禁用语法高亮；`ensurePagedPage`/阅读位置上报守卫放行 `isReadOnlyPaged || isLargeMd`；提取 `loadFullContent` 供普通文件与大 md 共用全文加载；`docs/large_render_test.md`（2.38MB/53607 行）不再触发 MarkdownPreview 全量渲染崩溃
- **超长 md 彻底改当大 TXT 处理（09-03 晚）**：大 md 编辑态仍全文载入 + 语法高亮致卡退；现将大 md 的 `isReadOnlyPaged` 亦置 true，编辑/预览均走分页只读 + 分段编辑，**删除大 md 的 `loadFullContent` 调用**、默认 EDIT→PREVIEW；`ensurePagedPage`/`onPagedScrollPosition` 守卫简化为仅 `isReadOnlyPaged`；TOC 按钮对大 md 隐藏（分页只读不全文载入、无法生成目录）。详见 §4.3

### 10.3 审查修复会话核心成果（09-03 晚，Top 10 修复）

依据 `docs/代码全面审查报告.md` 的 Top 10 清单逐项修复，全部 94 单测（90 旧 + 4 新）通过、debug/release 构建通过：

- **分段保存缓存错位修复（高危）**：`savePagedEdit`/`saveAndSwitchEdit` 的 `preserveUntil` 原保留被编辑块本身的旧缓存（保存后界面回显编辑前内容）；现仅保留未受写回影响的块（`savePagedEdit` → `preserveUntil = index`；`saveAndSwitchEdit` → 目标段在编辑段之前时 `newIndex + 1`，否则 `oldIndex`），且目标段在编辑段之后时按"旧字节偏移 + 长度差"在新索引中重新定位目标块。**教训：preserveUntil 语义 = "保留索引 < 该值的块"，被编辑块本身绝不可保留**
- **file:// 写回改备份回滚（高危）**：`persistFileRandomWrite` 原为就地写，中断留半新半旧文件；现先把受影响区（脏块到 EOF，同长仅单块）整体备份临时文件，写失败从备份回滚 + `setLength` 恢复原长；成本与旧实现相同
- **UTF-16 分页错位乱码修复（高危）**：`PagedTextSource` 原按单字节 `0x0A` 找换行，UTF-16 下一块从奇数字节界开始解码整块错位；现块边界按编码单元对齐（UTF-16LE 找偶偏移 `0A 00`、BE 找 `00 0A`、硬截断取偶；UTF-8 硬截断回退完整多字节字符），新增 4 个单测（LE/BE 分块还原、偶偏移校验、UTF-8 硬截断对齐）
- **大 .md 预览降级确认 + 补漏（高危）**：代码层已实现（`isLargeMd`：>1MB 预览走分页只读、编辑保留全文禁高亮，`LARGE_MD_THRESHOLD_BYTES = 1MB`）；本次补上缺口——`onPagedScrollPosition` 原只对 `isReadOnlyPaged` 生效，大 md 阅读位置记忆被静默丢弃，现 `isReadOnlyPaged || isLargeMd` 均记录
- **编辑态语法高亮异步化（高危）**：`MarkdownSyntaxHighlighter.filter()` 原主线程每键全量跑 11 个正则（文档曾误称已有 debounce）；现 ≤16K 字符同步（UX 不变）、>16K 后台 + 80ms debounce + LRU 缓存 + 快照版本号触发重组
- **批量删除授权异常一致性（高危）**：`SecurityConsentRequiredException` 新增 `partialSucceeded`，`deleteFiles` 抛出前已成功删除的 URI 不再丢失；ViewModel 先把已删项移出列表 + `unhideFiles`，授权重试仅清已删选中项不再清空全部选择；多个待授权 URI 合并进同一 `PendingFileOperation.Delete` 逐轮授权
- **MediaStore 扫描行级容错（高危）**：`queryMediaStore` 单行脏数据（DISPLAY_NAME null 等）只跳过该行，不再中断整次扫描
- **预处理排除代码块（中危）**：`MarkwonConfig` 新增 `transformOutsideCode`（围栏 ``` + 行内 `` ` `` 区间识别），`preprocessLatexInline`/`preprocessLatexEnvironments`/`preprocessImagePaths` 不再改写代码示例内容
- **签名校验改执行期（中危）**：`app/build.gradle.kts` 原配置期 `error(...)` 致 debug 构建/IDE sync 受牵连；现 keystore.properties 缺失/缺键或 keystore 文件不存在时 `signingConfig = null`，独立 `validateReleaseSigning` 任务在 `packageRelease` 前抛 `GradleException` 并指明缺失项（避免 doFirst 闭包破坏 configuration cache，任务始终执行防 up-to-date 误判）；keystore.properties 经 `providers.fileContents` 读取（配置缓存感知）
- **导入复制原子化 + 同名去重（中危）**：`copyContentUriToLocal` 先写 `.xxx.tmp` 临时文件再 rename（中断不留残缺文件），同名文件生成 `name(1).ext` 不再静默覆盖；`handleSharedText` 同样去重并补失败日志

### 10.4 待办（下次推进）
- [ ] 真机验证 1.2.0（`adb devices` 长期为空）：大 txt 阅读位置/Phase 5 压测、大 md 当大 txt 处理（分页只读 + 分段编辑）、搜索位置保持、手写输入、图片插入与预览、单图标切换、新建后缀窗、重命名后缀
- [ ] 是否 `git push --force origin main` 推送重建后的基线到 Gitee（本地为单根提交，与远端无共同祖先，普通推送会被拒，需 --force；用户保留 URL 未推，待自行决定）
- [ ] **（未来规划，暂未实现）编辑模式图标顺序拟改为「插入图片 → 搜索 → 撤销 → 反撤销」**。当前实现仍为「搜索 → 撤销 → 反撤销 → 插入图片」（见 §4.2）；此条为用户明确意向的记录，调整时需同步 §4.2 描述与 README

## 11. Git 与环境中易踩的坑（经验沉淀）

- **路径存在性校验勿用相对路径 + 单次输出下结论**：`docs/markor` 曾因相对路径 Test-Path 误判"已删除"实则仍在磁盘。删除/校验关键目录必须用绝对路径复核并观察真实文件列表
- **曾被跟踪过的文件绕过 `.gitignore`**：`.gradle/`、`local.properties` 等即使已入 ignore，一旦历史上被 `git add` 过，重建基线时 `git add -A` 仍会纳入，需显式 `git rm --cached` 排除
- **git 历史瘦身依赖远端跟踪引用**：`remotes/origin/<branch>` 若仍指向被丢弃的旧 commit，`git gc --prune` 不会清旧对象；须先将该引用重定向到新基线（或删除），再 `git reflog expire --expire=now --all && git gc --prune=now --aggressive`
- **PowerShell 长命令注意引号平衡**：多余引号会触发 `The '--' operator works only on variables` 解析错