# MarkFlow 静态代码审查问题清单

- **审查日期**：2026-09-03
- **审查范围**：`app/src`（Kotlin + Jetpack Compose + Hilt）、`AndroidManifest.xml`、Gradle/ProGuard 配置、签名/密钥文件
- **审查方式**：5 路并行专项静态扫描（构建/依赖/密钥、存储/文件访问、Markdown 渲染/LaTeX、编辑器 UI/行为、架构/DI/权限安全），只读分析
- **判定基准**：`AGENTS.md` 已确认滞后于代码。**当代码与 `AGENTS.md` 描述不一致时，以代码为基准**。因此本清单将问题分为两类：
  - **A 类（真实代码风险）**：与文档无关，代码本身存在缺陷/风险。
  - **B 类（仅文档待更新）**：代码实现与 `AGENTS.md` 不同，但代码即正确实现，属文档漂移，非代码缺陷。
- **总体风险评级**：**中**；最高优先级为 A1（大 TXT 数据丢失）。

---

## 风险总览

| 编号 | 严重度 | 类别 | 问题 | 位置 |
|------|--------|------|------|------|
| A1 | 🔴 严重 | A | 大 TXT「保存并退出」写空文件（数据丢失） | `EditorViewModel.kt:1082-1113` 等 |
| A2 | 🟠 高 | A | 外部 content:// 导入文件名未净化→路径穿越 | `MainActivity.kt:140-160` |
| A3 | 🟠 高 | A | 明文弱签名密钥驻留仓库根 | `keystore.properties` |
| A4 | 🟡 中 | A | 长文档渲染在主线程（卡顿/ANR） | `MarkdownPreview.kt:71-100` |
| A5 | 🟡 中 | A | compileSdk=35 + AGP 8.4 用 suppress 掩盖 | `gradle.properties:17` |
| A6 | 🟡 中 | A | HtmlPlugin 渲染不可信原始 HTML（远程 `<img>`） | `MarkwonConfig.kt:174` |
| A7 | 🟡 中 | A | 声明 `MANAGE_EXTERNAL_STORAGE` 违反最小权限 | `AndroidManifest.xml:21` |
| A8 | 🟡 中 | A | 重命名/删除未处理 `RecoverableSecurityException` | `StorageRepository.kt:804-815` |
| A9 | 🟡 中 | A | `.ts` 视频在 MIME 为 null 时混入列表 | `FileType.kt:51-52` |
| A10 | 🟡 中 | A | `textFieldValue` 双向同步 + `LaunchedEffect` 整串为 key | `EditorScreen.kt:158` |
| A11 | 🟡 中 | A | 导出 Activity `BROWSABLE` 与 `file/content` 误配 | `AndroidManifest.xml:67` |
| A12 | 🟡 中 | A | Release 缺失签名配置静默回退 debug 密钥 | `app/build.gradle.kts:44-53` |
| A13 | 🟢 低 | A | `allowBackup="true"` 可被 adb 提取 | `AndroidManifest.xml:33` |
| A14 | 🟢 低 | A | `PlainTxtPreview` 单行超长未字节切块 | `EditorScreen.kt:858` |
| A15 | 🟢 低 | A | `pagedReader` 未在 `onCleared` 置空 | `EditorViewModel.kt:119` |
| A16 | 🟢 低 | A | 全局 `exclude annotations-java5` 过宽 | `app/build.gradle.kts:87-89` |
| A17 | 🟢 低 | A | configuration-cache 与 kapt/Hilt 兼容 | `gradle.properties:5` |
| A18 | 🟢 低 | A | Markwon 4.6.2 已停止维护 | `libs.versions.toml:21` |
| A19 | 🟢 低 | A | 编辑器单文件分享 MIME 硬编码 `text/*` | `EditorScreen.kt:1362` |
| B1–B7 | — | B | 文档漂移（§5.1/§4.5/§4.1/§4.2/§4.4 等） | 见 B 类章节 |

---

## A 类：真实代码风险（含复现步骤）

### A1 🔴 严重 — 大 TXT「保存并退出」写空文件（数据丢失）

- **位置**：`EditorViewModel.kt:1082-1113`（`saveAndExit`）、`:1062-1068`（`requestNavigateBack`）、`:800-831`（`saveFile`）、`:181-182`（大 TXT 加载后 `currentContent=""`）；`EditorScreen.kt:297-300`（顶栏返回箭头）。
- **说明**：大 TXT（`isReadOnlyPaged=true`）加载后 `currentContent` 恒为 `""`，分段编辑内容只存在 `pagedEditingText`，未合并回 `currentContent`。顶栏返回箭头触发「保存并退出」时直接 `saveContent(uri, currentContent)`，把整个大文件覆盖成空。系统返回键（`EditorScreen.kt:150-156`）会先 `cancelPagedEdit()` 而安全，**两处行为不一致**。
- **复现步骤**：
  1. 准备一个 **>0.5MB** 的 `.txt` 文件（如 50MB），放入手机 `Documents/` 或 `Download/`。
  2. 用 MarkFlow 打开它（进入分页只读浏览）。
  3. 点 **编辑模式** → 长按某一段进入分段编辑 → 输入一些内容（此时 `hasUnsavedChanges=true`、`currentContent` 仍为 `""`）。
  4. 点击**顶栏左上角返回箭头 ←**（不是系统返回键）。
  5. 在弹出的确认框中点「**保存并退出**」。
  6. **结果**：用文件管理器查看该 txt，大小变为 0（或接近空），原内容丢失。
  7. **对照**：同样操作改用**系统返回键**退出则安全（先 `cancelPagedEdit`）。

---

### A2 🟠 高 — 外部 content:// 导入文件名未净化 → 路径穿越

- **位置**：`MainActivity.kt:140-160`（`copyContentUriToLocal`）、`:186-201`（`guessFileNameFromUri`）、`:206-219`（`handleSharedText`）。
- **说明**：目标文件名直接取自外部 `DISPLAY_NAME` 或 `EXTRA_TITLE` / URI path 末段，未做 `../` 或 `/` 净化即 `File(destDir, fileName)` 写入 `filesDir/imported/`（或 `shared/`）。恶意/被篡改的 ContentProvider 可让文件写到沙箱预期目录之外，覆盖/污染应用私有文件。项目已有 `sanitizeFileName`（`StorageRepository.kt:849`）可复用但未调用。
- **复现步骤**：
  1. 准备一个可控 `DISPLAY_NAME` 的 ContentProvider（测试可用一个简单 App 或 `ContentProvider` 桩，返回 `DISPLAY_NAME = "../../shared_prefs/secrets.xml"`）。
  2. 通过该 Provider 以 `ACTION_SEND` / `ACTION_VIEW` 把"文件"分享/打开给 MarkFlow。
  3. MarkFlow 的 `copyContentUriToLocal` 以该名 `File(destDir, fileName)` 写盘。
  4. **结果**：文件被写到 `filesDir/imported/` 之外（沙箱内越界），可能覆盖私有文件。
  5. 简化自测：用 `adb` 或测试 Activity 构造 `content://` 指向一个 `DISPLAY_NAME` 含 `../` 的 Uri 拉起 MarkFlow。

---

### A3 🟠 高 — 明文弱签名密钥驻留仓库根

- **位置**：`keystore.properties`（`storePassword=markflow2026`、`keyPassword=markflow2026`，且两密码相同）、`markflow-release.keystore`。
- **说明**：release 签名密钥与明文密码并排放置于仓库根目录。经 `git check-ignore` / `git log --all` 核实，二者当前已被 `.gitignore` 忽略、**未进 VCS**，故无版本库泄露；风险仅在**工作副本被打包/云同步/备份外传**时成立（弱密码 `markflow2026` 易猜解，密钥丢失则无法在应用市场持续更新）。
- **复现/验证步骤**：
  1. 在仓库根执行 `git check-ignore keystore.properties markflow-release.keystore` → 应返回两文件名（确认被忽略）。
  2. 将整个 `MarkFlow` 文件夹压缩发给他人 / 上传云盘 / 误 `git add` → 密钥与密码随副本泄露。
  3. 验证弱密码：`keytool -list -keystore markflow-release.keystore`（密码 `markflow2026`）可解锁 → 说明密码强度低。

---

### A4 🟡 中 — 长文档渲染在主线程（卡顿/ANR）

- **位置**：`MarkdownPreview.kt:71-100`（`remember` 内主线程做图片/LaTeX 预处理 + `MarkdownParser.parse`）、`:266,294`（`markwon.setMarkdown` 主线程同步）；`EditorScreen.kt:511`。
- **说明**：预处理（多个正则）+ 解析 + `setMarkdown` 全部在组合/主线程执行，未迁移到 `Dispatchers.Default`。大文档易掉帧甚至 ANR。
- **复现步骤**：
  1. 准备一个含大量代码块、LaTeX（`$...$`/`$$...$$`）、相对图片的**几 MB** Markdown。
  2. 用 MarkFlow 打开 / 在编辑态切到预览。
  3. 观察：UI 明显卡顿、掉帧；低端机可能弹"应用无响应"。
  4. 用 Android Studio **Profiler → CPU → Method Tracing** 录制，可见 `MarkdownPreview` 的 `remember{}` 与 `setMarkdown` 调用栈落在 `main` 线程。

---

### A5 🟡 中 — compileSdk=35 + AGP 8.4 用 suppress 掩盖

- **位置**：`gradle.properties:17`（`android.suppressUnsupportedCompileSdk=35`）、`app/build.gradle.kts:20`（`compileSdk = 35`）、`gradle/libs.versions.toml:4`（`agp = "8.4.0"`）。
- **说明**：AGP 8.4 官方仅支持到 compileSdk 34，用 `suppressUnsupportedCompileSdk` 抑制兼容性报错，属"编译期被忽略、运行/资源处理期可能异常"的隐患。
- **复现步骤**：
  1. 将 `gradle/libs.versions.toml` 的 `agp` 改为 `8.6.0`（或更高，原生支持 API 35）。
  2. 删除 `gradle.properties:17` 的 `android.suppressUnsupportedCompileSdk=35`。
  3. 执行 `.\gradlew.bat clean assembleDebug`。
  4. 观察：若移除 suppress 后构建报 compileSdk 不兼容，则说明原 suppress 掩盖了真实问题；升级 AGP 后应保持无 suppress 可正常构建。
  5. 当前（8.4 + suppress）下被掩盖的 AAPT2/资源处理异常，可在特定资源/机型上表现为预览或构建偶发错误。

---

### A6 🟡 中 — HtmlPlugin 渲染不可信原始 HTML（远程 `<img>`）

- **位置**：`MarkwonConfig.kt:174`（`.usePlugin(HtmlPlugin.create())`）。
- **说明**：预览会渲染 Markdown 文档中的原始 HTML。`preprocessImagePaths` 只处理 `![](...)`，对 HTML 内联 `<img src="http(s)://...">` 完全放行，经 Coil 直接加载。**当前因 `AndroidManifest` 未声明 `INTERNET` 权限，远程图片实际无法加载（有效缓解）**；一旦将来加 `INTERNET` 权限即构成隐私泄露/追踪像素通道，且绕过 §4.1 的图片安全处理设计。
- **复现步骤**：
  1. 创建一个 Markdown 文件，内容含：`<img src="https://attacker.example/track.gif">`。
  2. 用 MarkFlow 打开预览 → 当前因无 INTERNET 权限，图片加载失败（无外传）。
  3. **验证泄露通道**：在 `AndroidManifest.xml` 加入 `<uses-permission android:name="android.permission.INTERNET"/>` 后重装，再预览该文件 → Coil 会请求远程图片，造成隐私泄露/追踪。
  4. 第三方通过 `ACTION_VIEW` 传入含 `<img>` 的 `content://` 文档时同样走此通道。

---

### A7 🟡 中 — 声明 `MANAGE_EXTERNAL_STORAGE` 违反最小权限

- **位置**：`AndroidManifest.xml:21`。
- **说明**：声明了 `android.permission.MANAGE_EXTERNAL_STORAGE`（`tools:ignore="ScopedStorage"`，无 `maxSdkVersion`）。代码未实际申请该权限（无 `requestPermission` / `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 调用），但声明本身即违反最小权限，**一旦误调用即授予全盘访问**，远超 §4.6 限定的 Documents/Download 范围。
- **复现步骤**：
  1. 查看 `AndroidManifest.xml:21` 确认权限声明。
  2. 全局搜索 `MANAGE_EXTERNAL_STORAGE` / `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 确认代码未申请（已确认）。
  3. 风险触发条件：若将来误加申请逻辑并引导用户授权 → 应用获全盘访问。当前仅为"声明即隐患"，无直接运行期复现。

---

### A8 🟡 中 — 重命名/删除未处理 `RecoverableSecurityException`

- **位置**：`StorageRepository.kt:804-815`（重命名）、`:698-723`（批量删除）。
- **说明**：对"非本应用创建"的文件，`contentResolver.update/renameDocument/delete` 会抛 `RecoverableSecurityException`（继承自 `IOException`，非 `SecurityException`）。当前仅 `catch (Exception)` 吞掉 → 重命名提示"不支持的文件类型"、删除静默失败，未走 `MediaStore.createWriteRequest` / `createDeleteRequest` 向用户授权。
- **复现步骤**（需 Android 11+ / API 30+）：
  1. 用**系统文件管理器或其他 App**（非 MarkFlow）在 `Download/` 新建一个 `.txt` 文件。
  2. 用 MarkFlow 打开它 → 进入编辑 → 点重命名，改为新后缀/名字。
  3. **结果**：弹"不支持的文件类型"或重命名失败；无系统授权弹窗。
  4. 勾选该文件 → 点删除 → **结果**：删除静默失败（无提示，文件仍在）。
  5. **期望**：应弹 `createWriteRequest` / `createDeleteRequest` 让用户授权。
  6. 对照：MarkFlow 自己创建于 `Documents/MarkFlow` 下的文件可正常改名/删除。

---

### A9 🟡 中 — `.ts` 视频在 MIME 为 null 时混入列表

- **位置**：`FileType.kt:51-52`（`ts`/`tsx` 注册为 TypeScript）+ `StorageRepository.kt:133-142,188-194`。
- **说明**：白名单把 `%.ts` 作为受支持扩展名纳入，媒体排除仅在 MIME 为 `video/audio/image` 时生效。若某 `.ts` 视频在 MediaStore 中 **MIME 为 null**（手工拷贝/部分 ROM 常见），会被放行并因命中 `%.ts` 加入文本文件列表。
- **复现步骤**：
  1. 在 `Documents/` 或 `Download/` 放一个 **MIME 为 null** 的 `.ts` 视频（如用手动拷贝使 MediaStore 未识别 MIME，或用 `adb shell` 写入后不触发媒体扫描）。
  2. 打开 MarkFlow 文件列表。
  3. **结果**：该 `.ts` 视频出现在 Markdown 文本文件列表中（可能卡顿/无法预览）。
  4. **对照**：MIME 正确为 `video/mp2t` 时会被媒体排除逻辑过滤掉。

---

### A10 🟡 中 — `textFieldValue` 双向同步 + `LaunchedEffect` 整串为 key

- **位置**：`EditorScreen.kt:158`（顶层 `textFieldValue`）、`:164-195`（`LaunchedEffect(uiState.currentContent)` 回灌）、`:446-489`（`onValueChange` 又写回）。
- **说明**：`BasicTextField` 的 `value` 既由本地 `textFieldValue` 持有，又通过 `LaunchedEffect(currentContent)` 从 ViewModel 回灌，靠 `isInternalUpdate` 与相等判断防回环（双向同步反模式）。`LaunchedEffect` 的 key 是**整个 `currentContent` 字符串**，每次按键产生新对象并做 O(n) 全量比较，大文件有卡顿/重组隐患。
- **复现步骤**：
  1. 打开一个接近 0.5MB 的 Markdown 文本，进入编辑模式。
  2. 快速连续输入多字符。
  3. 观察：可能的光标跳动、输入延迟/卡顿。
  4. 用 Android Studio **Layout Inspector → Recomposition counts**，可见每次按键触发以整串为 key 的 `LaunchedEffect` 重新执行。

---

### A11 🟡 中 — 导出 Activity `BROWSABLE` 与 `file/content` 误配

- **位置**：`AndroidManifest.xml:67`（VIEW 过滤器含 `BROWSABLE` + `scheme file/content`）。
- **说明**：`BROWSABLE` 设计用于 `http/https` 以便网页拉起应用，与 `file/content` 组合为异常配置，可能被浏览器借深链拉起 MarkFlow 并传入攻击者可控文件。结合 A2/A6 构成跨应用内容渲染/落盘入口。
- **复现步骤**：
  1. 查看 `AndroidManifest.xml:67` 确认 VIEW 过滤器同时含 `BROWSABLE` 与 `file`/`content` scheme。
  2. 执行 `adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "file:///sdcard/Download/test.md"`，观察是否被 MarkFlow 接收并打开该文件。
  3. 也可在网页中构造 `<a href="file:///.../evil.md">open</a>` 点击，验证是否拉起 MarkFlow。

---

### A12 🟡 中 — Release 缺失签名配置静默回退 debug 密钥

- **位置**：`app/build.gradle.kts:44-53`。
- **说明**：release 的 `signingConfig` 为 `if (keystorePropertiesFile.exists()) { ... } else signingConfigs.debug`。当 CI/发布机缺 `keystore.properties` 时 `assembleRelease` 仍"成功"，但用 **debug 密钥**签名，无法正式发布/覆盖升级。（按"代码为准"后不再是文档矛盾，但静默降级仍是真实运营风险，需确认是否预期设计。）
- **复现步骤**：
  1. 在**不提供** `keystore.properties` 的环境（或临时重命名/移走该文件）执行 `.\gradlew.bat assembleRelease`。
  2. 构建"成功"产出 `app-release.apk`。
  3. 执行 `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`。
  4. **结果**：证书指纹为 debug（CN=Android Debug），而非 release 密钥。
  5. 用该包尝试上架/覆盖升级 → 失败（签名不符）。

---

### A13 🟢 低 — `allowBackup="true"` 可被 adb 提取

- **位置**：`AndroidManifest.xml:33`（未定义 `backupRules`）。
- **复现步骤**：`adb backup` 后可提取应用私有数据（SharedPreferences 含隐藏 URI 集合、阅读位置等，敏感度低）。建议设 `false` 或配置 `backupRules`。

### A14 🟢 低 — `PlainTxtPreview` 单行超长未字节切块

- **位置**：`EditorScreen.kt:858`（`content.split('\n')`）。
- **复现步骤**：准备一个约 0.49MB 的**单行** txt，用 MarkFlow 预览，观察内存/卡顿（整行一次性持入并渲染）。

### A15 🟢 低 — `pagedReader` 未在 `onCleared` 置空

- **位置**：`EditorViewModel.kt:119,1152`。
- **复现/验证**：退出大 TXT 编辑器后，对象仅待 GC（无 FD 泄漏，`PagedTextSource` 每次读都 `open→finally close`）；建议在 `onCleared` 显式 `pagedReader = null` 助 GC。

### A16 🟢 低 — 全局 `exclude annotations-java5` 过宽

- **位置**：`app/build.gradle.kts:87-89`（`configurations.all { exclude ... }`）。
- **复现/验证**：构建正常；该全局排除可能误伤其他依赖对 annotations 的引用。收窄到 Prism4j 相关 configuration 即可。

### A17 🟢 低 — configuration-cache 与 kapt/Hilt 兼容

- **位置**：`gradle.properties:5`（`configuration-cache=true`）。
- **复现步骤**：Gradle 8.7 / AGP 8.4 下，部分 kapt/Hilt/Android 任务在 configuration cache 下报 "not compatible"，导致构建失败；可临时关闭验证。

### A18 🟢 低 — Markwon 4.6.2 已停止维护

- **位置**：`gradle/libs.versions.toml:21`。
- **说明**：`io.noties:markwon` 原作者已归档，长期存在传递依赖过期风险，当前无已知 CVE。

### A19 🟢 低 — 编辑器单文件分享 MIME 硬编码 `text/*`

- **位置**：`EditorScreen.kt:1362-1367`。
- **复现步骤**：编辑一个 `.json` 文件 → 点分享 → 用 `resolveShareMimeType` 之外的硬编码 `text/*` 发送；对照文件列表分享路径（精确推断）策略不一致，接收方识别精度有差异。

---

## B 类：仅文档待更新（非代码缺陷，代码即正确实现）

> 这些项代码与 `AGENTS.md` 不同，但**以代码为基准**，代码即正确；只是 `AGENTS.md` 需回写对齐。下面给出"如何观察差异"。

### B1 — §5.1 上下标实现方式（代码用 `<sup>/<sub>` HTML）
- **代码现状**：`SuperscriptPlugin.kt:66`、`SubscriptPlugin.kt:66` 将 `^text^`→`<sup>`、`~text~`→`<sub>`，依赖 `HtmlPlugin`。
- **文档说法**：§5.1 称"HtmlPlugin 不支持 `<sub>/<sup>`，用 Unicode 占位 + Editable span 替代"。
- **如何观察**：打开含 `^上标^` 与 `~下标~` 的 md 预览，确认上/下标是否**正确渲染**（若渲染正常则代码正确、文档过时；若渲染为字面文本则需修复）。

### B2 — §5.1 AndroidView `update` 同步 `setMarkdown`
- **代码现状**：`MarkdownPreview.kt:277-321` 在 `update` 回调内同步调用 `markwon.setMarkdown`（为规避 LazyColumn/滚动跳动的有意取舍）。
- **文档说法**：§5.1 要求用 `textView.post{}` 推迟到下一帧。
- **如何观察**：预览中滚动/切换文档，观察是否出现滚动跳动（若无则说明同步调用在当前 `Column+verticalScroll` 下可接受）。

### B3 — §4.5 pathPattern 未达 1-3 级全覆盖
- **代码现状**：`AndroidManifest.xml:111-190` 实际 2 级仅 8 种、3 级仅 4 种扩展名，与文档"31 个 pattern"计数不符。
- **文档说法**：§4.5 要求 1-3 级全覆盖。
- **如何观察**：用文件名形如 `a.b.toml`、`x.y.z.cfg`（多点/嵌套）的文件，通过文件管理器"打开方式"选 MarkFlow，看是否能被匹配拉起（可能不被匹配）。

### B4 — §4.1 导入落点
- **代码现状**：`StorageRepository.kt:974-1024` 把"打开文件"按钮导入的外部文件复制到 MediaStore `Documents/MarkFlow`，非 `filesDir/imported/`。
- **文档说法**：§4.1 要求先复制到 `filesDir/imported/`。
- **如何观察**：用"打开文件"按钮导入外部文件后，去系统文件管理器的 `Documents/MarkFlow` 目录，可见生成了一份副本（且不会出现在"最近打开"区）。

### B5 — §4.2 `consumeBringIntoView()` 缺失
- **代码现状**：`EditorScreen.kt:1145-1151` 用外层 Box + `graphicsLayer` 偏移补偿的自定义方案，未用 `Modifier.consumeBringIntoView()`。
- **文档说法**：§4.2 要求用该 Modifier 防长按选择回弹顶部。
- **如何观察**：编辑长文本时**长按选择**一段文字，观察文本是否回弹到顶部（若无回弹则自定义方案有效）。

### B6 — §4.4 代码块配色不统一
- **代码现状**：`CodeBlockCard.kt:79-80` 代码区 `#1E1E1E`、顶栏 `#2D2D2D`；`MarkwonConfig.kt:146,608` 为 `#2D2D2D`。两条渲染路径背景色不一致。
- **文档说法**：§4.4 要求统一 `#2D2D2D`。
- **如何观察**：预览含代码块的 md，观察代码区背景色（实际为 `#1E1E1E`）与文档声称的 `#2D2D2D` 不一致；属轻微内部视觉不一致。

### B7 — 其他纯措辞/UX 差异
- 工具栏按钮 **36.dp vs 文档 34.dp**（`EditorScreen.kt:949` 等）。
- 打开文件即用 `requestFocus()`（`EditorScreen.kt:184`），与 §4.2"不请求焦点"措辞略有出入（行为可接受）。
- 双 `BackHandler` 分层（`FileListScreen.kt:116-129`）：多选模式 + 搜索激活时的返回优先级。
- 全选文案基于全部文件数而非可见数（`FileListScreen.kt:786-808`）。
- 编辑器单文件分享 MIME 策略与列表分享不一致（见 A19）。
- 搜索"当前匹配"橙色高亮不随匹配项跳转刷新（`MarkdownPreview.kt:304-319`）。
- `CodeBlockCard` `setLineSpacing(4f, 1.3f)` 与文本段 `1.0f` 不一致（可接受）。

---

## 修复优先级建议

1. **最高优先**：**A1**（大 TXT 数据丢失）——在 `requestNavigateBack`/`saveAndExit`/`saveFile` 中对 `isReadOnlyPaged && pagedEditingIndex != null` 先提交或取消分段编辑，禁止对大 TXT 直接 `saveContent(currentContent)`。
2. **高优先**：**A2**（路径穿越）、**A3**（密钥外传风险，移出仓库 + 改强密码）。
3. **中优先**：**A4/A5**（渲染线程与 AGP 升级）、**A6**（HtmlPlugin 原始 HTML，建议移除或统一过滤）、**A8**（RecoverableSecurityException 授权流程）、**A9**（.ts 视频兜底）、**A10**（状态同步）、**A11**（BROWSABLE）、**A12**（签名静默降级，需确认设计）。
4. **低优先**：A13–A19 及 B 类文档回写。

> 本清单为只读审查产出，未对任何源文件做修改。B 类问题如需落地，建议把 `AGENTS.md` 对应章节（§4.1/§4.2/§4.4/§4.5/§5.1/§5.2）修订为代码现状。
