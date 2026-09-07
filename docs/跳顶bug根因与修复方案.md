# X-H6 长按选区视口跳顶 —— 根因诊断与修复方案

> 版本：2026-09-05 03:48（**真正根因定位 + B′ rememberUpdatedState 修复定案 + 探针清除**）
> 状态：**已根治（最终定案）**。B′（长按前落点锚定）此前因 pointerInput 闭包陈旧捕获空文本而从未真正生效；探针定位到该根因后，用 `rememberUpdatedState` 修复，冷启动直接长按亦不再跳顶。全部探针(XH6)/responder/主动拉回/graphicsLayer/首载聚焦等脚手架已删除，纯净版装机并通过视觉回归。
> 关联代码：`app/src/main/java/com/markflow/editor/ui/screens/editor/EditorScreen.kt`（B′ 在 EditContentView 的 BasicTextField pointerInput + `rememberUpdatedState`）
> 埋点 Tag：已全量清理，无任何运行时日志残留（正式版安全）

---

## 0. 结论速览（当前正确认知）

**现象**：编辑态，在**无可见光标**（折叠光标逻辑上停在文本开头 `offset 0`）时，第一次长按框选，视口被滚回文章顶部一次；此后无论怎么框选都不再跳。md / txt 均复现，与异步高亮无关。

**根因（已确诊，有真机 `sel=0` 日志铁证）**：

> 文件冷启动聚焦时，代码把折叠光标设在 `TextRange(0)`（文本开头）。用户滚动到文档中段阅读时，该折叠光标成为"**视口外的幽灵光标**"（逻辑 offset = 0，但视口在中段）。此时长按框选，`BasicTextField` 内部做选区 reveal，用的是这个 **offset 0 的幽灵光标**，于是把视口滚回顶部去显示开头 —— 表现为"跳顶"。

**核心机制要点**：
- 真正"发力"导致跳顶的，是 **BasicTextField 内部基于旧 selection(offset 0) 的 reveal**，它正确地把视口滚向"光标所在 offset"，只是光标恰好远在开头。
- "光标存在（在视口内）时长按不跳" —— 因为 reveal 目标就近，无需大幅滚动。
- "为何只跳一次"（2026-09-05 COMBO probe 修正）：真正把 selection 钉在 offset 0 的**只有文件冷启动首载一次**。切预览↔编辑**不会**把 selection 复位到视口外/offset 0（见 §1.4）——probe 证实一旦用户点/长按落点，selection 即停在真实位置、切多少次预览都不回 0。故"再次制造幽灵→再跳"只可能来自"冷启动后滚动浏览却从未落点"这一条路径。
- 该规律**在老版本正式版同样成立**，是从早期就存在的旧行为，**不是近期某次改动引入的回归**。

**历史教训**：此前多轮"主动拉回 / responder / graphicsLayer 补偿 / 删 graphicsLayer"都是这条根因的**下游治标**（事后把被滚走的 scroll 拉回来），只会"闪一下再拉回"，无法根除。

### ⭐ 真正根因补记（2026-09-05 03:40 探针铁证，覆盖此前所有"幽灵值"假设）

> **此前的"幽灵 offset 0 → TextField reveal"只是触发器；真正的 bug 是 B′ 自己从没生效过。**

- **现象新证据（用户真机 + 探针）**：纯 B′ 版下，冷启动直接长按 → 跳顶；但**切一次预览→切回编辑后再长按 → 永不跳**。
- **关键推导**：A/B 两种情形 `selection` 都是 `TextRange(0)`、都没点过文本、都没聚焦，唯一差异 = B 中 BasicTextField 被移出组合又**重建**。→ 与"文首光标/offset0 的值"无关（用户判断正确）。
- **探针日志铁证**：冷启动后滚动到 `scroll=9990/11841`（文本早已非空、能滚这么远），B′ 仍打 `DOWN-miss textEmpty=true`——**B′ 读到的 text 一直是空**；跳顶瞬间 `JUMP cursorFollowScrolling=false`（非光标跟随拉回）。切预览切回重建后，B′ 才打出 `DOWN-hit textLen=6798 off=… → SETSEL`。
- **根因**：`pointerInput(Unit)` 的 suspend 块 **key=Unit 恒定，只随首次组合启动、不随重组重启**，闭包捕获的是**首次组合时 `textFieldValue`（空文本）的陈旧引用**。B′ 里 `text = textFieldValue.text` 恒空 → `text.isEmpty()` 守卫恒真 → **永不执行落点 setSelection** → 长按 reveal 走幽灵 offset0 → 跳顶。`layoutResult` 是 `mutableStateOf` 读取到最新（非空），唯 text/selection 陈旧，故此前一直误判是"幽灵值"问题。
- **为何切预览能"治愈"**：EditContentView 移出组合销毁 → 切回重建 → pointerInput 重启 → 捕获到最新非空 textFieldValue → B′ 恢复生效。这也解释了为何此前 B′ 验证"通过"是假象（验证时首载聚焦仍在，TextField 一开始就有内容/聚焦路径不同）。
- **修复**：B′ 内改读 `val currentTextFieldValue by rememberUpdatedState(textFieldValue)`（Compose 标准做法），pointerInput 块始终拿到最新 text/selection 再落点。真机确认冷启动直接长按不再跳顶。

---

## 1. 完整诊断历程（时间线，含被推翻的结论）

> 按时间倒序/正序均可读。每条标注最终是否成立。

### 1.1 早期尝试（已被推翻 / 被取代）

| 时间 | 方案/结论 | 最终判定 |
|---|---|---|
| 09-05 00:0x | 埋点诊断确认：长按后 scroll 经 ~200ms 动画归零，`lastGoodScroll` 被同步污染 → graphicsLayer 补偿形同虚设 | ✅ 现象描述成立，方向不对 |
| 00:3x | 用 `isScrollInProgress` 守卫冻结 lastGoodScroll | ❌ v1 被击穿（内部滚动路径 isScrollInProgress 恒 false） |
| 00:34 | 事件驱动锁定 lastGoodScroll | ❌ v2 仍被污染 |
| 00:43 | `bringIntoViewResponder` 从源头拦截 | ⚠️ 部分有效（拦首次），但跳顶滚动**不走 responder 链**，拦不住 |
| 00:46 | 主动拉回兜底（scrollTo 打断动画） | ⚠️ 有效但属事后拉回 |
| 01:03 | 假设乙：删 graphicsLayer 平移后"不跳" → 误判 graphicsLayer 是元凶 | ❌ 结论下早（01:28 删主动拉回后复现，证明 graphicsLayer 非充分条件） |
| 01:15 | 方案一清理：只留 responder + 删 graphicsLayer/主动拉回 | ❌ 删主动拉回后跳顶复现 |
| 01:28 | 恢复主动拉回 → 01:42 增强（双判据）→ 01:52 时间窗放宽到 400ms | ⚠️ 有效但"闪一下再拉回"，非根治 |

**转折点**：所有"事后拉回"方案都无法消除那一瞬闪烁，因为 scroll 先被滚走、再被拉回。用户据此要求找根源。

### 1.2 规律探索（用户真机实证，逐条收敛）

用户通过观察逐步厘清规律，推翻了多个早期假设：

1. **非"分屏/目录功能残留"**：相关功能早已删除，与滚动层解耦；
2. **非键盘问题**：曾观察到"键盘升起时不跳"，但后来证明是误导；
3. **非"切换预览/编辑次数"**：切预览只是为了"消除光标"；
4. **非布局未就绪 / 时间因素**：等多久都跳；
5. **md 与 txt 一样**：排除异步高亮因素。

**最终正确规律（2026-09-05 02:22）**：

> **在无可见光标的状态下，第一次长按框选必跳顶一次；之后无论如何框选都不再跳。**

### 1.4 "为何只跳一次"的最终解释（2026-09-05 03:0x COMBO probe 判别）

- **代码核实**：`textFieldValue.selection` 的写点仅 4 处——内容变更保留光标 / **仅冷启动首载强制 `TextRange(0)`** / 用户编辑 / B′ 落点。**切换 EDIT↔PREVIEW 根本不改 selection**（L457 原样传 EditContentView，preview 渲染不碰它）。
- **COMBO probe 铁证**（每次切回编辑组合打印 sel，两进程复现）：
  ```
  COMBO sel=0       ← 冷启动：selection 被钉在 offset 0（唯一源头）
  用户点/长按落点 → sel=2814 / 1115
  COMBO sel=2814    ← 切回编辑 sel=2814；连切 6 次预览↔编辑 sel 恒 =2814，绝不回 0
  ```
- **修正后的自洽结论**：
  1. 真正制造 offset 0 幽灵的**只有文件冷启动首载那一次**（首载自动聚焦块把光标放文首）。
  2. "切预览"让**视觉光标消失**（preview 无编辑光标），但**逻辑 selection 从不清零**——它停在切换前真实位置（视口附近），故切回后再长按 reveal 就近，不跳。
  3. 因此"跳顶"只发生在 **"冷启动 sel=0 + 用户滚动离开文首却从未点/长按落点"后的首次长按**。用户以为"切预览重置光标"实为**视觉错觉**（无可见光标 ≠ 逻辑回 0）。

> **勘误**：早期 §0/§1.2 曾写"消除光标（切预览把 selection 复位到视口外）→ 再次制造幽灵 → 再跳一次"，与代码和本次 probe **不符**，已修正。

### 1.3 根因确诊（sel=0 铁证）

- **用户验证**：滚动到中段后，**先点一下让光标落中段，再长按 → 不跳** → 坐实"光标位置"是变量；
- **探针日志（02:37）**：滚动定位 `v=8895 sel=0 collapsed=true` → 长按跳顶 `v=144 high=8906 sel=0 collapsed=true` → PULLBACK 拉回。
  → **长按跳顶全程折叠光标逻辑 offset 恒为 0**，`collapsed` 恒 `true`（此场景长按连选区都没建成，只触发了 reveal 滚动）。
- **结论**：TextField 长按 reveal 用的就是折叠在 `offset 0` 的幽灵光标 → 滚回顶部。

**对应代码根因位置**（冷启动把光标放 offset 0）：

```kotlin
// EditorScreen.kt ~L184-187（文件首次加载完成时）
textFieldValue = TextFieldValue(
    text = newContent,
    selection = TextRange(0)   // ← 折叠光标被强制放在文本开头 offset 0
)
```

---

## 2. 机制图

```
文件首载自动聚焦:  selection 默认停在 offset 0（聚焦文本天然光标在开头）
                ↓
用户滚动到【文档中段】阅读（v≈8900）
                ↓
折叠光标仍在 offset 0（视口外上方）= “幽灵光标”（看不见；用户从未点/长按落点）
                ↓
用户长按中段某词  → BasicTextField 内部要 reveal 选区
                ↓
它记录的光标还在 offset 0 → reveal 目标 = 文本开头
                ↓
scrollState 被驱动滚回顶部(v→0) = “跳顶”
                ↓
（长按触发瞬间 B′ 已把逻辑光标先落到 down 字符 → reveal 目标就近 → 不再跳顶）
```

**为什么"光标在视口内时长按不跳"**：reveal 目标就在视口内，无需大幅滚动。

---

## 2.5 业界检索 / 外部互证（2026-09-05 联网查证）

> 目的：与自身诊断互证，并补充可借鉴的根治方向。检索渠道：Android 官方 API、Compose Foundation 源码级说明（DeepWiki）、StackOverflow、Rich Editor 开源库。

### 已证实的业界技术事实（与我们诊断一致）
1. **老 API BasicTextField 不自带垂直滚动**，光标/选区 reveal 靠外部 bringIntoView 语义链或手动 scroll。
   - 与我们"外层 `verticalScroll` + 内层 BasicTextField"的自造架构互为因果：官方本就是让 TextField 靠 `scrollState` 和外部容器。
   - 印证：我们"靠外滚、手动跟随"的架构方向没错，问题只出在"幽灵光标逻辑 offset 0"这一个环节。

2. **BringIntoView 链的实际执行者是 ScrollableNode**（源码级，Compose Foundation）：
   > 请求从 child 沿 `BringIntoViewModifierNode` 链向上冒泡；`ScrollableNode` 里的 `ContentInViewNode` 真正执行滚动；若子 responder 不能满足，请求继续上浮到 parent responder。
   - **直接推论**：把 responder 挂在 `verticalScroll` **内部**的 Box 上做 no-op，请求即便被"吞"，只要 scrollState 还被驱动，滚动照样发生 → 印证文档 §3 "纯 responder no-op 拦不住不走 responder 链的动画"。
   - 真正该在意的，是**那一跳到底走不走 responder 链**（见 §3 C′ 判别实验），而不是 responder 是否能"挡"——它大概率挡不住。

3. **旧 API 的光标滚动/reveal 缺陷，官方集中在新版 state-based TextField 修复**（SO 明确引官方 bug report：光标滚动问题 "fixed by google in BasicTextField2"，即后来的新 `TextFieldState` overload / 新 BasicTextField）。
   - 这是**架构级根治方向**的强信号：升级到新版 `state=rememberTextFieldState()` 的 BasicTextField，它自带正确 reveal/光标跟随，能整锅端掉这套"双滚动 + responder 拦截 + 主动拉回 + 手动跟随"的自造机制。

### 业界对"幽灵光标"的通用处置（与方案 A′/B′ 同源）
- 官方与社区普遍认知：**不要让逻辑光标长期停留在一个与用户视觉位置无关的 offset**。既然支持只读浏览（无编辑意图），处置无非两种：
  - **浏览态无编辑意图 → 干脆不聚焦 / 逻辑 selection 不可见化**（消除"看不见却参与 reveal"的状态）；
  - **要么滚动时把逻辑光标跟随进视口**（= 我们方案 A′ 的思想）。
- 两者都直指同一个根：**逻辑 selection 与视口解耦是病根**，与本项目 sel=0 铁证完全互证。

### 可借鉴的成熟库（如需彻底绕开自造机制可评估）
- `MohamedRejeb/Compose-Rich-Editor`（富文本 markdown 编辑器，用自持 `RichTextState` 包装 text input）。
- `colintheshots/MarkdownTwain`（基于 Markwon 的 markdown 编辑器/查看器）。
- 均可作为"把滚动+光标管进库内、避免手搓"的参考实现。

### GitHub / 官方已知 bug 佐证（2026-09-05 二次检索确证）
> **我们遇到的正是 Compose TextField（老 value API）+ 外部 verticalScroll 的官方已知 bug，不是本项目特有缺陷。**

- **JetBrains compose-multiplatform issue [#4014](https://github.com/JetBrains/compose-multiplatform/issues/4014) "TextField always scroll to top on first focus"**
  - 复现代码与我们**几乎逐字相同**：`TextField(value, onValueChange) + Modifier.fillMaxSize().verticalScroll(scrollState)`，**滚动到中/底部不点击 → 点某行 → 突然滚回顶**。
  - JetBrains 官方回复：**"This is a known bug in `TextField` itself"**，指向 Google bug tracker **#235693496** → 官方承认是 TextField 自身 bug，跟踪于 Google tracker（该 issue 在 partner/内部 tracker，外部需登录不可见）。
  - 与我们差异仅在触发：issue 是"首次点按/聚焦"，我们是"首次长按选词"——**本质同源**：光标逻辑在视口外 offset（此处 scroll 已滚走、逻辑光标仍在初值），TextFrame 内部 reveal 按该 offset 滚回，导致视口跳到与用户位置无关的地方。
- **新版 BasicTextField2（即新版 `TextFieldState`-based，方案 D 目标）对同类问题做了系统性根治**：
  - androidx commit `3cae670` "**Ensures BTF2 cursor is scrolled back into view when typing**" —— 把"光标滚回视野"逻辑内置进 BTF2；
  - BTF2 支持**外接 `scrollState`**（像普通 scrollable 一样 hoist，供滚动条/程序控制），光标 reveal 与外部滚动由组件统一协调 → 从根上避免"外部 verticalScroll 与内部 reveal 打架"。
- **社区 workaround 全部收敛到与方案 A′/B′ 同源**：要么 onTextLayout 手动把光标 scroll 进视口（= A′），要么避免"视口外幽灵光标"参与 reveal（= B′ 或浏览态不聚焦）。

> **结论（更新）**：外部资料不改变"幽灵光标 offset 0"根因判断，反而进一步**确证它是 Compose 老 API TextField 的官方已知 bug**（#235693496 / CMP #4014），绝非本项目逻辑错误。这解释了为何"怎么修都闪烁/拦不住"——框架层缺陷，无干净的外部 workaround；唯一"根治路线"仍是 **A′/B′ 让幽灵光标不成立**，或长期走 **D 升级新版 BTF2**（官方已在框架层修好同类问题）。

---

## 3. 疑点判别实验（C′）—— 已完成，responder no-op 证伪为帮凶

> **结论（2026-09-05 真机验证）：`bringIntoViewResponder`（no-op 拦截）不是跳顶帮凶，已排除。**
>
> 曾设想的假说：responder 把 TextField "让长按点可见" 的正常 bringIntoView 全吞掉（no-op），TextField 得不到"已滚到长按点"的正确反馈，内部状态错乱后 **fallback 滚到 offset 0**（文档开头）。→ 若成立，移除/改造 responder 即可根治。

**判别实验（临时移除 responder，保留下方 TRACK 埋点观察）**：

- 对照组（移除前）：长按跳顶 → 骤降 → 主动拉回（PULLBACK 日志命中）。
- 实验组（本次，responder 已移除）真机日志：

```
TRACK v=6890 high=6890 sel=0 collapsed=true   ← 滚中段停住（无光标，逻辑 offset 0）
TRACK v=3647 high=6897 ...                     ← 长按瞬间 scroll 骤降
TRACK v=254  high=6897 slam=true               ← ~0.15s 内 6890→254，骤降到顶
PULLBACK! v=254 high=6897                      ← 主动拉回命中，scrollTo(6897)
TRACK v=6897 ... sel=1699                      ← 拉回后 sel=1699（长按点），collapsed=true
```

**判定**：移除 responder 后，长按跳顶**依旧发生**（6890 骤降到 254 并触发 slam/PULLBACK）→
1. **那一跳根本不走 responder 链**，是 BasicTextField 内部直接基于旧 `selection(offset 0)` 驱动 `scrollState` 的 reveal，responder(no-op) 拦不住，也**不是**因吞掉请求才 fallback 的元凶 → **responder no-op 证伪为帮凶**；
2. 长按瞬间 `sel=0`（幽灵光标仍在），跳完后才落到 `sel=1699`（长按点）→ 坐实"**先按 offset 0 reveal 到顶，随后才把 selection 更新到长按点**"，根因就是幽灵光标 offset 0；
3. **方案 C（改造 responder 恢复默认 reveal）无效，淘汰。** 根治仍指向 A′/B′（处理幽灵光标 offset 0，让 reveal 目标就近）。

> 次要观察：本实验还暴露主动拉回判据的一处边缘漏判——用户快速甩到底（fling）且 high 在骤降前恰好被刷新时，`high-v` 落差不足可能 `slam=false`。这是主动拉回自身的健壮性瑕疵，与本根治无关，定案后一并清理。

---

## 4. 改动方案（候选）

> 目标：消除"折叠光标停在视口外远处(offset 0) + 视口在别处"的幽灵态，让长按 reveal 目标就近，从根源不跳顶、且不再闪烁。
> 注意：**视觉隐藏光标无效**——TextField 逻辑里的 `selection` 才是关键，必须处理逻辑位置，而非显示。

### 方案 A′（滚动跟随 / 消除幽灵光标）—— 治本
- **做法**：滚动浏览时，若折叠光标离开视口，把其**逻辑 offset 同步到当前视口内可见位置**（如跟随到视口顶部可见字符），使逻辑光标永远不留在 offset 0 这种远点。
- **副作用**：滚动中改 selection 可能干扰 IME/输入态；需仅在"无输入意图"时跟随。复杂度中等。

### 方案 B′（长按锚定长按点）—— ✅ 已采用并验证（2026-09-05 定案）
- **做法**：长按选词那一刻，先把逻辑光标设到长按的字符 offset，再让选区基于它建立；使 reveal 目标是长按点（视口内）而非 offset 0。
- **副作用**：需要拦截/协调长按手势时序，改动中等，可能有新时序复杂度。本质是"先点一下"的自动化。
- **落地实现**（EditContentView 的 BasicTextField modifier 上的 `.pointerInput(Unit)`）：
  - `awaitEachGesture` + `awaitFirstDown(requireUnconsumed=false)` —— **只观察不消费**，不破坏 TextField 自身的 tap/双击/长按/拖选手势；
  - 当 down 落在文本区且当前为**折叠光标**时，用 `layout.getOffsetForPosition(down.position)` 把逻辑光标先落到 down 字符 offset（`coerceIn(0, len)`），再经 `editTextFieldValueChange` 更新 selection；仅折叠态干预，避免打断框选拖动。
  - **⚠️ 关键坑（务必保留）**：block 内必须读 `val currentTextFieldValue by rememberUpdatedState(textFieldValue)`，**绝不能直接读 `textFieldValue` 参数**——pointerInput(Unit) 闭包不随重组更新，直接读会拿到首次组合的空文本而永远不落点（见 §0 真正根因补记）。
  - 新增 import：`awaitEachGesture` / `awaitFirstDown` / `waitForUpOrCancellation`（`rememberUpdatedState` 属 `androidx.compose.runtime.*`）。
- **真机验证（最终）**：修复后冷启动打开长文件→不点文本→直接滚中段长按→**不跳顶**；反复长按/滚动换位稳定不跳；切预览↔编辑再长按亦不跳（均通过视觉回归）。此前"保留主动拉回作脚手架"的 slam/PULLBACK=0 是次要佐证，真正的判定是修复后无任何脚手架仍不跳。

### 方案 C（改造 responder 恢复默认 reveal）—— ❌ 已淘汰（2026-09-05 C′ 判别证伪）
- 假设 responder no-op 吞掉正常 reveal 致 fallback offset 0。
- **判别实验结果**：移除 responder 后长按仍跳顶 → responder 非帮凶、那一跳不走 responder 链 → 恢复默认 reveal 无济于事。
- **不再考虑。** 响应者拦截既拦不住跳顶（§2.5 已述 responder 链执行者是 ScrollableNode、且此跳直接驱动 scrollState），也在本项目证伪为无关，应清理而非改造。

### 方案 D（升级新版 TextFieldState-based BasicTextField）—— 架构级根治，代价最高（2026-09-05 联网补充）
- **依据**：官方已将旧 API 的光标滚动/reveal 缺陷集中在新版 `state = rememberTextFieldState()` 的 BasicTextField（自带的垂直滚动 + 正确 reveal + 光标跟随）中修复。
- **做法**：弃用旧 `value/onValueChange` overload，改用新版 `TextFieldState` 承载文本 + selection；由组件自带滚动接管"光标滚入视野"，从而**整锅删除**自造机制：外层双滚动 → responder 拦截 → 主动拉回 → onTextLayout 手动光标跟随。
- **代价 / 前置**：
  - 需 Compose Foundation ≥ 1.7（新版 TextFieldState overload 引入），须确认项目依赖版本与 Android 最低版本兼容；
  - 当前代码强依赖"外层统一 `editScrollState` 供搜索跳转 / 目录定位 / 跨模式保留"，迁移需重写这段（新版字段内部持 ScrollState，需通过其 scrollState 参数外接）；
  - 语法高亮 VisualTransformation、搜索高亮、AutoTextFormatter 需在新 API 下重新适配（inputTransformation/outputTransformation 语义变化）。
- **建议排序**：作为 A′/B′ 之外的**长期重构方向**，而非本轮应急首选；但若本轮 A′/B′/C 实验皆被证伪或改动扩散过大，应认真评估 D。


### 已排除（勿再走回头路）
- ❌ graphicsLayer 事后找平（lastGoodScroll/compensationOffset）—— lastGoodScroll 会被内部滚动污染成 0，v1/v2 已证伪；
- ❌ 纯 responder no-op 拦截 —— 拦不住不走 responder 链的跳顶动画；
- ❌ responder 改造/恢复默认 reveal（原方案 C）—— C′ 判别证伪：移除 responder 后仍跳顶，那一跳不走 responder 链；
- ❌ 主动拉回 scrollTo 事后拉回 —— 只能"闪一下再拉回"，非根治；
- ❌ 视觉隐藏光标 —— 不解决逻辑 offset 0。

---

## 5. 当前代码状态（2026-09-05 定案后，临时脚手架已全量清理）

### EditContentView 最终结构（只保留 B′ 根治 + 正常功能）
```
Box(fillMaxSize + background + padding)
  └─ Box(fillMaxSize).verticalScroll(scrollState)      ← 外层滚动
       └─ BasicTextField(旧API, value/onValueChange)
            ├─ modifier.focusRequester(focusRequester)  ← 仅挂载能力，不再自动 requestFocus
            └─ modifier.pointerInput(Unit)              ← 【B′ 根治】down 落点锚定
                 └─ 读 rememberUpdatedState(textFieldValue) 的最新 text/selection 再落点
```
滚动定位输入：`scrollState = editScrollState`（EditorScreen 级 remember，跨模式保留）。

### 已清理项（全部删除，勿再加回）
0. ✅ **XH6 探针（最新一轮，2026-09-05 03:48）**：全部 `Log`（`[LIFE]`/`[OVC]`/`[B'] DOWN-*`/`[JUMP]`/`[FOLLOW]`）+ `import android.util.Log` + 探针辅助变量(`probeTag`/`lastScroll`/`cursorFollowScrolling`)+ JUMP 骤降 snapshotFlow。根因定位后已彻底删除，正式版无任何日志残留。
1. ✅ 主动拉回 LaunchedEffect 整块（骤降检测 + `scrollTo` 拉回 + `lastDbg`/`lastSelDbg` + `TRACK`/`PULLBACK` 埋点）—— 治标且闪烁，非根治。
2. ✅ responder + `bringIntoViewResponder` 挂载 Box + `bringIntoViewBlocker` 定义 + 相关 import —— C′ 判别证伪非帮凶、拦不住直接驱动 scrollState 的滚动。
3. ✅ `COMBO` probe（切回编辑打印 selection 的判别埋点）。
4. ✅ `hasInitialFocus` 变量、首载自动聚焦块（把光标强制放 `TextRange(0)` + `requestFocus`）—— 该块是"文首 offset 0 幽灵"的直接源头；删除后打开文件呈浏览态（不自动聚焦），点击文本才进入编辑（TextField 点击自动聚焦落光标）。
5. ✅ graphicsLayer 补偿 / lastGoodScroll / compensationOffset（更早轮次）。

### 保留项（属正常功能，勿误删）
- B′ pointerInput（根治逻辑）+ **`currentTextFieldValue by rememberUpdatedState(textFieldValue)`**（关键修复，见 §4 方案 B′）。
- 光标跟随 LaunchedEffect（含 `skipCursorFollow` 首次跳过，用于 EDIT↔PREVIEW 切换时保留滚动位置）—— 正常功能，非测试代码。
- `editFocusRequester` 声明与传给 EditContentView 的挂载链路 —— 保留作为"编辑器可聚焦能力"载体，将来如需手动聚焦入口可直接 `requestFocus()`（当前无调用者，不自动聚焦）。
- `app/src/test/` 下的单测（FileSorterTest 等）为项目正式测试，非跳顶排查的过渡测试。

### 相关文档勘误
- AGENTS.md §4.2 曾有 "需 consumeBringIntoView()" 的错误记录（Compose Foundation 1.6.7 无此 API），**已更正**为本轮最终结论（responder 已删、B′ + rememberUpdatedState 为根治）。

---

## 6. 复现操作（供回归验证用）

> 注：当前版本已删除所有埋点（无 `EditScrollDbg` 日志）与首载自动聚焦，回归只能靠**视觉**。

1. 冷启动打开长文件 → 打开为浏览态（无光标、不弹键盘）；
2. 点击文本进入编辑（光标落到点击处）或直接滚动到文档中段；
3. **不先落点、直接长按选词** → 预期（修复后）**不跳顶**、能正常选中、无闪烁。

**修复成功判据**：滚动到中段后首次长按**不再跳顶**、无闪烁，且能正常选中文字；切预览↔编辑后再长按同样不跳。
