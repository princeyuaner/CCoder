# 贴图（截图直接发给 Claude）Implementation Plan

> 设计稿：`docs/design/image-attach.html`（方案甲：附件带 = 56px 缩略图悬在输入框上沿）
> 探针：`sidecar/tools/probe-image.mjs`（**已经跑过**，见下面 §0.1）

**要解决的问题**：往输入框里粘截图，今天什么都不发生（那是个 JTextArea，只认文字）。
干 UI 活的时候，"这张截图里 ＋ 和齿轮离太远"比写一段话快得多。

## 0. 先钉死的事实（全部实测/读源码得到，不是推断）

### 0.1 这条链路收图 —— 已实测 ✅

`node sidecar/tools/probe-image.mjs`：当场用 zlib 手搓一张 64×64 PNG（左半一种颜色），
问"左半边什么颜色"。插件当前用的模型 `deepseek-v4-flash[1m]`（走 tokenhub 网关）
**两次都答对**，而且第二次是把颜色换到另一边问的 —— 不是猜的。

结论：**不需要为贴图切模型**，走 `SDKUserMessage` 的 content blocks 就行。

### 0.2 content block 的形状

`sdk.d.ts:5464`：`SDKUserMessage.message` 就是 Messages API 的 `MessageParam`，
content 可以是字符串或数组（text / image / document / …）。image 这一块长这样：

```js
{ type: 'image', source: { type: 'base64', media_type: 'image/png', data: '<base64>' } }
```

今天 `sidecar/session.js:158` 传的是**纯字符串**（`content: text`），这是主要改动点。

### 0.3 CLI 自己的图片政策（读 `claude.exe` 内嵌 JS 得到）

```js
rk = { maxWidth: 2000, maxHeight: 2000, maxBase64Size: 5242880, targetRawSize: 3932160 }
dd = 1568, Ef = 1568, pxPerToken = 28, Aw = 85
```

- 支持 `image/png` `image/jpeg` `image/webp` `image/gif`
- 边长上限 2000、单张 base64 上限 **5MB**、超了报 `Image was too large`
- 它自己的目标长边是 **1568**；token 估算 `ceil(w/28) * ceil(h/28)`
  （一张 1568×880 的截图 ≈ **1800 token**，该省还是要省）

**推论：缩图这件事由我们做**，别把一张 3000px 的截图丢过去等 CLI 报错。

### 0.4 转写页读不到本地文件

`ClaudeTranscriptView.loadUi()` 用的是 `loadHTML(html)`（没有 base URL），
所以图**只能以 data URL 推过去**，不能"传个路径让它自己读"。

### 0.5 三个改动点（各自只有一处）

| 环节 | 位置 |
|---|---|
| 发请求 | `Protocol.kt:521` `encodeSend(id, text)` |
| 侧车入口 | `sidecar/index.js` `case 'send'`（含 `preStartQueue`） |
| 拼消息 | `sidecar/session.js:158` `session.send(text)` |

## Global Constraints

1. **三侧都要跑测试**：Kotlin（`./gradlew test -PskipWeb`）、侧车（`node --test`）、
   web（`npm test`）—— 这次是真的三侧都动，不像排队那次能豁免两侧。
2. **上限是我们定的，写在一处**：单条消息最多 **4 张**图；单张先缩到长边 ≤ **1568**；
   缩完还 > 3.75MB 就转 JPEG（质量 85）；base64 > 5MB 直接拒绝并提示。
   常量集中在 `AttachedImage.kt`，别散在组件里。
3. **纯逻辑与 Swing 分开**（本仓库惯例）：缩放/校验/命名都是纯函数，喂 `BufferedImage`，
   单测不碰剪贴板、不碰 AWT 机器人。
4. **注释写"为什么"**。每一处"为什么先缩再发""为什么 PNG 优先"都要落在代码上。
5. **提交粒度**：Task 1（纯函数）、Task 2（协议+侧车）、Task 3（输入框+附件带）、
   Task 4（发送/排队）、Task 5（转写区）各自可独立提交；Task 4 必须一次提交（编译不过的半成品不许进历史）。

## 文件结构

新增：

| 文件 | 内容 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/AttachedImage.kt` | `AttachedImage` + 缩放/校验/命名纯函数 |
| `src/main/kotlin/com/ccoder/ui/AttachmentStrip.kt` | 附件带组件（56px 缩略图 + ✕） |
| `src/test/kotlin/com/ccoder/ui/AttachedImageTest.kt` | 上面那些纯函数 |
| `src/test/kotlin/com/ccoder/ui/AttachmentStripTest.kt` | 组件与交互 |
| `src/test/kotlin/com/ccoder/ui/AttachmentStripRenderProbe.kt` | 出图给人眼 |
| `sidecar/tools/probe-image.mjs` | ✅ 已写（§0.1） |

改动：

| 文件 | 改什么 |
|---|---|
| `src/main/kotlin/com/ccoder/sidecar/Protocol.kt` | `encodeSend(id, text, images)` |
| `sidecar/index.js` | `case 'send'` 透传 images；`preStartQueue` 带图 |
| `sidecar/session.js` | `send(text, images)` → 拼 content blocks |
| `src/main/kotlin/com/ccoder/ui/Composer*.kt` | 接剪贴板图片 / 拖拽；附件带挂进输入卡 |
| `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt` | 发送与排队带上图；用户项推给转写区时带缩略图 |
| `src/main/kotlin/com/ccoder/ui/SendQueue.kt` | `QueuedInput` 带 `images` |
| `src/main/kotlin/com/ccoder/ui/QueueStrip.kt` | 条目尾部显示「图 N」 |
| `src/main/kotlin/com/ccoder/sidecar/TranscriptOpCodec.kt` / `MessageRenderer.kt` | `RenderItem.User` 带 images |
| `web/src/types.ts` | `UserItem.images?: { dataUrl: string }[]` |
| `web/src/components/UserBubble.tsx`（+ `Transcript.tsx`/`App.tsx`） | 气泡里画缩略图 |
| `web/src/components/ImageLightbox.tsx`（新增） | 放大浮层 |
| `web/src/styles.css` | 缩略图与浮层样式 |
| `web/tools/layout-probe.mjs` | 增一条：带图的气泡不横向溢出 |

---

### Task 1: `AttachedImage` —— 缩放、校验、命名（纯 Kotlin，可独立提交）

```kotlin
/** 一条消息里最多几张图。CLI 侧没有条数上限，这个数是**我们**定的：
 *  4 张已经能覆盖"这张 + 那张"的真实用法，再多是图片墙，token 也吃不消。 */
internal const val MAX_IMAGES = 4

/** 长边缩到这个数。CLI 的目标长边就是 1568（见计划 §0.3），
 *  再大不会更清楚，只会更贵：token = ceil(w/28)*ceil(h/28)。 */
internal const val MAX_EDGE = 1568

internal data class AttachedImage(
    val mediaType: String,     // image/png 或 image/jpeg
    val bytes: ByteArray,      // 已经是缩过、可以直接发的
    val thumb: BufferedImage,  // 56×42 上下，只给界面用
    val name: String,          // 「截图1.png」
)
```

要实现的纯函数：
- `fitToLimit(img: BufferedImage, maxEdge: Int = MAX_EDGE): BufferedImage` —— 等比缩，双线性；
  长边小于上限时**原样返回**（不要无谓重画，重画一次就掉一次锐度）
- `encodeForApi(img: BufferedImage): Pair<String, ByteArray>` —— PNG 优先；PNG 字节 > 3.75MB
  才转 JPEG（`ImageIO` 的 JPEG writer + 质量 85），返回 (mediaType, bytes)
- `thumbOf(img: BufferedImage, w: Int = 56, h: Int = 42): BufferedImage`
- `imageName(index: Int, mediaType: String): String`
- `acceptImage(bytes: ByteArray): String?` —— 校验入口：> 8MB 原图、认不出的格式给一行原因

**测试（`AttachedImageTest`）**：小图不被重画（同一对象引用）、长边超限按比例缩、
PNG 优先、超大转 JPEG 且 mediaType 跟着变、名字带序号、超限的原图给得出原因。

### Task 2: 协议 + 侧车（可独立提交）

- `Protocol.encodeSend(id, text, images: List<AttachedImage> = emptyList())`：
  `images` 非空时加 `"images": [{ "mediaType": ..., "data": "<base64>" }]`；
  **空就不加这个字段** —— 让今天的报文一个字节都不变（老侧车也还能跑）。
- `index.js` 的 `case 'send'`：`session.send(params.text ?? '', params.images ?? [])`；
  `preStartQueue.push({ text, images })` 跟着改（这里现在是 push 一个字符串）。
- `session.js` 的 `send(text, images = [])`：
  ```js
  const content = images.length
    ? [...images.map(i => ({ type: 'image', source: { type: 'base64', media_type: i.mediaType, data: i.data } })),
       ...(text ? [{ type: 'text', text }] : [])]
    : text;   // 没图就走老路：content 是字符串，与今天一字不差
  ```
  **纯图片消息（text 为空）要能发** —— 效果图上就是这么画的。

**测试**：侧车 `node --test`（`sidecar/test/index.test.js` 加用例：有图拼数组、无图还是字符串、
图在前文字在后、空文字只剩图）；Kotlin 侧 `ProtocolTest` 加"无图时 JSON 里没有 images 键"。

### Task 3: 输入框接线 + 附件带（可独立提交）

- **粘贴**：给 `ComposerInput` 装一个 `TransferHandler`，`importData` 里按顺序问
  `DataFlavor.imageFlavor` → `DataFlavor.javaFileListFlavor`（只收图片扩展名）；
  **文字优先**：剪贴板里同时有文字时交回默认行为（用户粘的是代码不是图）。
- **拖拽**：同一个 handler 的 `canImport`/`importData` 就能覆盖从项目树/资源管理器拖图。
- **附件带**：`AttachmentStrip`（`JPanel` + `BoxLayout X_AXIS`，`alignmentX = LEFT_ALIGNMENT` ——
  排队条那次的教训，见 `QueueStrip` 的注释），每项 = 56×42 缩略图（圆角 + 边框）+ 右上角 ✕ +
  底下文件名。挂进 `buildComposerCard`，位置在输入框**上沿**（方案甲）。
- ✕ 用 `LinkButton`（今天刚抽的：New UI 给所有 JButton 兜 72px，不钉死会浮在离边 30px 的地方）。

**测试**：`AttachmentStripTest`（加/删/上限 4 张时第 5 张给提示、纯图无字也能提交）；
渲染探针出图，人眼确认 56px 缩略图与「＋/⚙」那一排的粗细节奏一致。

### Task 4: 发送与排队带上图（**必须一次提交**）

- `SendQueue.QueuedInput` 加 `images: List<AttachedImage> = emptyList()`（默认值保证既有测试不改）。
- `QueueStrip`：一行文字后面挂一个「图 N」小标签（复用设计稿里那个 chip 样式）。
- `ClaudePanel`：`sendNow(text, typed, images)`；`sendCurrentInput()` 从附件带取图；
  发完后清空附件带；忙时连同图一起入队。
- 推给转写区的那条用户项带**中等图**（长边 ≤ 900 的 PNG data URL，几百 KB 级）——
  原图只发给 CLI，别让 4MB 的字符串过 JCEF 桥（计划 §0.4）。

**测试**：`SendQueueTest` 加"图跟着那条走"；`MainButtonState` 不受影响（确认一遍）。

### Task 5: 转写区（可独立提交）

- Kotlin：`RenderItem.User` 加 `images: List<String>`（data URL），op 编码跟着走。
- web：`UserItem.images`；`UserBubble` 里图在**文字上方**、`display: flex; gap: 5px; flex-wrap: wrap`，
  每张 150px 宽（设计稿的尺寸）；点击 → `ImageLightbox`（压暗浮层、Esc 关、1/2 翻页）。
- 布局探针加一条断言：带图气泡不横向溢出（`scrollWidth <= clientWidth + 1`）。

**测试**：`UserBubble.test.tsx`（有图/无图两条路）、`ImageLightbox.test.tsx`（Esc / 翻页 / 焦点）。

### 收尾：手工冒烟（不在自动化范围内）

1. `./gradlew --stop; PATH="/c/Program Files/nodejs:$PATH" ./gradlew buildPlugin`（**不许 `-x buildWebUi`**）
2. 装 `build/distributions/CCoder-0.2.9.zip` → 重启 PyCharm
3. 粘一张真截图 → 看附件带 → 发送 → 看气泡与放大 → 问它"这张图里有什么"
4. 忙的时候粘一张图回车 → 看它跟着排队条走
5. 一张 3000px 的巨图 → 确认被静默缩到 1568（不是报错）

## 风险

- **网关是实验性的**：`deepseek-v4-flash[1m]` 今天收图（§0.1 实测），但它名字里没有 vision。
  真发出去之后如果哪天开始报错，错误会原样出现在转写区（现有的错误路径），不会静默丢图。
- **data URL 过桥的体积**：中等图（≤900px PNG）实测几百 KB；JCEF 的 `executeJavaScript`
  能吞多长的字符串没量过 —— Task 5 第一条就是量它（超了就把中等图再降一档）。
- **`preStartQueue` 的形状变了**（字符串 → 对象）：`index.js` 里读它的地方要一起改，
  漏一处就是"ready 之前发的图被丢掉"。

## 自查记录（占位符扫描）

新增的六个文件（AttachedImage / AttachmentStrip / ImagePaste / UserBubble /
ImageLightbox / session.js 的改动）里没有 TODO / FIXME / TBD / XXX / 待补。

## 执行记录（2026-09-15）

五个提交，与 Global Constraints 第 5 条定的粒度一致：

| 提交 | 内容 |
|---|---|
| `3464c88` | Task 1：AttachedImage（纯函数 + 12 条单测） |
| `c56fe15` | Task 2：协议 + 侧车（7 + 3 条测试） |
| `0ffb865` | Task 3：输入框接线 + 附件带（17 条测试 + 渲染探针） |
| `0ef7bda` | Task 4：发送与排队带图（5 条测试 + 排队条探针两张图） |
| `16403b0` | Task 5：转写区（13 条 web 测试 + 布局探针两条断言） |

三侧全绿：Kotlin 935、侧车 147、web 212；`tsc -b` 干净；布局探针两场景通过。

### 计划本身的两处偏差

1. **Task 4 / Task 5 的边界动了**。计划把"推给转写区的中等图"写在 Task 4 里，
   实际落在 Task 5 —— 协议（`TranscriptItem.User.images`）与 web 那一半必须一起改：
   只改一半的话，图发出去了、转写区不显示，而中间那个提交是**能编译、能跑**的，
   看起来像"功能没做完"而不是"还没做"。所以 Task 4 只到"发送与排队带图"。
2. **`queueLineText` 计划里没提**。排队条只有一条时是折叠写法（没有列表），
   所以「图 N」得写进那一行 —— 出图才发现的（第一版探针图上"排队 1 · 内容"
   后面什么都没有，而那条明明贴着两张图）。

### 渲染出来才发现的错（单测全绿时它就在）

**✕ 徽标用兄弟组件叠在缩略图上 —— 屏幕上根本看不见。**
组件树打出来是对的（`RemoveBadge bounds=42,0,14x14 visible=true`），出图只有缩略图。
原因是重叠的兄弟组件谁盖谁由 z 序决定，而**先 add 的反而在上面**（与直觉相反）。
改成画进同一层（缩略图自己画 ✕，命中判定也用它）之后正常，还省掉了两边的同步。
—— 这一条是"渲染出来看一眼"第三次抓到单测看不见的错，记在
`[[render-swing-to-look-at-it]]` 那条教训下面。

### 仍未做的（都不是漏，是划出去的）

- **恢复历史会话时，历史里的图不显示**：历史条目只带回文本。SDK 的 history 里
  有没有 image block 没验过，要做得先验一次。
- **"在编辑器里打开这张图"**：设计稿里就写了不做（要落临时文件、还要管清理）。
- **拖拽分支的 `isDrop == true` 测不到**：`TransferSupport.setDrop` 是包内可见，
  只有 AWT 自己在拖拽时会设。所以那两条规则抽成了纯函数 [attachImagesWanted] 单测，
  传输路径那一层只覆盖到"粘"。
- **超过 8MB 的原图直接拒绝**（给一行原因），没有"自动裁一半再试"。
- **通知式提示**：图没能收下时，原因写在附件带上；没有做系统通知。

### 一件要记着的事

`deepseek-v4-flash[1m]`（插件当前用的模型，走的 tokenhub 网关）**实测收图**：
`sidecar/tools/probe-image.mjs` 两次都答对（第二次把颜色换到另一边问的，不是猜的）。
但网关是第三方的，哪天开始不认 image block，错误会原样出现在转写区 ——
那条路不会静默丢图。
