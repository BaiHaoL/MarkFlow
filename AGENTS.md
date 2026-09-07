# MarkFlow 项目指南（供 AI 代理协作）

本文档是 AI 编程代理在 MarkFlow 仓库协作时的工作指引，沉淀本项目长期规范、技术方案、已知陷阱与经验。**修改任何代码前请先通读**，尤其「硬性约束」「已知陷阱」两章。请保持内容与代码现状同步——凡与代码不符的描述，以代码为准并随手修正本文。

## 1. 项目简介

MarkFlow 是一款 Android Markdown 编辑器：浏览 / 编辑 / 预览 / 管理 Markdown 及多种文本文件。基于 Jetpack Compose，用 Markwon 渲染 Markdown、Prism4j 做语法高亮、JLaTeXMath 渲染 LaTeX、Coil 加载图片。支持大 TXT / 大 Markdown（≥512KB）的分页只读浏览与分段编辑。

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
| LaTeX | JLaTeXMath |
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
- release 签名需 `~/.markflow/keystore.properties`（见 §7）；缺失时 release 构建在执行期报错，不影响 debug/IDE sync
- **Gradle daemon 冲突**（Kotlin daemon 连接/AAPT2 导致 AccessDeniedException）：先 `.\gradlew.bat --stop` 重试
- 勿把 `app/build/`、`.gradle/` 提交进 git；被跟踪过而 `.gitignore` 失效的文件需显式 `git rm --cached`
- **Debug 过 / Release 失败且报错在未修改文件**：多为 release 增量编译/kapt stub 缓存损坏，先 `clean assembleRelease` 重试，勿先改源码

## 4. 硬性约束（必须遵守）

### 4.1 文件与图片
- Markdown 图片**必须相对路径**：`![alt](images/xxx.png)`，禁绝对路径
- 插入图片复制到 `.md` 同级 `images/` 目录，命名 `{unix_timestamp}_{random4}.jpg`（小写、无空格无中文），压缩长边至 2048px、JPEG 质量 85；按来源分流写：`content://` 文档用 MediaStore（`RELATIVE_PATH=images/`）插入、`file://` 用 File API
- 外部 `content://` URI 文件必须先复制到 `filesDir/imported/` 再打开（否则权限丢失）
- **外部文件名不可信**：`content://` / `SEND` 等来源的 `DISPLAY_NAME`、`EXTRA_TITLE` 可能含 `../`、空名或隐藏文件名，必须先 basename 化 + 拒绝路径分隔符 + canonical path 校验，再拼接到应用私有目录（`filesDir/imported/`、`filesDir/shared/`）
- 非标准 MIME 文件（如 `.toml`）：同时处理 `content://` 与 `file://`

### 4.2 编辑器行为
- 标题栏显示保存状态（未保存 / 保存中 / 已保存）
- 退出编辑器（返回键 / 顶栏返回）必须弹未保存确认（「保存并退出」「放弃更改」），点击后立即关对话框再处理
- 撤销 / 重做按钮**仅编辑模式显示**，位于模式切换按钮与模式状态文字之间
- **编辑模式图标顺序：搜索 → 撤销 → 反撤销 → 插入图片**；组间距统一 8.dp；工具栏高度紧凑（内边距上下 3.dp、分段按钮 34.dp）
- 自动保存：编辑态 + 停输 3 秒 + 未锁定；**不得产生额外撤销历史项、不得影响撤销/重做历史**；`saveFile`/`saveAndExit` 进入即 `autoSaveJob?.cancel()` 且所有写盘经 `saveMutex` 串行，杜绝手动保存与在途自动保存竞态覆盖（X-H1）
- Markdown 列表自动格式化：无序/有序/复选框续行、缩进继承；空列表项回车自动清理；**仅 `.md` 文件启用**（`onValueChange` 以 `uiState.isMarkdown` 守卫，非 .md 透传原值，X-H2）
- 编辑模式滚动位置跨 EDIT↔PREVIEW 切换保留：`editScrollState`/`previewScrollState` 均为 EditorScreen 级 `rememberScrollState()`，EditContentView 销毁重建时 verticalScroll 重新附着同一 state、value 不重置
- 编辑/预览切换为**单图标互切**（编辑=`Icons.Default.Visibility` 眼睛 / 预览=`Icons.Default.Create` 笔，同一位置）；无预览模式时固定笔图标
- 搜索激活期间暂停光标跟随；关闭搜索后首帧也跳过光标跟随，避免视口被拉回光标处
- **编辑器返回键分层（无键盘场景）**：软键盘可见时按返回键由 IME 消费（仅收键盘，不触发 `BackHandler`）；键盘已收、编辑框仍有焦点（有光标）时按返回键 `LocalFocusManager.clearFocus()` 取消光标、进入浏览态；无焦点时走未保存确认退出（`BackHandler` 的 `when` 顺序：immersive→分段编辑 `pagedEditingIndex`→`isEditorFocused`→else 退出）。`clearFocus` 不经 B′ 的 `pointerInput(Unit)` 段（见 X-H6 条目），不影响 scenario A（键盘收起时长按框选不唤起键盘）；`onFocusChanged` 是 modifier 回调、非 suspend 闭包，无陈旧捕获坑
- 新建文件：文件名输入 + 右侧后缀选择（只读不可手输、默认 `.md`、约 21 种，`max=180.dp` 纵向滚动小窗）；`sanitizeFileName` 已带扩展名保留原后缀、无扩展名才补 `.md`
- 重命名（可改后缀）：输入框预填完整文件名（含后缀）；后缀变化（忽略大小写）先弹「更改扩展名」确认框（含"不受支持后缀可能不显示在列表"提示），确认后按新后缀重命名并刷新文件类型；无后缀即无后缀，不再自动补回原后缀；文件名不得以点号结尾；重命名 content:// URI 时同步更新 MediaStore/DocumentProvider 的 MIME_TYPE，避免系统因 MIME 与扩展名不匹配自动追加原扩展名（如 `2255.cpp` -> `2255.cpp.md`）；MediaStore 扫描白名单必须覆盖全部受支持后缀（`FileType.ALL_EXTENSIONS` 动态生成 DISPLAY_NAME LIKE 子句），否则改后缀后文件会因 MIME 变化从列表消失
- 编辑占位**按文件类型显示空白提示/骨架**（12 组模板，见 `PlaceholderTemplates.kt`）：`EditContentView` 入参收敛为 `placeholderText: String?`（null=不显示）；调用方 `PlaceholderTemplates.forExtension(uiState.fileExtension)` 选模板，与语法高亮开关 `isMarkdown`（大 md 时为 `isMarkdown && !isLargeMd`）解耦。`.md` 用保留的 Markdown 语法参考；未注册/无扩展名返回 null 不显示；大文件走分页只读不经本视图故天然不显示。UI 依赖 `EditorUiState.fileExtension`（小写不含点，`loadFile` 填充）
- **`BasicTextField`（旧 value/onValueChange API）内嵌外层 `verticalScroll` 的"长按框选跳顶"（X-H6）**：根因是折叠光标停在视口外 offset（幽灵）时，TextField 内部长按 reveal 用该 offset 驱动外层滚走。已确证为 Compose 老 API TextField 官方已知 bug（#235693496 / CMP #4014）。**根治 = 方案 B′**：BasicTextField modifier 加 `pointerInput(Unit)`，用 `awaitEachGesture`+`awaitFirstDown(requireUnconsumed=false)`（只观察不消费）在 down 时把逻辑光标经 `getOffsetForPosition` 落到长按字符，使 reveal 就近。**⚠️ B′ 的致命坑（真机探针定位）**：`pointerInput(Unit)` 闭包只随 key=Unit 重启、不随重组更新，**直接捕获 `textFieldValue` 参数会拿到首次组合的空文本** → `text.isEmpty()` 恒真、落点永不执行。**必须用 `val currentTextFieldValue by rememberUpdatedState(textFieldValue)` 读最新值再落点**。已删除首载自动聚焦块（不再把光标钉 offset 0、打开呈浏览态、点按才编辑）。**已证伪勿再试**：`graphicsLayer` 平移补偿、`Modifier.bringIntoViewResponder` no-op 拦截、主动拉回 scrollTo；`Modifier.consumeBringIntoView()` 在 Compose Foundation 1.6.7 不存在，勿引用。当前仅保留 B′（含 rememberUpdatedState），勿加回任何兜底脚手架

### 4.3 大文件（≥512KB，统一阈值）
- **>512KB 的所有文本类型**（含 txt/md/代码/配置/日志等，统一阈值 `LARGE_FILE_THRESHOLD_BYTES`）用**分页只读浏览**，编辑态隐藏全文工具（撤销/重做/搜索，`showTextTools`）并禁用自动保存；进入分段编辑仅单段可写
- **大 .md 直接当大 TXT 处理**：`LARGE_MD_THRESHOLD_BYTES` 与 `LARGE_FILE_THRESHOLD_BYTES` **均为 512KB**；`isLargeMd` 时 `isReadOnlyPaged` 亦置 true，编辑与预览均走 `PagedReadContentView` 分页只读 + 分段编辑，**不 `loadFullContent` 全文载入**（根除超长 md 渲染/高亮卡顿崩溃）；大 md 打开默认 PREVIEW；TOC 按钮对大 md 隐藏
- 单行超长必须按**字节**切块，禁止整行持入内存
- 阅读位置锚点统一用**全局字节偏移**，按 `fileUri` 分 key 持久化；防抖 400ms 落盘 + `onCleared()` 强兜底
- `BasicTextField` 分段编辑重建 value 时**必须保留 `TextFieldValue.composition`**（否则手写输入法「一笔即字」无法成字）
- **退出保存必须区分普通文件与大文件分段编辑**：大文件下 `currentContent` 恒为 `""`，`saveAndExit` 必须保存 `pagedEditingText` 当前块（走 `persistPagedEdit` + `loadPaged` 重建），禁止 `saveContent(uri, "")`；`confirmDiscard` 也需 `cancelPagedEdit` 清理编辑态
- **未注册扩展名 / 无扩展名的文件源头禁用打开**：`MainActivity.extractFileUriFromIntent` 对 `ACTION_VIEW/EDIT` 的 `data` 与 `ACTION_SEND` 的 `EXTRA_STREAM` 做 `FileType.isSupported` 校验，未注册/无扩展名（`FileType.resolve` 为 null）→ 拒绝打开 + Toast「不支持的文件类型」；`EXTRA_TEXT` 纯文本分享保留。第二层兜底在 `loadFile` 的 `isPaged`（任何文本文件 >512KB 都走分页）

### 4.4 预览 / 渲染
- 代码块深色背景：围栏代码卡片底 `#1E1E1E`、卡片顶栏 `#2D2D2D`、行内/缩进代码块 `#1E1E1E`；未指定语言块语言标签显示 `text` 且不可选中文本；预览模式代码卡片仅横向滚动
- 非 Markdown 文件预览用 `LazyColumn` + `item(key)` 防滚动跳动；`MarkdownPreview` 用 `itemsIndexed` + `contentType` 助估尺寸
- 预览 TextView 禁用 overscroll、外部叠加滚动条（防右侧白条）
- 主题切换必须用 `effectiveDarkTheme` 参数（防 UI 卡死）
- TOC 仅 Markdown 显示（大 md 分页只读隐藏）；点击 TOC 条目仅关面板不跳转
- `com.miui.notes` 权限图片 URL 忽略、仅显图片名
- 相对路径图片不存在时预览回退灰色占位 `图片{alt}`

### 4.5 Intent 过滤器
- 必须含 `application/octet-stream`（无标准 MIME）、`text/*`、`application/json`
- pathPattern 过滤器覆盖 `.toml/.ini/.cfg/.conf/.env/.properties/.yaml/.yml/.csv/.diff/.patch/.log/.sh/.bash/.zsh/.dockerfile`；已知局限：`.conf/.env/.properties/.csv/.log/.bash/.zsh/.dockerfile` 仅一级深度、pathPattern 大小写敏感（`.TOML` 不命中）
- **VIEW 过滤器禁止带 `BROWSABLE` category**（与 `file`/`content` scheme 组合会被浏览器借深链拉起应用并传入可控文件）
- **SEND 过滤器 MIME 覆盖必须与 VIEW 一致**（接收 `ACTION_SEND` + `EXTRA_STREAM` 时需覆盖 `text/*`、`application/json`、`application/octet-stream`，否则分享 .md/.toml 时不出现在分享目标）；代码层必须优先读 `EXTRA_STREAM` 走 `copyContentUriToLocal`，回退 `EXTRA_TEXT` 走 `handleSharedText`，禁止只处理 `text/plain`；API 33+ 用类型化 `getParcelableExtra`
- **应用内"打开文件"按钮**（文件列表顶栏，`ActivityResultContracts.OpenDocument`）：MIME 过滤数组为 `text/*` + `application/octet-stream`，保持现状（不显式补代码文件 MIME）

### 4.6 架构与列表
- ViewModel 管状态、Repository 管数据、Coroutines + Flow 异步
- `FormatPlugin` 接口定义格式扩展，`FormatRegistry` 统一注册；内置 Markdown（自动格式化）/ 代码（仅高亮）/ 纯文本
- 主题模式用 `ThemeMode` 枚举（不用裸字符串）
- Markdown 编辑语法高亮覆盖 11 种元素；**围栏代码块（``` ``` / ~~~ ~~~）内部屏蔽所有高亮规则**（`scanFencedBlocks` 栈式配对识别区间 + `isInFences` 二分判定，11 个高亮方法均跳过区间内命中），防止代码里 `#`/`**`/`-`/`>`/`` ` `` 等被全篇正则误染为标题/粗体/列表/引用/行内代码；仅标题降级高亮（>32K）同样过滤；**按文档大小分流：≤64K 字符同步（filter 内即时计算），>64K 字符后台线程 + 80ms debounce 异步计算**（`SYNC_THRESHOLD_CHARS=64_000`；`REDUCED_HIGHLIGHT_THRESHOLD=16_000` 是超长文档的"降级高亮"阈值——跳过列表/引用/分隔线，勿与同步阈值混淆）；LRU 缓存 8 项，计算完成自增快照版本号触发重组，组件销毁 `cancelPending()`
- 文件列表：imported 与 local 各自按 sortMode 排序后合并；单击打开、长按多选（仅删除）
- **扫描范围（禁止全盘扫描）**：仅扫描 `Documents/`、`Download/`（Android 10+ 用 `RELATIVE_PATH LIKE 'Documents/%' OR 'Download/%'`，Android 9- 用 `getExternalStoragePublicDirectory`）；排除媒体 MIME（`video/%`、`audio/%`、`image/%`，SQL 层 + cursor 循环双保险，MIME null 放行）；**MIME 为 null 的 `.ts` 后缀按视频兜底排除**（防部分 ROM 把 `.ts` 视频识别为 TypeScript）
- **搜索后全选**：仅选中搜索过滤后的可见文件；**搜索返回键**：搜索框有焦点或关键词时先退搜索（清关键词+收键盘），不直接退出；多选模式返回键退多选
- **分享**：多选用 `ACTION_SEND_MULTIPLE`、单选 `ACTION_SEND`；编辑器与文件列表统一按后缀推断 MIME（`resolveShareMimeType`）；`BottomActionBar` 分享按钮 `enabled = selectedCount > 0`，重命名/详细信息仅单选可用
- **批量删除性能**：并发删除（每批 16 个）+ 删除成功直接从内存列表移除，不全量重扫 MediaStore
- **三键导航遮挡**：`enableEdgeToEdge` 下窗口内容延伸到系统栏后，`Scaffold` 的 `bottomBar` 不自动避让底部三键导航栏（手势导航因手势条极薄通常无感）。已处理：`BottomActionBar` 的 `Row` 加 `navigationBarsPadding()`（`Surface` 背景色铺满导航区、按钮上移避开）；`FileListScreen` 新建 FAB 加 `navigationBarsPadding()` 防御；编辑器正文由 `Scaffold` 默认 `contentWindowInsets`（含导航栏）避让，无需改。各 ROM 导航栏高度差异由系统 inset 自动适配
- **Android 11+ 非本应用文件操作必须处理 `RecoverableSecurityException`**：重命名/删除 content:// URI 捕获后通过 `IntentSender` 向用户申请授权，授权成功后自动重试；`StorageRepository` 用 `@SuppressLint("NewApi")`（该类仅 API29+ 抛出、旧系统不命中 catch，勿改 @RequiresApi 因相关方法全 API 运行）

## 5. 已知陷阱（务必避免）

### 5.1 渲染与 UI
- **预览图片上方空白根本原因**：预览 TextView `setLineSpacing(8f, mult)` 的 `mult`（>1.0）会按 replacement span（图片）行高等比放大 bottom，**仅当图片行后还有内容时**图片贴底绘制露空白。修复：`mult` 改 `1.0f`，仅留固定像素 `add`
- **Markwon 相对路径图片**：必须自定义 image loader 基于 `.md` 物理路径解析
- **上下标插件**：逐字符扫描需跳过 LaTeX 公式区间（`$...$`/`$$...$$`），否则 `^`/`~` 误识别
- **AndroidView update 回调**：勿直接 `markwon.setMarkdown()`（嵌套布局），用 `textView.post{}` 推迟到下一帧
- **滚动位置计算**：勿用固定行高，用 `TextLayoutResult.getLineTop()` / `getLineForOffset()`
- **代码块卡片重复 setText**：update 回调需比较 `codeSpanned` 引用；factory setText 后记录 `lastCodeSpanned`
- **避免 `AnimatedVisibility` 展开动画**（初始高 0 致跳动）：用 `if (!isCollapsed) + animateContentSize()`
- **避免 `mutableStateOf` 存 span 引用**：用 `Ref` 容器，防初始布局二次测量
- **`FocusRequester.requestFocus()`**：必须 try-catch `IllegalStateException`
- **动态取色**：部分国产 ROM 抛异常，try-catch 并回退自定义配色
- **状态更新后导航**：用 `Dispatchers.Main`（非 immediate）确保对话框先关再导航
- **长 .md 高亮 span 卡顿是 Compose 架构限制（勿再尝试优化）**：`VisualTransformation` 全文一次性产出 AnnotatedString，Compose `Text`/`BasicTextField` 非懒加载，大量 span 致 StaticLayout 每帧做样式区间查找（官方 JetBrains/compose-multiplatform#4023）。**已到优化上限**：`transformedText()` 缓存、长文档降级高亮（跳过列表/引用/分隔线）、去全篇基础色 span。**排除的无效方向**：继续减 span（code+bold+heading 占 94% 不可去）、伪粗体/去 Bold、异步/多线程渲染（Compose 渲染须主线程）。**唯一根治 = 换原生 EditText**（见 §10.2）

### 5.2 Markdown / LaTeX / R8
- **JLaTeXMath 反射**：大量字符串反射，R8 改名致块级公式 NPE；ProGuard 保留 `ru.noties.jlatexmath.**`、`org.scilab.forge.jlatexmath.**`；`isBlock()` 反射依赖 `-keep class io.noties.markwon.**`
- **行内公式对齐**：`fixFormulaSpanAlignment` 在 `setText` 同步调用，替换 `VerticalAlignedLatexSpan` 施偏移，比例 `INLINE_FORMULA_UPWARD_OFFSET_RATIO=0.35f`
- **`MarkwonConfig` 缓存**：按主题缓存 Markwon 实例（`@Volatile`），长文档渲染用 `Dispatchers.Default`

### 5.3 构建 / 编译
- **kapt 诡异编译错优先查源结构**：`duplicate class` / javac `TypeEnter` 多为同文件两个 companion object 污染 kapt stub。定位：临时移除 kapt 依赖（prism4j-bundler）→ `compileDebugKotlin` 暴露真实错误；勿先清缓存/改工具链
- **一 Kotlin 类只允许一个 companion object**；新常量并入既有 companion
- **构建配置实验需还原**：排查后把 `gradle.properties` 与 `app/build.gradle.kts` 的临时改动恢复原值；诊断日志及时删
- **`ColumnScope.align` 只接受 `Alignment.Horizontal`**；传 `CenterEnd` 编译不过，需 `Alignment.End`
- **图标集限制**：`Icons.AutoMirrored.*` 可用性随 icons 版本而定，不稳时退 `Icons.Default.Create`

### 5.4 状态持久化 / 文件
- **防抖落盘有竞态**：滚动中旧协程 cancel 后仍可能用过期参数写盘。正解 = 主线程同步记录最新状态 + 落盘前读最新值 + `onCleared()` 兜底
- **大文件分段编辑退出保存**（见 §4.3）：大文件 `currentContent=""`，`saveAndExit` 不得直接 `saveContent(uri, "")`；检测 `isReadOnlyPaged && pagedEditingIndex != null` 走 `persistPagedEdit`
- **签名密钥禁止驻留仓库**：`keystore.properties`/`.keystore` 放 `~/.markflow/`；`app/build.gradle.kts` 经 `providers.fileContents` 读取（配置缓存感知）；release 签名缺失在执行期 `validateReleaseSigning` → `packageRelease` 失败，禁止静默回退 debug 签名、禁止在配置期 error（防连累 debug/IDE sync）

## 6. 日志策略

- **release（R8）自动移除 `Log.v/d/i`**，仅保留 `Log.w/e/wtf`（proguard `-assumenosideeffects`）
- 源码中的日志**全部为异常分支**（文件 I/O 失败、MediaStore 异常、LaTeX 渲染失败），**正常使用无日志输出**；无任何 `Log.d/i` 常规调试日志、无崩溃上报/分析 SDK
- 调试需长期保留的日志用 `Log.e`；勿把排障逻辑写进 `Log.d`（release 不生效）

## 7. ProGuard 关键规则（app/proguard-rules.pro）

- 保留反射依赖：JLaTeXMath、Markwon、Prism4j、Coil、Hilt
- `-assumenosideeffects class android.util.Log { v(); d(); i(); }`（release 移除低级别日志）
- **改 ProGuard 后必须回归 release 构建**（混淆问题只在 release 出现）

## 8. 大文件（≥512KB）分页技术方案

### 8.1 核心设计（已拍板）
- **乱码 / 崩溃 / 流畅是三个独立问题，用三套互不相干的机制解决，勿混为一谈**
- 可编辑 = **分段编辑模型**：浏览连续分页只读、编辑时单块载入（Compose `BasicTextField` 天生持有整份文本，无法做 piece-table 无缝连续编辑）
- 字节预算分块 **~10KB/段**；单行超长（>200KB）独立成块且硬截断按编码单元对齐；预取上/下各 2 块；块缓存按累计字符 **≤10M（≈20MB）** 逐出（LinkedHashMap 插入序 FIFO，非严格 LRU，注释勿再写 LRU）
- 块索引 `List<Long>`（O(1) seek）；`content://` 必须用 PFD/AssetFileDescriptor 拿 fd seek，`openInputStream+skip` 会退化为 O(n) 禁用；超大文件用懒惰/稀疏索引按需增量补建
- 编码：BOM 优先（`EF BB BF` UTF-8 / `FF FE` UTF-16LE / `FE FF` UTF-16BE）→ 严格 UTF-8 位模式 → GB18030/系统默认回退；UTF-8 校验须容忍采样结尾不完整序列；解码 RECOVER/REPLACE 不抛异常；提供手动切换编码入口
- 写回：`file://` 用 `RandomAccessFile` 随机写（长度不变原地覆盖；变化时"新块+尾部落盘临时文件拼接"）；`content://` 整文件临时文件覆盖（O(n)，异步+进度+脏段合并）
- 撤销：页面内 UndoRedoManager，**不做跨页全局撤销**
- **写回安全（高危）**：`persistFileRandomWrite` 先把受影响区（脏块到 EOF）整体备份临时文件，写失败从备份回滚 + `setLength` 恢复原长，防中断留半新半旧文件

### 8.2 关键实现要点（防回退）
- **UTF-16 分页错位乱码**：`PagedTextSource` 不能按单字节 `0x0A` 找换行（UTF-16 会从奇数字节界解码整块错位）；块边界按编码单元对齐（UTF-16LE 找偶偏移 `0A 00`、BE 找 `00 0A`、硬截断取偶；UTF-8 硬截断回退完整多字节字符）
- **分段保存缓存错位**：`savePagedEdit`/`saveAndSwitchEdit` 的 `preserveUntil` 语义 = "保留索引 < 该值的块"，**被编辑块本身绝不可保留**（否则保存后回显编辑前内容）；目标段在编辑段之后时按"旧字节偏移 + 长度差"在新索引中重新定位
- 分页核心组件：`PagedTextSource`（~10KB + 懒惰块索引 + O(1) seek + 单行兜底）、`StorageRepository.openSeekable`（file→RAF / content→PFD.FileChannel）、字节预算 LRU + ±2 块预取、无限 LazyColumn + EOF

### 8.3 大文件压测方案（真机）
- 样本矩阵：阈值内小文件 / 跨阈值 ~1MB / ~10MB GBK（乱码）/ ~50MB UTF-8（滚动跳块内存）/ BOM / UTF-16 / 超长单行，覆盖 `file://`（push Download）与 `content://`（push Documents/MarkFlow 触发媒体扫描）
- 关键用例：冷启动无 OOM、滚动流畅、随机跳块 O(1)、编码正确、模式切换×10、分段编辑保存（file/content 各一）、连续编辑无错位、编码切换重载、内存峰值在 LRU 预算内、无 tmp 残留、小文件回归
- 写回正确性：保存后 pull 回 PC 与原始样本逐字节比对，**仅被编辑段落对应字节变化，其余一致**

## 9. 验证技巧

- **判断修复是否入包**：源码 mtime 早于 APK 构建时间可快速确认；严格用 dex 符号检查
- **dex 验证**：读 APK 内 `classes*.dex`——查目标字符串字面量是否存在；解析 method_id 表统计 `android/util/Log` 引用，`v/d/i` 应为 0、`e/w` 保留，据此验证 R8 日志移除与反射 keep
- **R8 注意**：自定义类名可能被重命名，但功能代码与字符串字面量保留；字符串命中即证代码已进包

## 10. 版本与待办（当前 1.2.0）

### 10.1 已落地里程碑（供追溯）
大文件分页全链路（Phase 1 编码检测 / Phase 2 分页内核 / Phase 3 分段编辑 / Phase 4 预览）· 图片插入（写同级 images/）· 行内 LaTeX 垂直对齐 · 阅读位置记忆 · 手写输入法 · 搜索位置保持 · 编辑栏单图标 + 图标顺序 · 新建保留后缀 · 重命名可改后缀+确认+MIME 同步 · R8 反射保留与 release 日志清理 · 签名密钥外置与执行期校验 · 大文件/大 md 阈值统一 512KB · 超大 md 改当大 TXT 分页处理 · 扫描范围收紧防 .ts 混入 · 搜索全选/返回键 · 多文件分享 · 批量删除并发 · 外部文件导入净化 · Android 11+ `RecoverableSecurityException` 授权 · 移除 VIEW `BROWSABLE` · 编辑器与列表分享 MIME 统一 · **跳顶 bug 根治（B′ + `rememberUpdatedState`，见 §4.2）** · lint 清零（安全修复 + NewApi 抑制） · **稳定性/性能加固（9/6）**：编辑态高亮三档阈值（`EDIT_LIVE_HIGHLIGHT_CHARS=32K` 仅标题）+ `catch(Throwable)` 兜底（MarkwonConfig/MarkdownPreview/高亮器，OOM/StackOverflowError 从闪退变降级）+ `largeHeap` + `onTrimMemory`/`onLowMemory` 清 Markwon 缓存 + 撤销栈 `maxSteps` 50→20 + `TocParser` 正则常量化 + `saveAndExit` 后台化 TOC 解析 + `onCleared` 去 `runBlocking` + 三键导航 `navigationBarsPadding` + 编辑器返回键分层取消光标（见 §4.2）

### 10.2 待办（下次推进）
- [ ] 真机回归 1.2.0：大文件阅读位置 / Phase 5 压测、大 md 分页只读+分段编辑、搜索位置保持、手写输入、图片插入与预览、单图标切换、新建后缀窗、重命名后缀、**长按框选不跳顶（含浏览态直接长按）**
- [ ] 是否 `git push --force origin main` 推送重建后的基线到 Gitee（本地为单根提交，与远端无共同祖先，普通推送会被拒需 --force；用户保留 URL 未推，待自行决定）
- [ ] **（未来规划，暂未实现）编辑模式图标顺序拟改为「插入图片 → 搜索 → 撤销 → 反撤销」**：当前仍为「搜索 → 撤销 → 反撤销 → 插入图片」（见 §4.2）；此为用户明确意向，调整时需同步 §4.2 与 README
- [ ] **（长期方向，暂未实现）编辑态换原生 EditText 根治长 .md 高亮卡顿**：唯一能根除 span 卡顿的方案（原生 EditText 支持"动态 span 只在可见区域应用 + 滚动更新"，Markor 做法，蓝本思路见源码注释）。工程量=重写编辑器核心（撤销重做、自动保存、自动格式化、语法高亮 Spannable 化），需 Kotlin 重写 + Compose `AndroidView` 集成，风险大，仅适合"编辑器 2.0"一次性规划，勿混入普通 bug 修复

## 11. Git / 环境经验沉淀

- **路径存在性校验勿用相对路径 + 单次输出下结论**：删除/校验关键目录必须用绝对路径复核并观察真实文件列表（曾因相对路径 Test-Path 误判误删）
- **曾被跟踪过的文件绕过 `.gitignore`**：`.gradle/`、`local.properties` 等即使已入 ignore，一旦历史 `git add` 过，重建基线时 `git add -A` 仍会纳入，需显式 `git rm --cached`
- **git 历史瘦身依赖远端跟踪引用**：`remotes/origin/<branch>` 若仍指向被丢弃旧 commit，`git gc --prune` 不会清旧对象；须先重定向该引用（或删除），再 `git reflog expire --expire=now --all && git gc --prune=now --aggressive`
- **PowerShell 长命令注意引号平衡**：多余引号会触发 `The '--' operator works only on variables` 解析错
- **lint NewApi（RecoverableSecurityException）**：仅 API29+ 抛出的异常，旧系统运行不到该 catch，可用 `@SuppressLint("NewApi")` 抑制（勿改用 `@RequiresApi`，方法全 API 运行）；此类"API 级"错误不影响 assembleDebug/assembleRelease
