# 思考折叠：设置里那一个开关

日期：2026-09-21　分支：`v0.2.24-dev`　`pluginVersion=0.2.24`
状态：**已实现**

---

## 1. 用户要什么

设置 →「通用」→「界面」卡（原来只有一行「界面语言」）再加一行**开关：思考折叠**，
默认**不折叠**。

两条已定的口径：

- **折叠范围 = 两个阶段都收**：已完成的思考块、正在流式的那块，勾上之后都默认收起。
  进行中那块的标题里转圈 + 秒表照旧 —— "它在想"仍然看得见，那正是 2026-09-14 留下那两样的原因。
- **默认值不动**：2026-09-14 用户明确要过"两个阶段都展开"。这条偏好是**可选项**，
  不勾就是原样 —— 升级不偷袭任何人的观感。

这是 2026-09-18 那份「界面」页 A 切片设计（`~/.claude/plans/abstract-tickling-tome.md`）的
**最小子集**：只做这一项，且是**两态开关**（那份设计里的三态下拉「结束后收起」没做）。
底座（APP 级偏好服务 + 注入通道 + web 侧快照）按那份的形状落地 —— 字号、密度、配色
（B/C 切片）要复用的是同一条路。

## 2. 值存在哪

`settings/UiPreferences.kt`（新），**APP 级**：`@State("CCoderUiPreferences")` +
`@Service(Service.Level.APP)`，落 `ccoderUiPreferences.xml`，字段 `collapseThinking: Boolean = false`。

- 为什么 APP 级：个人外观偏好、跨项目复用才对，`.idea/` 整个被 gitignore（同 `UiLanguageSettings`
  / `PromptPresets` 的成文理由）。
- 为什么**不进 plugin.xml**：`UiLanguageSettings` 进 XML 只因为它必须"界面出现之前"就被读起来
  （右键菜单、状态栏都早于工具窗口）；这份偏好直到转写区建起来才有人读，懒实例化就够。
  注解注册还避开了 2026-09-20 那次"服务写在顶层被静默丢掉"的坑。
- `loadState` 逐字段复制（照 `ClaudeSettings` 的教训：忘了写就**静默**丢，症状是"重启后又变回去了"）。

## 3. 值怎么到页面上

三段，形状与主题/语言那两条**逐字一致**：

| 段 | 在哪 | 干什么 |
|---|---|---|
| 桥脚本 | `ClaudeTranscriptView.injectBridge` | ① 注入时**种**一份快照（`window.ccoderPrefs`，React 挂载之前就能读到）② 定义 `window.ccoderSetPrefs(prefs)`：写值 + 叫 `ccoderPrefsSink` |
| 推入口 | `PrefsInjector.buildInjectScript(...)` | 单行、`&&` 守卫：`window.ccoderSetPrefs && window.ccoderSetPrefs(encode(…))` |
| 接收端 | `web/src/App.tsx` → `prefs.ts` | 挂 `window.ccoderPrefsSink` + **读一次注入值**（桥可能比 React 先到），此后只认推送 |

**载荷是对象**（`{ collapseThinking: … }`）而不是裸布尔：后面还要往同一份快照里加键。

> 2026-09-21 补：字号与字体**没有**走这条通道，它们走的是主题那条 CSS 通道
> （见 `docs/superpowers/specs/2026-09-21-font-choice-design.md` "两个通道的边界"）。
> 这里当初写的"字号、密度要往这份快照里加"只对**密度**成立 —— 密度要 React 感知
> （它影响重排），字号不用。
字面量只有 `PrefsInjector.encode` 一个出处（桥里那份种下的与推送这份走同一个函数）——
今天只有布尔值，手拼也不会错；哪天某个值变成用户可控的文本（字体名、自定义 CSS），
那条路要换成 `escapeForJsString` + JSON 字符串。

**推送点两处**：① `ready` 握手（排在补推转写**之前**）、② 设置对话框关掉之后
（`ClaudePanel.openModelSettings`）。

`setPreferences()` 走 `UiPreferences.getInstanceOrNull()`：它跑在 ready 分支那一侧（CEF 回调
线程），抛出去会把"补推滞留的转写"整段吞掉，症状是界面全空、零报错。取不到就按默认档推。

**"不闪"的论证**（照语言那条）：`ready` 之前所有转写操作压在 `beforeReady` 里，React 首帧渲染的是
**空转写区**；三条 `executeJavaScript` 在同一渲染进程按序执行 —— 所以**第一个含思考块的渲染就拿着正确的偏好**，
不存在"先展开一帧再收起"。

**关框那一推**：偏好是纯界面的事，不经过会话对账（`applySavedSettingsToSession` 有"会话没就绪"的早退
分支，塞进去会被顺手吃掉）。框是模态的，"关掉那一刻对上"与模型/权限那几项同一条语义。

## 4. 「当场生效」是怎么兑现的

`Collapsible` 多了一条 `useEffect(() => setOpen(defaultOpen), [defaultOpen])`。

- `defaultOpen` 从此是"**初始值 + 偏好变化时的复位值**"：用户手动开合优先，直到偏好本身改变。
- 挂载时那次 `setOpen(同值)` 被 `Object.is` 挡掉 —— 不是冗余代码。
- 少了这条 effect，已经画在屏幕上的思考块一动不动，看起来像开关没生效（"界面立刻切换"那条承诺
  不该只对语言成立）。

## 5. 刻意不做的（边界，不是漏）

- **不碰 localStorage**：偏好的家是 Kotlin 那个服务，页面里只是内存快照。
  （`CodeBlock.tsx` 的 `ccoder.codeWrap` 是另一回事：那是"某一块自己的临时观感"。）
- **不重译已经说过的思考正文**：与语言那条同一个边界。
- **不做三态**（「结束后收起」）与**工具卡片默认展开**：那是 A 切片剩下的部分，等以后。
- 页面重载后由桥脚本重新种一份、`ready` 握手再补一次新鲜的（`window` 上的东西随文档一起没了，
  不额外缓存 —— 重建时读的是服务当时的值，可能比最后一次设置旧一次，但 `ready` 那一步
  排在任何转写内容之前，页面上看不到旧值）。

## 6. 用例

- Kotlin：`UiPreferencesTest`（默认值 / setter / loadState / 无参构造 / XML 名）、
  `PrefsInjectorTest`（单行 / `&&` 守卫 / 两种载荷 / 分号数）、
  `SettingsDialogTest`（勾上立刻落库用 `doClick()`；打开设置照服务里的值勾）。
  **诚实一句**：布尔写回是幂等的，"`loading` 闸缺了"在这个字段上观察不到 ——
  守它的是同页那条"带空格的值不被 trim"的探针（同一个 `save()`），没有假装能抓它的用例。
- web：`prefs.test.ts`（惰性 / 幂等 / 归一 / 退订 / 复位）、
  `ThinkingBlock.test.tsx`（勾上后两种块都收、进行中那块转圈还在、**渲染后改偏好当场跟着变**、
  手动开的那块只在偏好真变时复位）、
  `Transcript.test.tsx`（偏好穿到历史条目里 —— 守 `Item` 的 memo）、
  `App.test.tsx`（整条约定：注入快照定初始形态 → `ccoderSetPrefs` 换形态 → 卸载摘接收端）。
- 离屏：`SettingsDialogProbe` 的 `把通用页（勾上思考折叠）画成图片` —— 看新那行的间距、
  卡片有没有被撑出可视区。
