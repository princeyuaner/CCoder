# 粘贴图片

日期：2026-09-13
状态：设计已定，待实现

---

## 1. 要解决的问题

现在输入区只能发文字。用户截图之后没有任何路径把它交给 Claude —— 而"这张报错截图你看一眼"是这个插件最常见的用法之一。

要做的是：**把图弄进输入区，让它跟着消息一起送到 Claude，并且在对话里看得见。**

### 1.1 已核实的事实

设计建立在三条实测结论上，不是推测：

1. **SDK 原生支持图片输入。** `SDKUserMessage.message` 是标准 `MessageParam`，"content is a string or an array of content blocks (text, image, document, tool_result, ...)"（`sdk.d.ts:5464`）。所以 `session.js` 里把 `content` 从字符串换成内容块数组即可，不需要绕过什么。

2. **历史里图片就是 base64 内联存的。** 在本机真实会话 jsonl 里翻到了当年粘贴的图，形状是：

   ```json
   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo..."}}
   ```

   而且**图在前、文字在后**（实测块序列为 `image,text`）。这意味着：

   - 回放能直接读回真图，不需要额外存储
   - 我们发出去的顺序也应该照抄这个约定

3. **一张截图 base64 后约 90–300KB**（实测两张：89KB / 287KB）。这个量级决定了下文的限额取值。

## 2. 数据与控制流

```
剪贴板 / 拖拽 / 文件选择器
      ↓ 原始字节
  归一化 normalize()        ← 纯函数，可测
      ↓ ImageAttachment(mediaType, base64)
  输入区附件条（缩略图，可删）
      ↓ 发送
  Protocol.encodeSend(id, text, images)
      ↓ NDJSON
  sidecar session.send(text, images)
      ↓ content 数组
  [{type:'image',...}, ..., {type:'text',...}]   ← 图在前
```

**`ImageAttachment` 存 base64 而不是字节**：协议、转写区 op 契约、缩略图这三条路最终要的都是 base64，中间再转一次没有收益；`String` 的 equals 也让测试断言好写。归一化在读入时一次性做完，之后全程只有字符串。

## 3. 归一化（`ImageAttachment.kt`，纯函数）

```kotlin
internal data class ImageAttachment(val mediaType: String, val base64: String)

internal fun normalizeImage(raw: ByteArray, hintMediaType: String): ImageAttachment
```

```
1. ImageIO.read 解不出（webp 等）→ 原样 base64 返回，不缩放
2. 长边 > 1568 → 等比缩放（双线性插值）
3. 编码：源是 JPEG → JPEG(q0.85)；否则 → PNG
4. 编码结果比原始字节还大 → 回退原图
5. 结果 base64 仍 > 1.5MB → 按 JPEG q0.6 / q0.4 重试
6. base64 编码
```

三条硬规矩：

**① 1568 而不是 2000。** Claude Code 自己缩到 2000，但 1568 是 API 的视觉最优长边（超过它只会被服务端再缩一次，token 却照算）。既然要缩，就一步缩到位。

**② 有 alpha 通道的 PNG 不许转 JPEG。** 透明区在 JPEG 里会变成黑块 —— 截图带透明圆角时特别明显。步骤 5 的降质路径只在无 alpha 时启用。

**③ mediaType 跟着实际编码格式走。** 步骤 5 若把 PNG 降成了 JPEG，`mediaType` 必须同步改成 `image/jpeg`；不改的话 Claude 会按 PNG 去解 JPEG 字节，直接报错。

## 4. 输入区：三个入口，一条下游

三个入口都汇到同一个 `addImages(List<ImageAttachment>)`：

| 入口 | 实现 | 说明 |
|---|---|---|
| Ctrl+V 粘贴 | 替换 `ActionMap` 里的 paste action | **不能靠覆写 `JTextArea.paste()`** —— Swing 的 Ctrl+V 绑的是 ActionMap 里的 action，不走那个方法 |
| 拖拽文件 | `TransferHandler` | 接 `javaFileListFlavor`（按扩展名过滤）+ `imageFlavor` |
| 工具栏按钮 | `FileChooser` 多选 | 接在 `buildStatusRow(model, mode)`（`ComposerToolbar.kt:148`）左侧 |

**剪贴板同时有图又有文本时，有图优先。** 截图工具（Win+Shift+S、Snipaste、微信）基本只放图，所以这条在实际使用中很少触发；代价是「从 Excel 复制表格」会粘成一张图而不是文本，需要手动删一下。

### 4.1 附件条

卡片布局从 `CENTER=输入框` 改成 `CENTER=纵向{附件条, 输入框}`（`buildComposerCard` 加一个参数）。

```
┌─────────────────────────────────────────┐
│ ┌────┐ ┌────┐                           │
│ │🖼 ✕│ │🖼 ✕│   1500×3400 · 312KB       │  ← 附件条（有图才在）
│ └────┘ └────┘                           │
│ 在这里输入…                              │
│                          [模型] [模式] ▶│
└─────────────────────────────────────────┘
```

- **无图时整条隐藏。** 不做的话输入区永远多一条空白，且 `minimumSize` 白涨。
- 缩略图固定高 64px、宽度等比。悬停显示 `1500×3400 · 312KB`（**原始**尺寸与大小 —— 用户想知道的是"我粘的是哪张图"）。
- ✕ 删除；发送后整条清空。
- 超限被拒的图不进条，改在条的位置显示一行提示：「已跳过 2 张：单张超过 5MB」。下次添加时清掉。**不用弹窗**——粘错了弹一个模态框比不提示还烦。

## 5. 协议与 sidecar

```json
{"id":"req-3","method":"send","params":{
  "text":"这个报错什么意思",
  "images":[{"mediaType":"image/png","data":"iVBORw0KGgo..."}]
}}
```

- `images` 省略即纯文本，**纯文本仍走字符串形式**（`encodeSend` 不带 images 时行为完全不变，现有测试一条不用改）。
- sidecar `index.js` 的 `preStartQueue` 从存 `String` 改成存 `{text, images}`（`index.js:93-101`）。
- `session.send(text, images)` 构造 content 数组：

```js
const content = images.map(i => ({type:'image', source:{type:'base64', media_type:i.mediaType, data:i.data}}));
if (text) content.push({type:'text', text});
// 无图时退回字符串形式，保持既有行为
```

**纯图无文字是合法的**：此时 content 只有 image 块。CLI 侧接受这种输入，但**实现时要真跑一次冒烟**确认，不能只看类型定义。

## 6. 转写区渲染

`TranscriptItem.User` 末尾加两个字段（带默认值，现有构造点一个都不破）：

```kotlin
data class User(
    override val id: String,
    override val ts: Long,
    val text: String,
    val images: List<TranscriptImage> = emptyList(),
    val omittedImages: Int = 0,
) : TranscriptItem
```

链路：`TranscriptOpCodec.encodeItem` 在 `images` 非空时输出数组 → `shared/transcript-ops.json` 加一条带图用例（两端共用，改契约必变红）→ web `codec.ts` **逐项校验、坏的丢单项不丢整条** → `UserBubble.tsx` 渲染 `data:` URI。

`data:` 里塞 base64 要经过 `escapeForJsString`（`ThemeInjector.kt:76`），已核实**不需要改动**：base64 字母表里没有反斜杠与单引号，而 Gson 对 `=` 的 `=` 转义在这条路上能正确还原（`JSON.parse` 收到的是转义序列本身，不是被吃掉的反斜杠）。

## 7. 回放与限流

`MessageRenderer.renderPrompt` 现在返回 `String?`，遇到 image block 静默丢掉（`MessageRenderer.kt:70`）。改成：

```kotlin
internal data class PromptContent(val text: String?, val images: List<TranscriptImage>)
internal fun renderPrompt(item: JsonObject): PromptContent?
```

**顺带修一个既有 bug**：纯图无文字的提问现在会被末尾的 `isBlank()` 判成 null，回放时那整条消息连同图一起消失。新规则是「文字与图片都空才算 null」。

### 7.1 限流

按累计 base64 字节预算保留图片，超出的部分**不送数据**，只在气泡里让 `omittedImages` 计数说话。

- 初值：最多 12 张 / 累计 6MB
- 超出时气泡底部显示「已省略 N 张图」

**为什么必须限流**：一个图片多的老会话，历史原样推给 JCEF 就是几十 MB 的注入脚本，界面会卡死几秒甚至白屏。阈值是估的，实现时拿真实会话量一遍再定。

## 8. 边界与策略

| 项 | 取值 | 行为 |
|---|---|---|
| 单张原始字节 | ≤ 5MB | 超出：拒绝 + 提示行 |
| 单条消息张数 | ≤ 5 张 | 超出：拒绝多余部分 + 提示行 |
| 支持格式 | png / jpg / jpeg / gif / bmp / webp | webp 走"解不出→原样发"路径 |
| 长边 | ≤ 1568 | 超出等比缩放 |
| 归一化后单张 | ≤ 1.5MB base64 | 超出降质重试，仍超则原样保留（不丢图） |

**拒绝而不静默丢弃**：粘了 6 张只发 5 张却不吭声，用户会以为全发出去了。

## 9. 线程

图片解码 + 缩放是几十到几百毫秒级的活，**不能在 EDT 上做**，否则粘一张 4K 截图会卡住整个 IDE。

```
EDT：探测剪贴板 flavor → 有图就丢给后台线程
后台：读字节 + 归一化
EDT：把 ImageAttachment 加进附件条
```

平台的 `CopyPasteManager` 负责取数据（它自己处理了跨进程剪贴板的死锁问题），只把**策略与归一化**留在纯函数里 —— 这样单测不需要起平台环境，与 `styleComposerInput` 抽出来可测是同一个路子（`ComposerInput.kt:19`）。

## 10. 新增与改动

| 文件 | 内容 |
|---|---|
| `ui/ImageAttachment.kt` **新增** | 数据模型 + `normalizeImage` + 扩展名/尺寸判定（纯逻辑） |
| `ui/ImageIngest.kt` **新增** | 三个入口的采集：剪贴板 flavor 判定、Transferable、文件 → 原始字节 + 提示文案 |
| `ui/ComposerAttachments.kt` **新增** | 缩略图条 UI |
| `ui/ImageAttachmentTest.kt` **新增** | 归一化与限额判定的单测 |
| `ui/ImageIngestTest.kt` **新增** | 剪贴板策略、扩展名过滤的单测 |
| `ui/ComposerAttachmentsTest.kt` **新增** | 附件条的单测 |
| `ui/ComposerAttachmentsRenderProbe.kt` **新增** | 离屏渲染探针 |

改动：

| 文件 | 改什么 |
|---|---|
| `ui/ComposerInput.kt` | 装 paste 拦截 |
| `ui/Composer.kt` | `buildComposerCard` 加参数，CENTER 换成纵向{附件条, 输入框} |
| `ui/ComposerToolbar.kt` | `buildStatusRow` 加附件按钮 |
| `ui/MessageRenderer.kt` | `renderPrompt` 改返回 `PromptContent?` |
| `ui/TranscriptOp.kt` | `User` 加两个字段 |
| `ui/TranscriptOpCodec.kt` | user 分支输出 `images` / `omittedImages` |
| `sidecar/Protocol.kt` | `encodeSend` 加 images 参数 |
| `ui/ClaudePanel.kt` | 接线：`sendCurrentInput`（空判据加图片）、`pendingFirstMessage` 改带图（`ClaudePanel.kt:205`、`:1023`、`:1387`）、`replayItems`（`:703`） |
| `sidecar/session.js` | `send` 构造 content 数组 |
| `sidecar/index.js` | `send` 分支传 images；`preStartQueue` 存对象 |
| `web/src/types.ts` | `UserItem` 加 `images` / `omittedImages` |
| `web/src/codec.ts` | `parseItem` 的 user 分支解析 images |
| `web/src/components/UserBubble.tsx` | 渲染图片与省略计数 |
| `web/src/styles.css` | 图片样式 |
| `shared/transcript-ops.json` | 加带图用例 |

## 11. 测试

**纯逻辑**
- `normalizeImage`：长边 4000 → 缩到 1568；短边不动比例；JPEG 源出 JPEG、PNG 源出 PNG；带 alpha 的 PNG 不转 JPEG；编码变大回退原图；解不出的字节原样返回
- 尺寸与张数判定：5MB 边界、5 张边界
- 剪贴板策略：只有文本 → 不接管；有图 → 接管
- `Protocol.encodeSend`：带图 / 不带图（不带时字符串里不应出现 `images` 字段）
- `TranscriptOpCodec`：带图的 user 项形状、`omittedImages=0` 时不输出该字段
- `MessageRenderer.renderPrompt`：纯图无文字**不再返回 null**（那条既有 bug 的钉子）、含 tool_result 仍返回 null、图文混排取到两者

**sidecar**
- `session.send(text, images)` 的 content 形状：图在前文字在后；纯图无文字；不带图时退回字符串

**web**
- `codec.test.ts`：合法 images 解析；`mediaType` 或 `data` 类型不符时丢单项保留整条
- `UserBubble`：有图渲染 `<img>`；`omittedImages > 0` 时渲染省略提示
- `shared/transcript-ops.json` 的新用例两侧测试都读

**探针（`ComposerAttachmentsRenderProbe`）**
- 空 / 一张 / 三张 / 带提示行，深浅两色各出一张 PNG

## 12. 代价

- **输入区最小高度在有图时增加约 74px**（64 缩略图 + 边距），会顶掉转写区同样的高度。与状态卡那次同一个机制（`TranscriptSplit.kt` 的 `honorComponentsMinimumSize`）。无图时不变。

  ⚠️ 74px 是**估的，没量过**。实现时写一条断言直接读 `minimumSize`，把它变成量出来的数。
- 转写区注入脚本随图片线性变大。单批最坏 5×1.5MB ≈ 10MB 的 JS 字符串，会有一次可感的卡顿。这是限额取 5 张 / 1.5MB 的原因。
- 图片进 NDJSON 后，sidecar 的每行长度从 KB 级升到 MB 级。`readline` 与 Kotlin 侧的逐行读取都能处理，但没有为这个量级做过优化。

## 13. 明确不做的

- ~~不做图片点击放大/查看原图~~ —— **2026-09-17 反转**：输入框里那几张补上了点击放大，
  见 `2026-09-17-input-image-preview-design.md`（转写区那份 `ImageLightbox` 当时就已经能点）
- 不做图片在文字中间的混排（附件统一挂在气泡下方）
- 不做从截图工具直接截图（不内置截图器）
- 不做图片的持久化缓存（回放靠会话历史里的 base64，不额外存一份）
- 不改发送按钮在"纯图无文字"时的可用性判定逻辑之外的东西
