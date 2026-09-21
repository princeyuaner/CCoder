# 转录区的字体与字号

日期：2026-09-21　分支：`v0.2.24-dev`　`pluginVersion=0.2.24`
状态：**已实现**

---

## 1. 用户要什么

设置 →「通用」→「界面」卡再加两行：

- **字体**：固定几档 + 「跟随 IDE」（默认），不做自由输入 —— 与配色那条"只暴露选项不暴露色值"同一个口径。
  档位按**本机装了哪些**过滤：跟随 IDE / 系统默认 / 微软雅黑 / 宋体 / 楷体 / Segoe UI / Georgia /
  Consolas（等宽）/ JetBrains Mono（等宽）。上次选的那一档**永远留在列表里**（装过又卸了也得显示得出来）。
- **字号**：四档下拉 —— 小 0.9 / 标准 1.0 / 大 1.15 / 特大 1.3。

**范围只管转录区**（JCEF 那个页面）。输入框、四张状态卡、页签跟随 IDE 主题不动 —— 于是 Swing 那半
~40 处 `UIUtil.getLabelFont()` / `JBUI.Fonts.smallFont()` 一处都没碰。

## 2. 顺带修好的一条注入（这是字体能被看见的前提）

`ThemeInjector` 从 2026-09-11 起发的是 `--font-ui: "家族名", 13px;`（给 `font:` 简写准备的），
而 `styles.css` 按 `font-family:` 消费 —— 整条声明**非法、被浏览器丢掉**。2026-09-21 在真实
Chromium（本机 Edge，headless）里量到：

| 情形 | `getComputedStyle(body).fontFamily` |
|---|---|
| 旧写法（`"JetBrains Sans", 13px`） | **`"Noto Sans SC"`** —— 浏览器兜底，注入白写 |
| 新写法（`"JetBrains Sans", sans-serif`） | `"JetBrains Sans", sans-serif` —— 被接受 |

所以今天转录区用的是**浏览器默认字体**，不是 IDE 的、也不是谁选过的。
修法：家族名只进 `--font-ui`/`--font-mono`，字号另走 `--fs-scale`（下节）。
同一次核对里也量到 `--fs-scale: 1.15` → body 计算字号 13 × 1.15 = **14.95px**，
同一段文字宽 108.98 → 141.67px（1.3× 档）—— 缩放机制成立。

**观感变化要说清**：修好之后「跟随 IDE」才第一次真的跟随。而 IDE 自带字体
（JetBrains Sans / JetBrains Mono）**通常没装进系统**，浏览器解析不到 —— 于是栈里
自带退路（`"JetBrains Sans", "Segoe UI", sans-serif`、编辑器字体 → `"Consolas", monospace`），
多数机器上实际落到 Segoe UI / Consolas。想退回"浏览器默认"的人选「系统默认」。

## 3. 两个通道的边界（别把字号挪到 prefs 去）

| 通道 | 装什么 | 为什么 |
|---|---|---|
| **主题 CSS**（`ccoderSetTheme`，`ThemeInjector`） | 颜色、**字体家族、`--fs-scale`** | 都是 CSS 变量形状的东西；页面不需要为它们重渲染 |
| **偏好快照**（`window.ccoderPrefs`，`PrefsInjector`） | 思考折叠（今天） | React 需要**感知**它才知道怎么渲染 |

`styles.css` 的文件头从第一天起就写着"颜色与字体都走 CSS 变量、由 Kotlin 注入" ——
字号是同一个形状，所以放同一份注入里。反过来做（字号走 prefs、由 JS `setProperty` 写变量）
会让 `--fs-scale` 有**两个写者**（内联样式压过注入的那份 `<style>`），谁赢靠层叠细节、且是静默的。

> 2026-09-21 更正：`docs/superpowers/specs/2026-09-21-thinking-fold-design.md` 与
> `PrefsInjector.kt` 里"字号、密度要往 prefs 快照里加"那句话，只对**密度**成立。
> 字号走主题通道（本节），密度要 React 感知（它影响重排）。

三条推送合成一个入口 `ClaudeTranscriptView.pushUiState()`（主题 → 语言 → 偏好），
调用点两处：`ready` 握手与设置对话框关掉之后 —— 分开写迟早漏一条，而漏了是静默的。

## 4. 字号：24 处 px 乘一个倍率

每处写死的 px 字号改成 `calc(Npx * var(--fs-scale, 1))`（`styles.css` 里 24 处）。
两个 `em` 值（标题 `1.15em`、行内码 `0.92em`）不动：它们跟着父级缩放，本来就是自由乘客。

- **兜底是 1**：离屏探针读的是磁盘上那份 `styles.css`、不注入任何变量 ——
  量到的仍是设计值那套几何。所以**字号这一档没改一行探针断言**（`layout-probe` 全绿就是"零改动"的证明）。
- **间距、圆角、固定尺寸不乘**（150×104 缩略图、14px 转圈、15px 徽标）：那是「密度」那件事，分开做。
  也正因为这样 `thumbW === 150` 那条断言照旧成立。
- `web/src/styles.test.ts` 是这道不变量的守门人：读 `styles.css`，凡 px 字号必须乘倍率，
  除非那一行显式写着 `/* fs-scale: off */` 并说明理由（留这个口子是防"第一个人遇到例外就把整条扫描删掉"）。
- **代码块永远等宽**：正文档只换 `--font-ui`；`--font-mono` 始终跟 IDE 的编辑器字体 ——
  那是语义不是偏好。

## 4.5 顺带查出来的一件事：表格字号一直不跟正文（2026-09-21 修）

写这一版时用真实 Chromium 量表格，发现**单元格一直是 16px 而正文 13px**：
Chromium 的 UA 样式表给 `table`/`td` 钉了 `font-size: medium`，我们的样式里
从来没给单元格写过字号 —— 于是它既不跟正文，也不会跟 `--fs-scale`
（只给 `td` 写 `inherit` 没用：它继承的是那个被钉住的 `table`）。

修法是在 `.bubble__text table` 上写 `font-size: inherit`（显式声明盖过 UA），
单元格那条 `inherit` 只是把链条补完整。顺带的好处：表格文字小了一号之后，
首列序号在 420px 宽度下更不容易被挤断行（那正是 2026-09-18 那张"难看的表"的老毛病）。

这条**加进了布局探针**（第四处"继承坑"）：`cellFont` 必须等于 `bubbleFont`
（真实浏览器里比计算样式，同它已有那条 `fileStyle`/`titleStyle` 的做法）。

## 5. 已知边界

- **首帧用兜底**：注入发生在 `ready` 握手（React 首帧之后），所以第一帧是 `sans-serif` + 倍率 1，
  随后才换成用户的档位 —— 与颜色那条同一条边界（颜色首帧也是兜底色）。转写内容不会受影响：
  它在 `ready` 之前一直压在 `beforeReady` 里。
- **字体名做了一层 CSS 消毒**（去掉 `"` `}` `;` `\` 与换行）：`escapeForJsString` 管 JS 那层，
  管不到 CSS，而这条路现在握着平台给的字体名。
- **列表按本机字体过滤**（`GraphicsEnvironment`），但 Java 认得的字体 Chromium 不一定能渲染
  （IDE 自带字体就是这种）—— 所以每一档的栈都自带系统退路 + 通用族。
  设置页的说明里不承诺"装了才列出来"。
- **1.3× 下的固定尺寸盒子**（如 15px 的 `.tool__badge`）不在扫描范围内：字号放大了、盒子没放 ——
  离屏出图看过，必要时给那一行加 `/* fs-scale: off */`。

## 6. 用例

- `UiFontTest`：`fromName` 兜底、列表永远含「跟随 IDE」/「系统默认」、stored 一定在、每条栈以通用族收尾、
  解析（正文档换家族名 / 等宽栈始终跟编辑器字体）、CSS 消毒、四档显示名人话。
- `UiPreferencesTest`：三个字段的默认值、`loadState` 逐字段复制、非法名读出回默认但**不改写**盘上那份。
- `ThemeInjectorTest`：家族名-only（含"不许再出现 px"）、`--fs-scale` 按档位、默认 = 跟随 IDE + 标准。
- `SettingsDialogTest`：改字体/字号立刻落库（存枚举名）、打开设置照服务里的值显示、非法名照常打开且不被改写。
- web：`styles.test.ts` 三条（扫描、非空转、em 两处没被误改）。
- 离屏：`SettingsDialogProbe` 多一张"Georgia + 特大"的通用页 PNG。
