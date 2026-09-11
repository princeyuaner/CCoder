# CCoder 工具窗口 UI 重设计实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把工具窗口的转写区从原生 Swing 换成 JCEF + React，重做为"现代聊天"风格，并顺带修复通知组注册失败。

**Architecture:** 转写区换成一个 `JBCefBrowser`，Kotlin 侧把渲染项编码成 JSON 操作批量推送（16ms 节流），React 侧消费并渲染。输入区与权限卡片留在原生 Swing —— 前者是中文输入法考虑，后者是安全考虑。`MessageRenderer` 与权限相关代码一行不动。

**Tech Stack:** Kotlin + IntelliJ Platform Gradle Plugin 2.18.0 + JCEF（`com.intellij.modules.jcef`）；Vite + React + TypeScript + `vite-plugin-singlefile` + `marked` + `highlight.js`；Vitest + Testing Library（前端），JUnit 5（Kotlin）。

**Spec:** `docs/superpowers/specs/2026-09-11-ccoder-ui-redesign-design.md`

## Global Constraints

- **JCEF 依赖**：`plugin.xml` 加 `<depends>com.intellij.modules.jcef</depends>`，`build.gradle.kts` 加 `bundledPlugin("intellij.platform.ui.jcef")`。该别名自 **2025.3.1** 起稳定，项目基线 2025.3.1.1 满足。
- **JCEF API**：用 `JBCefJSQuery.create(browser as JBCefBrowserBase)` —— `create(JBCefBrowser)` 重载已废弃。
- **降级路径**：启动时检查 `JBCefApp.isSupported()`，不支持则回退到现有原生渲染。**原生渲染代码不删。**
- **不做**：消息重新生成、编辑已发消息、导出对话、转写区虚拟滚动、消息搜索、附件/图片。
- **契约单一来源**：Kotlin 与 React 共用 `shared/transcript-ops.json` fixture，两端各有一组测试消费它，任一端的契约改动都会让另一端变红。
- **颜色不硬编码**：全部从平台 API 取值注入成 CSS 变量。
- **代码块默认不换行**，横向滚动，右上角有「自动换行」开关（localStorage 持久化）。
- **尊重 `prefers-reduced-motion`**。
- **提交信息**结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`

---

## 文件结构

```
CCoder/
├── shared/
│   └── transcript-ops.json                  # Task 3：两侧共用的契约 fixture
├── web/                                     # Task 2：独立 Vite 项目
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── vitest.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx                         # Task 2（Task 7 补 styles.css 引用）
│       ├── test-setup.ts                    # Task 4
│       ├── App.tsx                          # Task 2（占位）→ Task 7（实现）
│       ├── codec.ts                         # Task 4：op 消费
│       ├── types.ts                         # Task 4：op 与 item 类型 + window.ccoder 声明
│       ├── components/
│       │   ├── Transcript.tsx               # Task 7（Task 9 接入 Markdown）
│       │   ├── Collapsible.tsx              # Task 7
│       │   ├── UserBubble.tsx               # Task 7
│       │   ├── AssistantBubble.tsx          # Task 7
│       │   ├── ThinkingBlock.tsx            # Task 7
│       │   ├── ToolCallBlock.tsx            # Task 7
│       │   ├── ErrorBubble.tsx              # Task 7
│       │   ├── ResultLine.tsx               # Task 7
│       │   ├── SystemNote.tsx               # Task 7
│       │   ├── StreamingCursor.tsx          # Task 7
│       │   ├── CodeBlock.tsx                # Task 8
│       │   └── Markdown.tsx                 # Task 9
│       ├── bridge.ts                        # Task 9：JS → Kotlin
│       ├── highlight.ts                     # Task 8：按需注册语言
│       └── styles.css                       # Task 7（Task 8、9 追加）
└── src/
    ├── main/kotlin/com/ccoder/
    │   ├── sidecar/TranscriptOp.kt          # Task 3
    │   ├── ui/TranscriptOpCodec.kt          # Task 3
    │   ├── ui/ThemeInjector.kt              # Task 5
    │   ├── ui/TranscriptPump.kt             # Task 6
    │   ├── ui/ClaudeTranscriptView.kt       # Task 6
    │   └── ui/ClaudePanel.kt                # Task 10：改
    ├── main/resources/META-INF/plugin.xml   # Task 1、Task 6：改
    └── test/kotlin/com/ccoder/
        ├── sidecar/TranscriptOpCodecTest.kt # Task 3
        ├── ui/ThemeInjectorTest.kt          # Task 5
        └── ui/TranscriptPumpTest.kt         # Task 6
```

---

## Task 1: 修复通知组注册失败

根因已定位：`displayType` 的合法值只有 `BALLOON` / `STICKY_BALLOON` / `TOOL_WINDOW` / `NONE`，而写的是 `STICKY` —— 不是合法常量，平台解析为 null。

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\resources\META-INF\plugin.xml:30-33`

- [ ] **Step 1: 修正 displayType**

把：

```xml
<notificationGroup
    id="CCoder Permissions"
    displayType="STICKY"
    isLogByDefault="false"/>
```

改为：

```xml
<notificationGroup
    id="CCoder Permissions"
    displayType="STICKY_BALLOON"
    isLogByDefault="false"/>
```

- [ ] **Step 2: 验证警告消失**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew runIde --no-daemon 2>&1 | grep -i "notification group" | head -3
```

预期：**无输出**（警告消失）。启动后关闭沙箱 IDE。

若仍报同样警告，说明 `STICKY_BALLOON` 也不是它要的值 —— 检查日志里完整消息，不要猜。

- [ ] **Step 3: 确认现有测试未受影响**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "BUILD"
```

预期：BUILD SUCCESSFUL，101 个测试仍全绿。

- [ ] **Step 4: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/resources/META-INF/plugin.xml && git commit -F - <<'EOF'
fix(notifications): 修正 notificationGroup 的 displayType 取值

根因：displayType 的合法值只有 BALLOON / STICKY_BALLOON / TOOL_WINDOW / NONE，
而写的是 STICKY。它不是合法常量，平台解析为 null，报
"displayType should be not null"，通知组注册因此失败。

影响：spec §6.3「不可忽略性补偿」里的粘性通知此前完全不工作
（状态栏计数与卡片置顶两项不受影响）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: web 前端项目骨架 + Vite 单文件构建

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\web\package.json`
- Create: `C:\Users\CY\Desktop\CCoder\web\vite.config.ts`
- Create: `C:\Users\CY\Desktop\CCoder\web\tsconfig.json`
- Create: `C:\Users\CY\Desktop\CCoder\web\index.html`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\main.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\App.tsx`
- Modify: `C:\Users\CY\Desktop\CCoder\.gitignore`

**Interfaces:**
- Consumes: 无
- Produces: `web/dist/index.html` —— 一个自包含的单文件（JS/CSS 全部内联），供 Task 6 的 JCEF 加载

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "ccoder-webui",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "highlight.js": "11.10.0",
    "marked": "14.1.3",
    "react": "18.3.1",
    "react-dom": "18.3.1"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "6.6.3",
    "@testing-library/react": "16.1.0",
    "@testing-library/user-event": "14.5.2",
    "@types/react": "18.3.12",
    "@types/react-dom": "18.3.1",
    "@vitejs/plugin-react": "4.3.4",
    "jsdom": "25.0.1",
    "typescript": "5.7.2",
    "vite": "6.0.5",
    "vite-plugin-singlefile": "2.0.3",
    "vitest": "2.1.8"
  }
}
```

版本全部**精确固定**，不带 `^` —— 前端依赖的 breaking change 会让构建在某天突然失败，固定版本让升级成为显式动作。

- [ ] **Step 2: 创建 vite.config.ts**

```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// 产物必须是单个自包含 HTML：JCEF 直接用 loadHTML() 加载，
// 不需要运行时提取资源、不需要自定义 CefResourceHandler。
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    outDir: 'dist',
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000,   // 一切资源内联
    rollupOptions: {
      output: { inlineDynamicImports: true },
    },
  },
})
```

- [ ] **Step 3: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "resolveJsonModule": true,
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"]
}
```

- [ ] **Step 4: 创建 index.html**

```html
<!doctype html>
<html lang="zh">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>CCoder</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: 创建 App.tsx 与 main.tsx（占位，Task 4 起替换）**

`web/src/App.tsx`：

```tsx
export function App() {
  return <div className="app">CCoder 前端已加载</div>
}
```

`web/src/main.tsx`：

```tsx
import React from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'

const root = document.getElementById('root')
if (root) createRoot(root).render(<React.StrictMode><App /></React.StrictMode>)
```

- [ ] **Step 6: 安装依赖并验证单文件产出**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm install --no-audit --no-fund && npm run build
```

预期：`dist/` 下**只有一个 `index.html`**（可能另有 `vite.svg`）。

验证确实是单文件（JS 与 CSS 都内联）：

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && ls dist/ && echo "--- 内联脚本数 ---" && grep -c "<script" dist/index.html && echo "--- 是否引用外部 js ---" && (grep -o 'src="[^"]*\.js"' dist/index.html || echo "无外部 js 引用 ✓")
```

预期：`grep -c "<script"` ≥ 1，且**没有** `src="...js"` 的外部引用。

- [ ] **Step 7: 更新 .gitignore**

在 `C:\Users\CY\Desktop\CCoder\.gitignore` 的 Node 段追加：

```
# ---- web 前端 ----
web/dist/
web/node_modules/
web/coverage/
```

- [ ] **Step 8: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add web/package.json web/package-lock.json web/vite.config.ts web/tsconfig.json web/index.html web/src/ .gitignore && git commit -F - <<'EOF'
build(webui): Vite + React 前端骨架，产出单文件 HTML

用 vite-plugin-singlefile 把所有 JS/CSS 内联进单个 HTML，JCEF 直接
loadHTML() 加载——不需要运行时提取资源，也不需要自定义资源处理器。
这是最少活动部件的做法。

依赖版本精确固定不带 ^：前端依赖的 breaking change 会让构建在某天
突然失败，固定版本让升级成为显式动作而不是意外。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 3: 桥接契约 —— 操作类型与共享 fixture

这是 Kotlin 与 React 之间唯一的接口。**两侧共用一份 fixture**，任一端的契约改动都会让另一端变红。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\TranscriptOp.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\TranscriptOpCodec.kt`
- Create: `C:\Users\CY\Desktop\CCoder\shared\transcript-ops.json`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\TranscriptOpCodecTest.kt`

**Interfaces:**
- Consumes: `com.google.gson`（平台自带）
- Produces:
  - `sealed interface TranscriptItem`，子类：`User`、`Assistant`、`Thinking`、`ToolUse`、`Error`、`Result`、`SystemNote`，均带 `id: String` 与 `ts: Long`
  - `sealed interface TranscriptOp`，子类：`Append`、`AppendDelta`、`FinalizeDelta`、`ClearDelta`、`Reset`
  - `object TranscriptOpCodec`，方法 `fun encodeBatch(ops: List<TranscriptOp>): String`

- [ ] **Step 1: 创建共享 fixture**

`shared/transcript-ops.json` —— 这是契约的**唯一真相来源**，两端都读它：

```json
[
  {"op":"reset"},
  {"op":"append","item":{"kind":"user","id":"m0","ts":1726050000000,"text":"你好"}},
  {"op":"append","item":{"kind":"systemNote","id":"m1","ts":1726050001000,"text":"会话 a1b2c3d4 · 模型 m"}},
  {"op":"appendDelta","target":"assistant","text":"你"},
  {"op":"appendDelta","target":"assistant","text":"好"},
  {"op":"finalizeDelta","target":"assistant","text":"你好呀"},
  {"op":"append","item":{"kind":"thinking","id":"m2","ts":1726050002000,"text":"让我想想"}},
  {"op":"append","item":{"kind":"toolUse","id":"m3","ts":1726050003000,"name":"Read","input":"{\"file_path\":\"/a.txt\"}"}},
  {"op":"clearDelta","target":"assistant"},
  {"op":"append","item":{"kind":"error","id":"m4","ts":1726050004000,"text":"认证失败"}},
  {"op":"append","item":{"kind":"result","id":"m5","ts":1726050005000,"subtype":"success","costUsd":0.1008,"durationMs":1681}},
  {"op":"append","item":{"kind":"assistant","id":"m6","ts":1726050006000,"text":"完成"}}
]
```

- [ ] **Step 2: 写失败的测试**

`src/test/kotlin/com/ccoder/sidecar/TranscriptOpCodecTest.kt`：

```kotlin
package com.ccoder.sidecar

import com.ccoder.ui.TranscriptOpCodec
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TranscriptOpCodecTest {

    /** 仓库根的 shared/ 目录，与 React 侧读的是同一份文件。 */
    private fun fixturePath(): Path {
        // 测试的工作目录是项目根（Gradle 默认）
        val path = Path.of("shared", "transcript-ops.json")
        check(Files.exists(path)) { "找不到共享 fixture：${path.toAbsolutePath()}" }
        return path
    }

    @Test
    fun `fixture 中的每一条 op 都能被解析`() {
        val array = JsonParser.parseString(Files.readString(fixturePath())).asJsonArray
        val kinds = array.map { it.asJsonObject.get("op").asString }

        assertEquals("reset", kinds[0])
        assertEquals("append", kinds[1])
        assertEquals("appendDelta", kinds[3])
        assertEquals("finalizeDelta", kinds[5])
        assertEquals("clearDelta", kinds[8])
        assertEquals(12, kinds.size)
    }

    @Test
    fun `reset 编码为仅有 op 字段`() {
        val json = TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Reset))
        val obj = JsonParser.parseString(json).asJsonArray[0].asJsonObject
        assertEquals("reset", obj.get("op").asString)
        assertEquals(1, obj.size(), "reset 不应携带额外字段")
    }

    @Test
    fun `append 编码包含完整 item`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.User(id = "m0", ts = 1726050000000L, text = "你好")
            )
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("user", item.get("kind").asString)
        assertEquals("m0", item.get("id").asString)
        assertEquals(1726050000000L, item.get("ts").asLong)
        assertEquals("你好", item.get("text").asString)
    }

    @Test
    fun `result 编码省略为 null 的可选字段`() {
        val ops = listOf(
            TranscriptItem.Result(id = "m", ts = 1L, subtype = "success", costUsd = null, durationMs = null)
                .let { TranscriptOp.Append(it) }
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("success", item.get("subtype").asString)
        assertTrue(!item.has("costUsd"), "null 的可选字段不应出现")
        assertTrue(!item.has("durationMs"))
    }

    @Test
    fun `result 编码保留非 null 的可选字段`() {
        val ops = listOf(
            TranscriptItem.Result(id = "m", ts = 1L, subtype = "success", costUsd = 0.1008, durationMs = 1681L)
                .let { TranscriptOp.Append(it) }
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals(0.1008, item.get("costUsd").asDouble, 0.0001)
        assertEquals(1681L, item.get("durationMs").asLong)
    }

    @Test
    fun `delta 类 op 编码 target 与 text`() {
        val ops = listOf(
            TranscriptOp.AppendDelta("assistant", "你"),
            TranscriptOp.FinalizeDelta("assistant", "你好"),
            TranscriptOp.ClearDelta("assistant"),
        )
        val array = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops)).asJsonArray

        assertEquals("assistant", array[0].asJsonObject.get("target").asString)
        assertEquals("你", array[0].asJsonObject.get("text").asString)
        assertEquals("你好", array[1].asJsonObject.get("text").asString)
        assertEquals("assistant", array[2].asJsonObject.get("target").asString)
        assertTrue(!array[2].asJsonObject.has("text"), "clearDelta 不带 text")
    }

    @Test
    fun `批次编码为 JSON 数组`() {
        val json = TranscriptOpCodec.encodeBatch(
            listOf(TranscriptOp.Reset, TranscriptOp.ClearDelta("assistant"))
        )
        val array = JsonParser.parseString(json).asJsonArray
        assertEquals(2, array.size())
    }

    @Test
    fun `空批次编码为空数组`() {
        assertEquals("[]", TranscriptOpCodec.encodeBatch(emptyList()))
    }

    @Test
    fun `文本中的换行与引号被正确转义`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.Assistant(id = "m", ts = 1L, text = "第一行\n第二行 \"引号\"")
            )
        )
        val json = TranscriptOpCodec.encodeBatch(ops)
        // 编码结果必须是单行——pushBatch 的参数不能含裸换行
        assertEquals(1, json.lines().size, "换行必须被 JSON 转义")
        val text = JsonParser.parseString(json).asJsonArray[0]
            .asJsonObject.getAsJsonObject("item").get("text").asString
        assertEquals("第一行\n第二行 \"引号\"", text)
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*TranscriptOpCodecTest*' --no-daemon 2>&1 | grep -E "(BUILD|e: )" | head -5
```

预期：FAIL，`Unresolved reference 'TranscriptOpCodec'`。

- [ ] **Step 4: 实现 TranscriptOp.kt**

`src/main/kotlin/com/ccoder/sidecar/TranscriptOp.kt`：

```kotlin
package com.ccoder.sidecar

/**
 * 转写区中的一条消息项。
 *
 * 与 sidecar 协议无关 —— 那是 [SidecarMessage] 的职责。这里的类型是
 * Kotlin 与 React 之间的渲染契约，由 [com.ccoder.ui.TranscriptOpCodec] 序列化。
 */
sealed interface TranscriptItem {
    val id: String
    val ts: Long

    data class User(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Assistant(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Thinking(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class ToolUse(
        override val id: String,
        override val ts: Long,
        val name: String,
        val input: String,
    ) : TranscriptItem

    data class Error(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Result(
        override val id: String,
        override val ts: Long,
        val subtype: String,
        val costUsd: Double?,
        val durationMs: Long?,
    ) : TranscriptItem

    data class SystemNote(override val id: String, override val ts: Long, val text: String) : TranscriptItem
}

/**
 * 推送给 React 的操作。
 *
 * [FinalizeDelta] 的存在是必需的：逐 token 增量与最终 assistant 消息是两条
 * 独立来源，两者都渲染会出现重复文本。以最终消息为准收尾。
 */
sealed interface TranscriptOp {
    data class Append(val item: TranscriptItem) : TranscriptOp

    /** [target] 当前只有 "assistant" 一个取值 —— 思考流的逐字渲染被刻意丢弃。 */
    data class AppendDelta(val target: String, val text: String) : TranscriptOp

    data class FinalizeDelta(val target: String, val text: String) : TranscriptOp

    data class ClearDelta(val target: String) : TranscriptOp

    data object Reset : TranscriptOp
}
```

- [ ] **Step 5: 实现 TranscriptOpCodec.kt**

`src/main/kotlin/com/ccoder/ui/TranscriptOpCodec.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 把转写操作序列化为推送用的 JSON。
 *
 * 契约的另一端在 `web/src/codec.ts`，两侧共用 `shared/transcript-ops.json`
 * 做测试 —— 任一端改了契约，另一端的测试就会变红。
 *
 * 输出必须能被安全地嵌进 JS 字符串，因此：
 * - 换行由 JSON 转义保证，不产生多行
 * - 使用 Gson 默认的 HTML 转义（`<` `>` `&` `=` `'`），
 *   避免注入到 <script> 上下文时提前闭合
 */
object TranscriptOpCodec {

    fun encodeBatch(ops: List<TranscriptOp>): String {
        val array = JsonArray()
        ops.forEach { array.add(encode(it)) }
        return array.toString()
    }

    private fun encode(op: TranscriptOp): JsonObject = when (op) {
        is TranscriptOp.Reset -> JsonObject().apply { addProperty("op", "reset") }

        is TranscriptOp.Append -> JsonObject().apply {
            addProperty("op", "append")
            add("item", encodeItem(op.item))
        }

        is TranscriptOp.AppendDelta -> JsonObject().apply {
            addProperty("op", "appendDelta")
            addProperty("target", op.target)
            addProperty("text", op.text)
        }

        is TranscriptOp.FinalizeDelta -> JsonObject().apply {
            addProperty("op", "finalizeDelta")
            addProperty("target", op.target)
            addProperty("text", op.text)
        }

        is TranscriptOp.ClearDelta -> JsonObject().apply {
            addProperty("op", "clearDelta")
            addProperty("target", op.target)
        }
    }

    private fun encodeItem(item: TranscriptItem): JsonObject {
        val obj = JsonObject().apply {
            addProperty("id", item.id)
            addProperty("ts", item.ts)
        }
        when (item) {
            is TranscriptItem.User -> obj.addProperty("kind", "user").also { it.addProperty("text", item.text) }
            is TranscriptItem.Assistant -> obj.addProperty("kind", "assistant").also { it.addProperty("text", item.text) }
            is TranscriptItem.Thinking -> obj.addProperty("kind", "thinking").also { it.addProperty("text", item.text) }
            is TranscriptItem.Error -> obj.addProperty("kind", "error").also { it.addProperty("text", item.text) }
            is TranscriptItem.SystemNote -> obj.addProperty("kind", "systemNote").also { it.addProperty("text", item.text) }

            is TranscriptItem.ToolUse -> obj.addProperty("kind", "toolUse").also {
                it.addProperty("name", item.name)
                it.addProperty("input", item.input)
            }

            is TranscriptItem.Result -> obj.addProperty("kind", "result").also {
                it.addProperty("subtype", item.subtype)
                item.costUsd?.let { cost -> it.addProperty("costUsd", cost) }
                item.durationMs?.let { ms -> it.addProperty("durationMs", ms) }
            }
        }
        return obj
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*TranscriptOpCodecTest*' --no-daemon 2>&1 | grep -E "(BUILD|FAILED)" | head -3
```

预期：BUILD SUCCESSFUL，9 个测试通过。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/TranscriptOp.kt src/main/kotlin/com/ccoder/ui/TranscriptOpCodec.kt src/test/kotlin/com/ccoder/sidecar/TranscriptOpCodecTest.kt shared/ && git commit -F - <<'EOF'
feat(transcript): 转写操作类型与共享契约 fixture

Kotlin 与 React 之间唯一的接口。shared/transcript-ops.json 是契约的唯一
真相来源，两端各有一组测试消费它——任一端改了契约，另一端就会变红。

FinalizeDelta 的存在是必需的：逐 token 增量与最终 assistant 消息是两条
独立来源，两者都渲染会出现重复文本，必须以最终消息为准收尾。

result 的 costUsd/durationMs 为 null 时字段整体省略而非输出 null——
让 React 侧少一层判空。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: React 侧消费操作

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\web\src\types.ts`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\codec.ts`
- Create: `C:\Users\CY\Desktop\CCoder\web\vitest.config.ts`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\App.tsx`
- Test: `C:\Users\CY\Desktop\CCoder\web\src\codec.test.ts`

**Interfaces:**
- Consumes: `shared/transcript-ops.json`（Task 3）
- Produces:
  - 类型 `TranscriptItem`（判别联合，`kind` 为判别键）、`TranscriptOp`
  - `function applyOps(state: TranscriptItem[], ops: TranscriptOp[]): TranscriptState`
  - `interface TranscriptState { items: TranscriptItem[]; live: { [target: string]: string } | null }`

- [ ] **Step 1: 创建 types.ts**

`web/src/types.ts`：

```ts
export type ItemKind =
  | 'user' | 'assistant' | 'thinking' | 'toolUse' | 'error' | 'result' | 'systemNote'

interface Base {
  id: string
  ts: number
}

export interface UserItem extends Base { kind: 'user'; text: string }
export interface AssistantItem extends Base { kind: 'assistant'; text: string }
export interface ThinkingItem extends Base { kind: 'thinking'; text: string }
export interface ErrorItem extends Base { kind: 'error'; text: string }
export interface SystemNoteItem extends Base { kind: 'systemNote'; text: string }
export interface ToolUseItem extends Base { kind: 'toolUse'; name: string; input: string }
export interface ResultItem extends Base {
  kind: 'result'
  subtype: string
  costUsd?: number
  durationMs?: number
}

export type TranscriptItem =
  | UserItem | AssistantItem | ThinkingItem
  | ToolUseItem | ErrorItem | ResultItem | SystemNoteItem

export type TranscriptOp =
  | { op: 'reset' }
  | { op: 'append'; item: TranscriptItem }
  | { op: 'appendDelta'; target: string; text: string }
  | { op: 'finalizeDelta'; target: string; text: string }
  | { op: 'clearDelta'; target: string }

export interface TranscriptState {
  items: TranscriptItem[]
  /** 进行中的流式气泡，按 target 分。当前只有 'assistant'。 */
  live: Record<string, string>
}

/**
 * Kotlin 侧注入的桥（见 Task 6 的 ClaudeTranscriptView）。
 *
 * 声明只放在这里一处 —— 多个文件各写一个 `declare global` 虽然能靠
 * 接口合并通过编译，但形状一旦不同就是静默的类型不一致。
 */
declare global {
  interface Window {
    ccoder?: {
      pushBatch?: (json: string) => void
      send?: (message: string) => void
    }
  }
}
```

- [ ] **Step 2: 创建 vitest.config.ts**

```ts
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
```

同时创建 `web/src/test-setup.ts`：

```ts
import '@testing-library/jest-dom/vitest'
```

- [ ] **Step 3: 写失败的测试**

`web/src/codec.test.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { applyOps, parseOps } from './codec'
import type { TranscriptState } from './types'

const FIXTURE = resolve(__dirname, '../../shared/transcript-ops.json')

function emptyState(): TranscriptState {
  return { items: [], live: {} }
}

describe('契约 fixture', () => {
  it('与 Kotlin 侧读取的是同一份文件', () => {
    // 路径为 ../shared/ 而不是 web/ 内部——这正是"共享"的意义
    expect(FIXTURE).toContain('shared')
    expect(() => readFileSync(FIXTURE, 'utf8')).not.toThrow()
  })

  it('能解析 fixture 中的全部操作', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    expect(ops).toHaveLength(12)
    expect(ops[0].op).toBe('reset')
    expect(ops[3].op).toBe('appendDelta')
  })
})

describe('applyOps', () => {
  it('reset 清空全部', () => {
    const state = applyOps(
      { items: [{ kind: 'user', id: 'x', ts: 1, text: '旧' }], live: { assistant: '半截' } },
      [{ op: 'reset' }],
    )
    expect(state.items).toHaveLength(0)
    expect(state.live).toEqual({})
  })

  it('append 追加消息', () => {
    const state = applyOps(emptyState(), [
      { op: 'append', item: { kind: 'user', id: 'm0', ts: 1, text: '你好' } },
    ])
    expect(state.items).toHaveLength(1)
    expect(state.items[0].kind).toBe('user')
  })

  it('appendDelta 累积到进行中的气泡，不产生新消息', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '你' }])
    state = applyOps(state, [{ op: 'appendDelta', target: 'assistant', text: '好' }])

    expect(state.items).toHaveLength(0)
    expect(state.live.assistant).toBe('你好')
  })

  it('finalizeDelta 以最终文本覆盖进行中的气泡并收尾', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '你' }])
    state = applyOps(state, [{ op: 'finalizeDelta', target: 'assistant', text: '你好呀' }])

    expect(state.live.assistant).toBeUndefined()
    expect(state.items).toHaveLength(1)
    expect(state.items[0]).toMatchObject({ kind: 'assistant', text: '你好呀' })
  })

  it('finalizeDelta 在无进行中气泡时也产生一条消息', () => {
    // 有些回合不产生 stream_event（例如极短的响应），
    // 此时 finalizeDelta 是唯一的文本来源，不能丢
    const state = applyOps(emptyState(), [
      { op: 'finalizeDelta', target: 'assistant', text: '短回复' },
    ])
    expect(state.items).toHaveLength(1)
    expect(state.items[0]).toMatchObject({ kind: 'assistant', text: '短回复' })
  })

  it('clearDelta 丢弃进行中的气泡', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '半截' }])
    state = applyOps(state, [{ op: 'clearDelta', target: 'assistant' }])

    expect(state.live.assistant).toBeUndefined()
    expect(state.items).toHaveLength(0)
  })

  it('批次内多个 op 按序生效', () => {
    const state = applyOps(emptyState(), [
      { op: 'appendDelta', target: 'assistant', text: '中间态' },
      { op: 'finalizeDelta', target: 'assistant', text: '最终' },
      { op: 'append', item: { kind: 'result', id: 'r', ts: 2, subtype: 'success' } },
    ])
    expect(state.items).toHaveLength(2)
    expect(state.items[0]).toMatchObject({ text: '最终' })
    expect(state.items[1].kind).toBe('result')
  })

  it('不修改传入的 state（保持不可变）', () => {
    const before = emptyState()
    const snapshot = JSON.stringify(before)
    applyOps(before, [{ op: 'append', item: { kind: 'user', id: 'x', ts: 1, text: 'a' } }])
    expect(JSON.stringify(before)).toBe(snapshot)
  })
})

describe('parseOps 的容错', () => {
  it('丢弃未知 op 而不是崩溃', () => {
    // 对应设计文档 §3.3 的"未知即忽略"——Kotlin 侧新增 op 时
    // 旧版本前端不该白屏
    const ops = parseOps([
      { op: 'reset' },
      { op: 'someFutureOp', payload: 1 },
    ] as unknown[])
    expect(ops).toHaveLength(1)
    expect(ops[0].op).toBe('reset')
  })

  it('丢弃结构不完整的 op', () => {
    const ops = parseOps([
      { op: 'append' },                                    // 缺 item
      { op: 'appendDelta', target: 'assistant' },          // 缺 text
      { op: 'clearDelta', target: 'assistant' },           // 合法
    ] as unknown[])
    expect(ops).toHaveLength(1)
  })

  it('丢弃未知 kind 的消息项', () => {
    const ops = parseOps([
      { op: 'append', item: { kind: 'someNewKind', id: 'x', ts: 1 } },
      { op: 'append', item: { kind: 'user', id: 'y', ts: 2, text: 'ok' } },
    ] as unknown[])
    expect(ops).toHaveLength(1)
  })

  it('非数组输入返回空数组', () => {
    expect(parseOps(null)).toEqual([])
    expect(parseOps({})).toEqual([])
  })
})
```

- [ ] **Step 4: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -12
```

预期：FAIL，`Failed to resolve import "./codec"`。

- [ ] **Step 5: 实现 codec.ts**

`web/src/codec.ts`：

```ts
import type { TranscriptItem, TranscriptOp, TranscriptState } from './types'

const KNOWN_KINDS = new Set([
  'user', 'assistant', 'thinking', 'toolUse', 'error', 'result', 'systemNote',
])

/**
 * 校验并解析来自 Kotlin 的操作批次。
 *
 * 未知的 op 与未知的消息 kind 一律丢弃而非抛错：Kotlin 侧新增类型时，
 * 已安装的旧版本前端不该白屏（对应设计文档 §3.3 的"未知即忽略"）。
 */
export function parseOps(raw: unknown): TranscriptOp[] {
  if (!Array.isArray(raw)) return []
  const out: TranscriptOp[] = []

  for (const entry of raw) {
    if (!entry || typeof entry !== 'object') continue
    const op = entry as Record<string, unknown>

    switch (op.op) {
      case 'reset':
        out.push({ op: 'reset' })
        break

      case 'append': {
        const item = parseItem(op.item)
        if (item) out.push({ op: 'append', item })
        break
      }

      case 'appendDelta':
      case 'finalizeDelta':
        if (typeof op.target === 'string' && typeof op.text === 'string') {
          out.push({ op: op.op, target: op.target, text: op.text })
        }
        break

      case 'clearDelta':
        if (typeof op.target === 'string') out.push({ op: 'clearDelta', target: op.target })
        break

      default:
        break   // 未知 op，丢弃
    }
  }
  return out
}

function parseItem(raw: unknown): TranscriptItem | null {
  if (!raw || typeof raw !== 'object') return null
  const it = raw as Record<string, unknown>

  if (typeof it.id !== 'string' || typeof it.ts !== 'number') return null
  if (typeof it.kind !== 'string' || !KNOWN_KINDS.has(it.kind)) return null

  const base = { id: it.id, ts: it.ts }

  switch (it.kind) {
    case 'user':
    case 'assistant':
    case 'thinking':
    case 'error':
    case 'systemNote':
      return typeof it.text === 'string' ? { ...base, kind: it.kind, text: it.text } : null

    case 'toolUse':
      return typeof it.name === 'string' && typeof it.input === 'string'
        ? { ...base, kind: 'toolUse', name: it.name, input: it.input }
        : null

    case 'result': {
      if (typeof it.subtype !== 'string') return null
      const item: TranscriptItem = { ...base, kind: 'result', subtype: it.subtype }
      // 可选字段：null 与 undefined 都不写入，保持对象形状精简
      if (typeof it.costUsd === 'number') item.costUsd = it.costUsd
      if (typeof it.durationMs === 'number') item.durationMs = it.durationMs
      return item
    }

    default:
      return null
  }
}

/**
 * 应用一批操作，返回新的 state。
 *
 * **不修改传入的 state** —— React 依赖引用变化来触发重渲染。
 */
export function applyOps(state: TranscriptState, ops: TranscriptOp[]): TranscriptState {
  if (ops.length === 0) return state

  let items = state.items
  let live = state.live
  let itemsCloned = false
  let liveCloned = false

  const cloneItems = () => { if (!itemsCloned) { items = items.slice(); itemsCloned = true } }
  const cloneLive = () => { if (!liveCloned) { live = { ...live }; liveCloned = true } }

  for (const op of ops) {
    switch (op.op) {
      case 'reset':
        items = []
        live = {}
        itemsCloned = true
        liveCloned = true
        break

      case 'append':
        cloneItems()
        items.push(op.item)
        break

      case 'appendDelta':
        cloneLive()
        live[op.target] = (live[op.target] ?? '') + op.text
        break

      case 'finalizeDelta':
        cloneLive()
        cloneItems()
        // 以最终文本为准——它可能包含增量之外的修正
        items.push({ kind: 'assistant', id: `final-${items.length}`, ts: Date.now(), text: op.text })
        delete live[op.target]
        break

      case 'clearDelta':
        cloneLive()
        delete live[op.target]
        break
    }
  }

  return { items, live }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -8
```

预期：全部通过（约 17 个用例）。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add web/src/types.ts web/src/codec.ts web/src/codec.test.ts web/src/test-setup.ts web/vitest.config.ts && git commit -F - <<'EOF'
feat(webui): React 侧消费转写操作

与 Kotlin 侧共用 shared/transcript-ops.json，契约测试两端各跑一遍。

未知 op 与未知消息 kind 一律丢弃而非抛错——Kotlin 侧新增类型时，
已安装的旧版本前端不该白屏（对应设计文档 §3.3 的"未知即忽略"）。

applyOps 保持不可变：React 依赖引用变化触发重渲染，
原地修改会导致界面不更新这类难查的问题。

finalizeDelta 在无进行中气泡时也产生消息——有些回合不产生 stream_event，
此时它是唯一的文本来源，不能丢。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: ThemeInjector —— 平台颜色与字体转 CSS 变量

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ThemeInjector.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ThemeInjectorTest.kt`

**Interfaces:**
- Consumes: 无（平台 API）
- Produces:
  - `data class TranscriptTheme(val css: String)`
  - `object ThemeInjector`，方法 `fun buildCss(colors: ThemeColors): String`
  - `data class ThemeColors(bg: Color, text: Color, textDim: Color, border: Color, accent: Color, surface: Color, codeBg: Color, errorBg: Color, fontUi: Font, fontMono: Font)`
  - `object PlatformTheme`，方法 `fun read(): ThemeColors`（生产路径，从平台 API 取值）

把「读平台 API」与「生成 CSS」分开，是为了让生成逻辑可测 —— 平台 API 在单测环境里拿不到真实主题。

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/ui/ThemeInjectorTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

class ThemeInjectorTest {

    private fun sample() = ThemeColors(
        bg = Color(0x1E1F22),
        text = Color(0xDCDCDC),
        textDim = Color(0x8C8C8C),
        border = Color(0x393B40),
        accent = Color(0x2F65CA),
        surface = Color(0x2B2D30),
        codeBg = Color(0x191A1C),
        errorBg = Color(0x4A1F1F),
        fontUi = Font("JetBrains Sans", Font.PLAIN, 13),
        fontMono = Font("JetBrains Mono", Font.PLAIN, 13),
    )

    @Test
    fun `颜色以十六进制输出`() {
        val css = ThemeInjector.buildCss(sample())
        assertTrue(css.contains("--bg: #1e1f22;"), "实际：$css")
        assertTrue(css.contains("--text: #dcdcdc;"))
    }

    @Test
    fun `所有约定的变量都存在`() {
        val css = ThemeInjector.buildCss(sample())
        for (name in listOf(
            "--bg", "--text", "--text-dim", "--border", "--accent",
            "--surface", "--code-bg", "--error-bg", "--font-ui", "--font-mono",
        )) {
            assertTrue(css.contains("$name:"), "缺少变量 $name")
        }
    }

    @Test
    fun `字体以 family size 输出且 family 带引号`() {
        val css = ThemeInjector.buildCss(sample())
        assertTrue(css.contains("""--font-ui: "JetBrains Sans", 13px;"""), "实际：$css")
        assertTrue(css.contains("""--font-mono: "JetBrains Mono", 13px;"""))
    }

    @Test
    fun `字体名含空格时仍被引号包裹`() {
        val colors = sample().copy(fontMono = Font("Fira Code", Font.PLAIN, 12))
        val css = ThemeInjector.buildCss(colors)
        assertTrue(css.contains("""--font-mono: "Fira Code", 12px;"""))
    }

    @Test
    fun `输出可被包进 style 标签`() {
        val css = ThemeInjector.buildCss(sample())
        assertTrue(css.contains(":root"), "应包含 :root 选择器")
        assertTrue(css.trimEnd().endsWith("}"), "应是完整的规则块")
    }

    @Test
    fun `十六进制补零`() {
        // Color(0x000102) 的十六进制必须是 000102 而不是 0102
        val colors = sample().copy(bg = Color(0x000102))
        val css = ThemeInjector.buildCss(colors)
        assertTrue(css.contains("--bg: #000102;"), "实际：$css")
    }

    @Test
    fun `相同输入产出相同输出`() {
        // 注入依赖内容稳定，否则每次主题刷新都会重写 DOM
        assertEquals(ThemeInjector.buildCss(sample()), ThemeInjector.buildCss(sample()))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ThemeInjectorTest*' --no-daemon 2>&1 | grep -E "(BUILD|e: )" | head -5
```

预期：FAIL，`Unresolved reference 'ThemeInjector'`。

- [ ] **Step 3: 实现 ThemeInjector.kt**

`src/main/kotlin/com/ccoder/ui/ThemeInjector.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

data class ThemeColors(
    val bg: Color,
    val text: Color,
    val textDim: Color,
    val border: Color,
    val accent: Color,
    val surface: Color,
    val codeBg: Color,
    val errorBg: Color,
    val fontUi: Font,
    val fontMono: Font,
)

/**
 * 把平台的颜色与字体转成 CSS 变量。
 *
 * JCEF 拿不到 IDE 的主题变量，所以由 Kotlin 侧读出后注入。这样明暗主题
 * 自动正确，而不是我们猜两套配色。
 *
 * 生成逻辑与"读平台 API"分开，是为了可测 —— 单测环境里拿不到真实主题。
 */
object ThemeInjector {

    fun buildCss(colors: ThemeColors): String = buildString {
        append(":root {\n")
        append("  --bg: ${colors.bg.hex()};\n")
        append("  --text: ${colors.text.hex()};\n")
        append("  --text-dim: ${colors.textDim.hex()};\n")
        append("  --border: ${colors.border.hex()};\n")
        append("  --accent: ${colors.accent.hex()};\n")
        append("  --surface: ${colors.surface.hex()};\n")
        append("  --code-bg: ${colors.codeBg.hex()};\n")
        append("  --error-bg: ${colors.errorBg.hex()};\n")
        append("  --font-ui: ${colors.fontUi.css()};\n")
        append("  --font-mono: ${colors.fontMono.css()};\n")
        append("}")
    }

    private fun Color.hex(): String = "#" + ColorUtil.toHex(this)

    private fun Font.css(): String = "\"$family\", ${size}px"

    /** 把 CSS 包成可直接 executeJavaScript 的赋值语句。 */
    fun buildInjectScript(colors: ThemeColors): String {
        val css = buildCss(colors)
            .replace("\\", "\\\\")
            .replace("\n", " ")
            .replace("'", "\\'")
        return "window.ccoderSetTheme && window.ccoderSetTheme('$css');"
    }
}

/**
 * 从平台 API 读取当前主题。生产路径，不做单测。
 */
object PlatformTheme {

    fun read(): ThemeColors {
        val bg = UIUtil.getPanelBackground()
        val text = UIUtil.getLabelForeground()
        return ThemeColors(
            bg = bg,
            text = text,
            textDim = UIUtil.getInactiveTextColor(),
            border = JBColor.border(),
            accent = UIUtil.getTreeSelectionBackground(true),
            surface = surfaceFor(bg),
            codeBg = UIUtil.getTextFieldBackground(),
            errorBg = mix(text, Color(0xD84315), 0.82),
            fontUi = UIUtil.getLabelFont(),
            fontMono = com.intellij.openapi.editor.colors.EditorColorsManager
                .getInstance().scheme.defaultEditorFont,
        )
    }

    /**
     * 气泡背景。优先用文本框背景色；若与面板背景过于接近，
     * 则按明暗方向加重/减淡，保证气泡有可见的边界。
     */
    private fun surfaceFor(bg: Color): Color {
        val candidate = UIUtil.getTextFieldBackground()
        if (ColorUtil.distance(candidate, bg) > SURFACE_MIN_DISTANCE) return candidate
        val darker = ColorUtil.isDark(bg)
        return if (darker) ColorUtil.brighter(bg, 1.08) else ColorUtil.darker(bg, 1.04)
    }

    /** 把 [fg] 以 [ratio] 的比例向 [base] 靠拢，得到低饱和变体。 */
    private fun mix(base: Color, fg: Color, ratio: Double): Color = Color(
        (base.red + (fg.red - base.red) * (1 - ratio)).toInt().coerceIn(0, 255),
        (base.green + (fg.green - base.green) * (1 - ratio)).toInt().coerceIn(0, 255),
        (base.blue + (fg.blue - base.blue) * (1 - ratio)).toInt().coerceIn(0, 255),
    )

    private const val SURFACE_MIN_DISTANCE = 12.0
}
```

> **实现提示**：`EditorColorsManager.getInstance().scheme` 需在主线程访问。若在后台线程调用 `PlatformTheme.read()` 会抛异常 —— Task 6 的调用点必须在 EDT 上。

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ThemeInjectorTest*' --no-daemon 2>&1 | grep -E "(BUILD|FAILED)" | head -3
```

预期：BUILD SUCCESSFUL，7 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ThemeInjector.kt src/test/kotlin/com/ccoder/ui/ThemeInjectorTest.kt && git commit -F - <<'EOF'
feat(theme): 平台颜色与字体转 CSS 变量

JCEF 拿不到 IDE 的主题变量，由 Kotlin 侧读出后注入。这样明暗主题自动正确，
而不是猜两套配色。不硬编码任何颜色。

生成逻辑与"读平台 API"分成两个对象，是为了让生成逻辑可测——
单测环境里拿不到真实主题，混在一起就什么都测不了。

气泡背景有可见性兜底：文本框背景色若与面板背景过近，按明暗方向
加重/减淡，避免气泡在部分主题下看不出边界。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: JCEF 转写区与节流推送

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\TranscriptPump.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudeTranscriptView.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\build.gradle.kts`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\resources\META-INF\plugin.xml`

**Interfaces:**
- Consumes: `TranscriptOp`/`TranscriptItem`（Task 3）、`TranscriptOpCodec`（Task 3）、`ThemeInjector`/`PlatformTheme`（Task 5）
- Produces:
  - `class TranscriptPump(private val exec: (String) -> Unit, throttleMs: Long = 16)`，方法 `enqueue(op)`、`flushNow()`、`dispose()`
  - `class ClaudeTranscriptView(project: Project) : JPanel, Disposable`，方法 `push(op: TranscriptOp)`、`setTheme()`

- [ ] **Step 1: 写失败的测试（只测可测的部分）**

`src/test/kotlin/com/ccoder/ui/TranscriptPumpTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 节流器与 JCEF 解耦，用注入的执行函数测试。
 * 真实浏览器不在单测范围内（那属于手工冒烟）。
 */
class TranscriptPumpTest {

    @Test
    fun `未 flush 前不执行`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.enqueue(TranscriptOp.Reset)
        assertEquals(0, executed.size, "节流期内不应推送")
        pump.dispose()
    }

    @Test
    fun `flushNow 推送已入队的操作`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.enqueue(TranscriptOp.Reset)
        pump.flushNow()

        assertEquals(1, executed.size)
        val array = JsonParser.parseString(executed[0]).asJsonArray
        assertEquals(1, array.size())
    }

    @Test
    fun `多个操作合并为一次推送`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.enqueue(TranscriptOp.AppendDelta("assistant", "你"))
        pump.enqueue(TranscriptOp.AppendDelta("assistant", "好"))
        pump.enqueue(TranscriptOp.AppendDelta("assistant", "呀"))
        pump.flushNow()

        assertEquals(1, executed.size, "三次入队必须压成一次跨边界调用")
        assertEquals(3, JsonParser.parseString(executed[0]).asJsonArray.size())
    }

    @Test
    fun `空队列时 flush 不产生调用`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.flushNow()
        assertEquals(0, executed.size, "空闲时不该产生跨边界调用")
    }

    @Test
    fun `flush 后队列清空`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.enqueue(TranscriptOp.Reset)
        pump.flushNow()
        pump.flushNow()
        assertEquals(1, executed.size)
    }

    @Test
    fun `dispose 后不再推送`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.dispose()
        pump.enqueue(TranscriptOp.Reset)
        pump.flushNow()
        assertEquals(0, executed.size)
    }

    @Test
    fun `并发入队不丢操作`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)

        val threads = (1..8).map { t ->
            Thread { repeat(100) { i -> pump.enqueue(TranscriptOp.AppendDelta("assistant", "$t-$i")) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        pump.flushNow()

        val total = executed.sumOf { JsonParser.parseString(it).asJsonArray.size() }
        assertEquals(800, total, "并发入队不能丢操作")
    }

    @Test
    fun `exec 抛错不影响后续推送`() {
        var failNext = true
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(
            exec = {
                if (failNext) { failNext = false; throw RuntimeException("桥断了") }
                executed += it
            },
            throttleMs = 10_000,
        )
        pump.enqueue(TranscriptOp.Reset)
        runCatching { pump.flushNow() }
        pump.enqueue(TranscriptOp.Reset)
        pump.flushNow()

        assertEquals(1, executed.size, "一次推送失败不该让节流器永久失效")
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*TranscriptPumpTest*' --no-daemon 2>&1 | grep -E "(BUILD|e: )" | head -5
```

预期：FAIL，`Unresolved reference 'TranscriptPump'`。

- [ ] **Step 3: 实现 TranscriptPump.kt**

`src/main/kotlin/com/ccoder/ui/TranscriptPump.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
import java.util.Timer
import java.util.TimerTask
import javax.swing.SwingUtilities

/**
 * 把转写操作按固定频率批量推送到 JCEF。
 *
 * 为什么必须节流：`includePartialMessages: true` 会让逐 token 增量以极高频率
 * 到达，而每一次 executeJavaScript 都要跨 CEF 进程边界。每个增量推一次会明显
 * 卡顿 —— 这是"流畅"目标的前提条件，不是优化。
 *
 * 16ms 约合 60fps，与显示器刷新率对齐。
 */
class TranscriptPump(
    private val exec: (String) -> Unit,
    throttleMs: Long = 16,
) {
    private val lock = Any()
    private val buffer = mutableListOf<TranscriptOp>()

    @Volatile
    private var disposed = false

    private val timer = Timer("ccoder-transcript-pump", true).apply {
        scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // exec 会触碰 JCEF/Swing，必须回到 EDT
                SwingUtilities.invokeLater { runCatching { flushNow() } }
            }
        }, throttleMs, throttleMs)
    }

    fun enqueue(op: TranscriptOp) {
        if (disposed) return
        synchronized(lock) { buffer.add(op) }
    }

    /** 立即推送。测试与"关掉节流"场景用。 */
    fun flushNow() {
        if (disposed) return
        val batch = synchronized(lock) {
            if (buffer.isEmpty()) return
            buffer.toList().also { buffer.clear() }
        }
        // 失败只吞掉本次：桥可能临时不可用，不该让节流器永久失效
        runCatching { exec(TranscriptOpCodec.encodeBatch(batch)) }
    }

    fun dispose() {
        disposed = true
        timer.cancel()
        synchronized(lock) { buffer.clear() }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*TranscriptPumpTest*' --no-daemon 2>&1 | grep -E "(BUILD|FAILED)" | head -3
```

预期：BUILD SUCCESSFUL，8 个测试通过。

- [ ] **Step 5: 加入 JCEF 依赖**

`build.gradle.kts` 的 `dependencies.intellijPlatform` 块改为：

```kotlin
    intellijPlatform {
        pycharm("2025.3.1.1")
        bundledPlugin("intellij.platform.ui.jcef")
    }
```

`plugin.xml` 在 `<depends>com.intellij.modules.platform</depends>` 之后加：

```xml
    <depends>com.intellij.modules.jcef</depends>
```

- [ ] **Step 6: 实现 ClaudeTranscriptView.kt**

`src/main/kotlin/com/ccoder/ui/ClaudeTranscriptView.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 转写区。JCEF 可用时渲染 React 界面，不可用时回退到一段说明文字。
 *
 * 回退不重建原生渲染：原生渲染已在 Task 12 从 ClaudePanel 移除，
 * 保留两套渲染实现会让长期维护面翻倍（设计文档 §11 的取舍）。
 * 降级到 JCEF 不可用时给出明确提示，而不是空白面板。
 */
class ClaudeTranscriptView(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?
    private val pump: TranscriptPump?

    /** React 挂载完成前收到的操作要暂存，否则会丢。 */
    private val beforeReady = mutableListOf<TranscriptOp>()

    @Volatile
    private var ready = false

    init {
        if (!JBCefApp.isSupported()) {
            browser = null
            jsQuery = null
            pump = null
            add(
                JLabel(
                    "<html><body style='padding:16px'>当前 IDE 未启用 JCEF 嵌入浏览器，CCoder 无法显示对话。<br><br>" +
                        "请在 Help → Find Action 中检查 'Registry' 里的 ide.browser.jcef.enabled 设置。</body></html>",
                    SwingConstants.LEFT,
                ),
                BorderLayout.CENTER,
            )
        } else {
            val b = JBCefBrowser()
            browser = b
            pump = TranscriptPump(exec = { json -> pushToJs(json) })

            val query = JBCefJSQuery.create(b as JBCefBrowserBase)
            jsQuery = query

            query.addHandler { message: String ->
                handleFromJs(message)
                null
            }

            add(b.component, BorderLayout.CENTER)

            // 必须在 React 挂载后注入桥，否则 window.ccoder 还不存在
            b.cefBrowser.executeJavaScript(
                """
                window.ccoder = window.ccoder || {};
                window.ccoder.send = function(m) { ${query.inject("m")} };
                window.ccoderSetTheme = function(css) {
                  var el = document.getElementById('ccoder-theme');
                  if (!el) {
                    el = document.createElement('style');
                    el.id = 'ccoder-theme';
                    document.head.appendChild(el);
                  }
                  el.textContent = css;
                };
                """.trimIndent(),
                b.cefBrowser.url,
                0,
            )

            // 兜底：任何外部 http(s) 导航都拦下来交给系统浏览器。
            // 即使 React 侧漏了一处 <a>，也不会把整个界面导航掉。
            //
            // 注意用 CefRequestHandler.onBeforeBrowse 而不是 CefLoadHandler ——
            // 后者只在加载开始**之后**的通知，拦不住导航。
            b.jcefClient.cefClient.addRequestHandler(
                object : CefRequestHandlerAdapter() {
                    override fun onBeforeBrowse(
                        browser: CefBrowser?,
                        frame: CefFrame?,
                        request: CefRequest?,
                        userGesture: Boolean,
                        isRedirect: Boolean,
                    ): Boolean {
                        val url = request?.url ?: return false
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            return false   // about:blank 等内部 URL 放行
                        }
                        val devServer = System.getProperty("ccoder.devServer")
                        if (devServer != null && url.startsWith(devServer)) {
                            return false   // 开发模式下允许 dev server 自身的导航
                        }
                        com.intellij.ide.BrowserUtil.browse(url)
                        return true        // 取消导航
                    }
                },
                b.cefBrowser,
            )

            loadUi(b)
        }
    }

    private fun loadUi(b: JBCefBrowser) {
        val devServer = System.getProperty("ccoder.devServer")
        if (!devServer.isNullOrBlank()) {
            // 开发模式：指向 Vite dev server 拿热更新，改 React 代码不用重启 IDE
            b.loadURL(devServer)
        } else {
            val html = javaClass.getResourceAsStream("/webui/index.html")
                ?.bufferedReader()?.use { it.readText() }
            if (html == null) {
                b.loadHTML("<html><body style='padding:16px'>插件资源缺失：webui/index.html</body></html>")
            } else {
                b.loadHTML(html)
            }
        }
    }

    fun push(op: TranscriptOp) {
        if (op is TranscriptOp.Reset) beforeReady.clear()
        if (!ready) {
            beforeReady.add(op)
            return
        }
        pump?.enqueue(op)
    }

    /** 重新注入主题。在主题切换时由 ClaudePanel 调用，必须在 EDT 上。 */
    fun setTheme() {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(
            ThemeInjector.buildInjectScript(PlatformTheme.read()),
            b.cefBrowser.url,
            0,
        )
    }

    private fun pushToJs(json: String) {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript("window.ccoder.pushBatch($json);", b.cefBrowser.url, 0)
    }

    private fun handleFromJs(message: String) {
        val obj = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return
        when (obj.str("op")) {
            "ready" -> {
                ready = true
                // 主题必须在 React 挂载后立刻注入，否则会有一帧无样式内容
                setTheme()
                beforeReady.forEach { pump?.enqueue(it) }
                beforeReady.clear()
                pump?.flushNow()
            }

            "openLink" -> obj.str("url")?.let { url ->
                com.intellij.ide.BrowserUtil.browse(url)
            }
        }
    }

    override fun dispose() {
        pump?.dispose()
        jsQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
```

> **注意**：`setTheme()` 必须在 EDT 上调用（`EditorColorsManager` 不是线程安全的）。`ClaudePanel` 在 `invokeLater` 里调用它。

- [ ] **Step 7: 编译验证**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew compileKotlin --no-daemon 2>&1 | grep -E "(BUILD|e: )" | head -8
```

预期：BUILD SUCCESSFUL。

若报 `Unresolved reference 'jcef'` 或 `JBCefApp`，说明 `bundledPlugin` 或 `<depends>` 没生效 —— 检查 Step 5 的改动，不要靠猜。

- [ ] **Step 8: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ClaudeTranscriptView.kt src/main/kotlin/com/ccoder/ui/TranscriptPump.kt src/test/kotlin/com/ccoder/ui/TranscriptPumpTest.kt build.gradle.kts src/main/resources/META-INF/plugin.xml && git commit -F - <<'EOF'
feat(transcript): JCEF 转写区与 16ms 节流推送

节流是"流畅"的前提而非优化：includePartialMessages 的增量频率极高，
每个增量跨一次 CEF 边界会明显卡顿。批量推送把 N 次调用压成 1 次。
节流器与 JCEF 解耦（注入 exec），因此可单测——含并发入队不丢操作、
exec 抛错不让节流器永久失效两组用例。

React 挂载完成前收到的操作会暂存：JCEF 加载是异步的，
loadHTML 之后到 React 首次渲染之间有窗口期，期间推送会丢。

主题在收到 ready 后立刻注入，否则会闪一帧无样式内容。

JCEF 不可用时给出明确提示而非空白面板。回退不重建原生渲染——
保留两套实现会让长期维护面翻倍，这是设计文档 §11 明确记录的取舍。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: React 消息组件与样式

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\web\src\styles.css`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\Transcript.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\UserBubble.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\AssistantBubble.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\ThinkingBlock.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\ToolCallBlock.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\ErrorBubble.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\ResultLine.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\SystemNote.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\Collapsible.tsx`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\App.tsx`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\main.tsx`
- Test: `C:\Users\CY\Desktop\CCoder\web\src\components\Transcript.test.tsx`

**Interfaces:**
- Consumes: `TranscriptItem`、`TranscriptState`（Task 4）
- Produces:
  - `<Transcript state={TranscriptState} />`
  - `<Collapsible title={string} defaultOpen={boolean}>{children}</Collapsible>`
  - `window.ccoder.pushBatch(json: string)` 与 `window.ccoderSetTheme(css: string)` 由 Task 6 注入

- [ ] **Step 1: 写失败的测试**

`web/src/components/Transcript.test.tsx`：

```tsx
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Transcript } from './Transcript'
import type { TranscriptItem } from '../types'

function state(...items: TranscriptItem[]) {
  return { items, live: {} }
}

const ts = 1726050000000

describe('Transcript', () => {
  it('空转写区不报错', () => {
    render(<Transcript state={state()} />)
    expect(screen.getByTestId('transcript')).toBeInTheDocument()
  })

  it('渲染用户消息', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    expect(screen.getByText('你好')).toBeInTheDocument()
  })

  it('渲染 Claude 消息', () => {
    render(<Transcript state={state({ kind: 'assistant', id: 'a', ts, text: '在的' })} />)
    expect(screen.getByText('在的')).toBeInTheDocument()
  })

  it('渲染错误消息', () => {
    render(<Transcript state={state({ kind: 'error', id: 'e', ts, text: '认证失败' })} />)
    expect(screen.getByText('认证失败')).toBeInTheDocument()
    expect(screen.getByTestId('error-bubble')).toBeInTheDocument()
  })

  it('渲染系统提示', () => {
    render(<Transcript state={state({ kind: 'systemNote', id: 's', ts, text: '会话已就绪' })} />)
    expect(screen.getByText('会话已就绪')).toBeInTheDocument()
  })

  it('result 显示成本与耗时', () => {
    render(
      <Transcript
        state={state({ kind: 'result', id: 'r', ts, subtype: 'success', costUsd: 0.1008, durationMs: 1681 })}
      />,
    )
    expect(screen.getByText(/\$0\.1008/)).toBeInTheDocument()
    expect(screen.getByText(/1681ms/)).toBeInTheDocument()
  })

  it('result 缺省可选字段时不显示它们', () => {
    render(<Transcript state={state({ kind: 'result', id: 'r', ts, subtype: 'success' })} />)
    expect(screen.getByText('success')).toBeInTheDocument()
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
  })

  it('每条消息显示时间戳', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    // 时间戳按本地时区格式化，只断言格式而非具体值
    expect(screen.getByTestId('timestamp')).toHaveTextContent(/^\d{2}:\d{2}$/)
  })

  it('思考块默认折叠，点击后展开', async () => {
    const user = userEvent.setup()
    render(<Transcript state={state({ kind: 'thinking', id: 't', ts, text: '让我想想' })} />)

    expect(screen.queryByText('让我想想')).not.toBeInTheDocument()
    await user.click(screen.getByText(/思考过程/))
    expect(screen.getByText('让我想想')).toBeInTheDocument()
  })

  it('工具调用默认折叠，展开显示参数', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({ kind: 'toolUse', id: 'x', ts, name: 'Read', input: '{"file_path":"/a.txt"}' })}
      />,
    )

    expect(screen.getByText(/Read/)).toBeInTheDocument()
    expect(screen.queryByText(/file_path/)).not.toBeInTheDocument()
    await user.click(screen.getByText(/Read/))
    expect(screen.getByText(/file_path/)).toBeInTheDocument()
  })

  it('进行中的气泡以流式形式渲染', () => {
    render(<Transcript state={{ items: [], live: { assistant: '正在输入' } }} />)
    expect(screen.getByText('正在输入')).toBeInTheDocument()
    expect(screen.getByTestId('streaming-cursor')).toBeInTheDocument()
  })

  it('未知 kind 不导致崩溃', () => {
    // 对应设计文档 §3.3：Kotlin 侧新增类型时旧前端不该白屏
    const bogus = { kind: 'someNewKind', id: 'z', ts } as unknown as TranscriptItem
    expect(() => render(<Transcript state={state(bogus)} />)).not.toThrow()
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -8
```

预期：FAIL，`Failed to resolve import "./Transcript"`。

- [ ] **Step 3: 实现 Collapsible.tsx**

`web/src/components/Collapsible.tsx`：

```tsx
import { useState, type ReactNode } from 'react'

interface Props {
  title: string
  defaultOpen?: boolean
  dim?: boolean
  children: ReactNode
}

/** 真折叠组件。第一版里的是个假的——只加了 ▸ 前缀，点了没反应。 */
export function Collapsible({ title, defaultOpen = false, dim = false, children }: Props) {
  const [open, setOpen] = useState(defaultOpen)

  return (
    <div className={`collapsible${dim ? ' collapsible--dim' : ''}`}>
      <button
        type="button"
        className="collapsible__head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`collapsible__chevron${open ? ' is-open' : ''}`}>▸</span>
        <span className="collapsible__title">{title}</span>
      </button>
      {open && <div className="collapsible__body">{children}</div>}
    </div>
  )
}
```

- [ ] **Step 4: 实现各消息组件**

`web/src/components/UserBubble.tsx`：

```tsx
export function UserBubble({ text }: { text: string }) {
  return (
    <div className="row row--user" data-testid="user-bubble">
      <div className="bubble bubble--user">
        <div className="bubble__text">{text}</div>
      </div>
    </div>
  )
}
```

`web/src/components/AssistantBubble.tsx`：

```tsx
import type { ReactNode } from 'react'

export function AssistantBubble({ children }: { children: ReactNode }) {
  return (
    <div className="row row--assistant" data-testid="assistant-bubble">
      <div className="bubble bubble--assistant">
        <div className="bubble__text">{children}</div>
      </div>
    </div>
  )
}
```

`web/src/components/ThinkingBlock.tsx`：

```tsx
import { Collapsible } from './Collapsible'

export function ThinkingBlock({ text }: { text: string }) {
  return (
    <Collapsible title="思考过程" dim>
      <div className="thinking-text">{text}</div>
    </Collapsible>
  )
}
```

`web/src/components/ToolCallBlock.tsx`：

```tsx
import { Collapsible } from './Collapsible'

export function ToolCallBlock({ name, input }: { name: string; input: string }) {
  let pretty = input
  try {
    pretty = JSON.stringify(JSON.parse(input), null, 2)
  } catch {
    // 不是合法 JSON 就按原文显示
  }
  return (
    <Collapsible title={`工具：${name}`}>
      <pre className="tool-input">{pretty}</pre>
    </Collapsible>
  )
}
```

`web/src/components/ErrorBubble.tsx`：

```tsx
export function ErrorBubble({ text }: { text: string }) {
  return (
    <div className="row row--assistant" data-testid="error-bubble">
      <div className="bubble bubble--error">
        <div className="bubble__text">{text}</div>
      </div>
    </div>
  )
}
```

`web/src/components/ResultLine.tsx`：

```tsx
export function ResultLine({
  subtype,
  costUsd,
  durationMs,
}: {
  subtype: string
  costUsd?: number
  durationMs?: number
}) {
  const parts = [subtype]
  if (typeof costUsd === 'number') parts.push(`$${costUsd.toFixed(4)}`)
  if (typeof durationMs === 'number') parts.push(`${durationMs}ms`)

  return <div className="result-line">{parts.join(' · ')}</div>
}
```

`web/src/components/SystemNote.tsx`：

```tsx
export function SystemNote({ text }: { text: string }) {
  return <div className="system-note">{text}</div>
}
```

- [ ] **Step 5: 实现 Transcript.tsx**

```tsx
import type { TranscriptItem, TranscriptState } from '../types'
import { AssistantBubble } from './AssistantBubble'
import { ErrorBubble } from './ErrorBubble'
import { ResultLine } from './ResultLine'
import { SystemNote } from './SystemNote'
import { ThinkingBlock } from './ThinkingBlock'
import { ToolCallBlock } from './ToolCallBlock'
import { UserBubble } from './UserBubble'
import { StreamingCursor } from './StreamingCursor'

function Timestamp({ ts }: { ts: number }) {
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return (
    <time className="timestamp" data-testid="timestamp" title={d.toLocaleString()}>
      {hh}:{mm}
    </time>
  )
}

function Item({ item }: { item: TranscriptItem }) {
  switch (item.kind) {
    case 'user':
      return (
        <div className="entry">
          <UserBubble text={item.text} />
          <Timestamp ts={item.ts} />
        </div>
      )
    case 'assistant':
      return (
        <div className="entry">
          <AssistantBubble>{item.text}</AssistantBubble>
          <Timestamp ts={item.ts} />
        </div>
      )
    case 'error':
      return (
        <div className="entry">
          <ErrorBubble text={item.text} />
          <Timestamp ts={item.ts} />
        </div>
      )
    case 'thinking':
      return <ThinkingBlock text={item.text} />
    case 'toolUse':
      return <ToolCallBlock name={item.name} input={item.input} />
    case 'systemNote':
      return <SystemNote text={item.text} />
    case 'result':
      return <ResultLine subtype={item.subtype} costUsd={item.costUsd} durationMs={item.durationMs} />
    default:
      // 未知 kind：静默忽略（设计文档 §3.3）。parseItem 已过滤，
      // 这里兜住直接构造 state 的情况。
      return null
  }
}

export function Transcript({ state }: { state: TranscriptState }) {
  const liveText = state.live.assistant

  return (
    <div className="transcript" data-testid="transcript">
      {state.items.map((item) => (
        <Item key={item.id} item={item} />
      ))}
      {liveText !== undefined && (
        <div className="entry">
          <AssistantBubble>
            {liveText}
            <StreamingCursor />
          </AssistantBubble>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 6: 实现 StreamingCursor.tsx**

`web/src/components/StreamingCursor.tsx`：

```tsx
export function StreamingCursor() {
  return <span className="streaming-cursor" data-testid="streaming-cursor" aria-hidden="true" />
}
```

- [ ] **Step 7: 实现 App.tsx 与 main.tsx**

`web/src/App.tsx`：

```tsx
import { useEffect, useState } from 'react'
import { Transcript } from './components/Transcript'
import { applyOps, parseOps } from './codec'
// window.ccoder 的全局声明在 types.ts，此处不重复声明
import type { TranscriptState } from './types'

export function App() {
  const [state, setState] = useState<TranscriptState>({ items: [], live: {} })

  useEffect(() => {
    window.ccoder = window.ccoder || {}
    window.ccoder.pushBatch = (json: string) => {
      try {
        const ops = parseOps(JSON.parse(json))
        // 用函数式更新：节流器可能在一帧内推多批，直接读 state 会丢更新
        setState((prev) => applyOps(prev, ops))
      } catch {
        // 畸形批次忽略，不让界面白屏
      }
    }
    // 通知 Kotlin 侧可以开始推送了。必须放在 pushBatch 赋值之后——
    // 否则 Kotlin 收到 ready 立刻推送时会打到 undefined 上。
    window.ccoder.send?.(JSON.stringify({ op: 'ready' }))
  }, [])

  return <Transcript state={state} />
}
```

`web/src/main.tsx`：

```tsx
import React from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles.css'

const root = document.getElementById('root')
if (root) createRoot(root).render(<React.StrictMode><App /></React.StrictMode>)
```

- [ ] **Step 8: 实现 styles.css**

`web/src/styles.css` —— 视觉规格来自设计文档 §4：

```css
*,
*::before,
*::after { box-sizing: border-box; }

html, body, #root {
  margin: 0;
  padding: 0;
  height: 100%;
  overflow: hidden;
}

body {
  background: var(--bg);
  color: var(--text);
  font-family: var(--font-ui);
  font-size: 13px;
  line-height: 1.55;
}

.transcript {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  scroll-behavior: smooth;
}

/* ---- 气泡 ---- */

.row { display: flex; }
.row--user { justify-content: flex-end; }
.row--assistant { justify-content: flex-start; }

.entry { display: flex; flex-direction: column; }
.entry .timestamp { margin-top: 2px; }
.row--user + .timestamp { align-self: flex-end; }

.bubble {
  border-radius: 12px;
  padding: 10px 14px;
  max-width: 92%;
  overflow-wrap: anywhere;
}

.bubble--user {
  background: var(--accent);
  color: #fff;
  max-width: 85%;
}

.bubble--assistant {
  background: var(--surface);
  border: 1px solid var(--border);
}

.bubble--error {
  background: var(--error-bg);
  border: 1px solid var(--border);
}

.bubble__text { white-space: pre-wrap; }

.timestamp {
  font-size: 11px;
  color: var(--text-dim);
  padding: 0 4px;
}

/* ---- 折叠块 ---- */

.collapsible { border-left: 2px solid var(--border); padding-left: 8px; }
.collapsible--dim { opacity: 0.75; }

.collapsible__head {
  background: none;
  border: none;
  padding: 2px 0;
  color: var(--text-dim);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  text-align: left;
}

.collapsible__head:hover { color: var(--text); }

.collapsible__chevron {
  display: inline-block;
  transition: transform 160ms ease;
}
.collapsible__chevron.is-open { transform: rotate(90deg); }

.collapsible__body {
  padding: 4px 0 4px 14px;
  animation: fade-in 160ms ease-out;
}

.thinking-text { color: var(--text-dim); font-style: italic; white-space: pre-wrap; }

.tool-input {
  margin: 0;
  padding: 8px;
  background: var(--code-bg);
  border-radius: 6px;
  font-family: var(--font-mono);
  font-size: 12px;
  overflow-x: auto;
  white-space: pre;
}

/* ---- 其他 ---- */

.system-note,
.result-line {
  text-align: center;
  font-size: 11px;
  color: var(--text-dim);
  padding: 2px 0;
  user-select: text;
}

@keyframes fade-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}

.streaming-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--text);
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

- [ ] **Step 9: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -10
```

预期：全部通过。

- [ ] **Step 10: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add web/src/ && git commit -F - <<'EOF'
feat(webui): 消息组件与视觉系统

Collapsible 是真折叠组件——第一版里的是假的，只加了 ▸ 前缀，点了没反应。

气泡规格来自设计文档 §4.2：用户右对齐 85%、Claude 左对齐 92%、
圆角 12px、内边距 10/14。颜色全部走 CSS 变量，不硬编码。

主题变量由 Kotlin 侧注入（Task 5），因此明暗主题自动正确。

respects prefers-reduced-motion：系统设为减少动效时全部关闭。

App 用函数式 setState：节流器可能在一帧内推多批，直接读 state 会丢更新。

pushBatch 赋值与 ready 通知的顺序是刻意的——反过来会让 Kotlin 的
首批推送打到 undefined 上。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 8: 代码块 —— 语法高亮、复制、换行开关

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\web\src\highlight.ts`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\CodeBlock.tsx`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\styles.css`
- Test: `C:\Users\CY\Desktop\CCoder\web\src\components\CodeBlock.test.tsx`

**Interfaces:**
- Consumes: 无（`marked` 的 renderer 钩子在 Task 9 接）
- Produces:
  - `function highlightCode(code: string, lang: string): string` —— 返回高亮后的 HTML
  - `<CodeBlock code={string} lang={string} />`

- [ ] **Step 1: 写失败的测试**

`web/src/components/CodeBlock.test.tsx`：

```tsx
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CodeBlock } from './CodeBlock'

beforeEach(() => {
  localStorage.clear()
  Object.assign(navigator, {
    clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

describe('CodeBlock', () => {
  it('渲染代码内容', () => {
    render(<CodeBlock code="print('hi')" lang="python" />)
    expect(screen.getByText(/print/)).toBeInTheDocument()
  })

  it('显示语言标签', () => {
    render(<CodeBlock code="x = 1" lang="python" />)
    expect(screen.getByText('python')).toBeInTheDocument()
  })

  it('语言未知时不显示标签', () => {
    render(<CodeBlock code="x = 1" lang="" />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.queryByTestId('code-lang')).not.toBeInTheDocument()
  })

  it('默认不换行（保护缩进）', () => {
    render(<CodeBlock code={'def f():\n    return 1'} lang="python" />)
    expect(screen.getByTestId('code-block')).not.toHaveClass('is-wrapped')
  })

  it('点击复制把代码写入剪贴板', async () => {
    const user = userEvent.setup()
    render(<CodeBlock code="print('hi')" lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("print('hi')")
  })

  it('复制后按钮文案变为已复制', async () => {
    const user = userEvent.setup()
    render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))
    expect(screen.getByRole('button', { name: /已复制/ })).toBeInTheDocument()
  })

  it('换行开关切换 is-wrapped 类', async () => {
    const user = userEvent.setup()
    render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /自动换行/ }))
    expect(screen.getByTestId('code-block')).toHaveClass('is-wrapped')
  })

  it('换行开关状态持久化到 localStorage', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /自动换行/ }))
    unmount()

    render(<CodeBlock code="y" lang="python" />)
    expect(screen.getByTestId('code-block')).toHaveClass('is-wrapped')
  })

  it('未识别的语言不高亮但内容完整', () => {
    render(<CodeBlock code="◊◊◊" lang="notalang" />)
    expect(screen.getByText('◊◊◊')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -6
```

预期：FAIL，`Failed to resolve import "./CodeBlock"`。

- [ ] **Step 3: 实现 highlight.ts**

`web/src/highlight.ts`：

```ts
import hljs from 'highlight.js/lib/core'

// 按需注册：全量 highlight.js 约 1MB，这里只引常用的十余种
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import go from 'highlight.js/lib/languages/go'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('go', go)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('kotlin', kotlin)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('python', python)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

/** 常见别名 → 注册名 */
const ALIASES: Record<string, string> = {
  js: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  jsx: 'javascript',
  py: 'python',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  html: 'xml',
  kt: 'kotlin',
  rs: 'rust',
  golang: 'go',
}

/**
 * 高亮代码。语言未注册时返回转义后的原文——**绝不抛错**，
 * 因为代码块内容来自模型输出，什么语言标记都可能出现。
 */
export function highlightCode(code: string, lang: string): string {
  const normalized = ALIASES[lang.toLowerCase()] ?? lang.toLowerCase()

  if (normalized && hljs.getLanguage(normalized)) {
    try {
      return hljs.highlight(code, { language: normalized, ignoreIllegals: true }).value
    } catch {
      // 落到转义原文
    }
  }
  return escapeHtml(code)
}

export function isKnownLanguage(lang: string): boolean {
  const normalized = ALIASES[lang.toLowerCase()] ?? lang.toLowerCase()
  return Boolean(normalized) && Boolean(hljs.getLanguage(normalized))
}

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}
```

- [ ] **Step 4: 实现 CodeBlock.tsx**

`web/src/components/CodeBlock.tsx`：

```tsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import { highlightCode, isKnownLanguage } from '../highlight'

const WRAP_KEY = 'ccoder.codeWrap'

/** 换行开关是全局偏好而非每块独立——用户要么都换行，要么都不换。 */
function readWrapPreference(): boolean {
  try {
    return localStorage.getItem(WRAP_KEY) === '1'
  } catch {
    return false
  }
}

export function CodeBlock({ code, lang }: { code: string; lang: string }) {
  const [wrap, setWrap] = useState(readWrapPreference)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    try {
      localStorage.setItem(WRAP_KEY, wrap ? '1' : '0')
    } catch {
      // 隐私模式下 localStorage 可能不可用，忽略即可
    }
  }, [wrap])

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(t)
  }, [copied])

  const html = useMemo(() => highlightCode(code, lang), [code, lang])
  const showLang = isKnownLanguage(lang) || lang.trim().length > 0

  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
    } catch {
      // 剪贴板不可用时静默失败，不打断阅读
    }
  }, [code])

  return (
    <div
      className={`code-block${wrap ? ' is-wrapped' : ''}`}
      data-testid="code-block"
    >
      <div className="code-block__bar">
        {showLang && (
          <span className="code-block__lang" data-testid="code-lang">
            {lang}
          </span>
        )}
        <span className="code-block__spacer" />
        <button
          type="button"
          className="code-block__action"
          onClick={() => setWrap((v) => !v)}
          aria-pressed={wrap}
          title={wrap ? '关闭自动换行' : '开启自动换行'}
        >
          自动换行
        </button>
        <button
          type="button"
          className="code-block__action"
          onClick={copy}
          title="复制代码"
        >
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      {/* 高亮结果是 highlight.js 生成的转义 HTML，非用户输入 */}
      <pre className="code-block__pre">
        <code
          className="code-block__code hljs"
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </pre>
    </div>
  )
}
```

- [ ] **Step 5: 追加样式到 styles.css**

```css
/* ---- 代码块 ---- */
/* 留在气泡内但贴边：自身不带左右内边距，直接吃到气泡的 14px 边缘。
   设计文档 §4.3 记录了这个取舍——初稿曾提议"突破内边距全宽"，
   但实测只有约 16% 宽度损失，不足以论证那个会让代码块脱离气泡的方案。 */

.code-block {
  margin: 8px 0;
  background: var(--code-bg);
  border-radius: 8px;
  overflow: hidden;
}

.code-block__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  font-size: 11px;
  color: var(--text-dim);
}

.code-block__spacer { flex: 1; }

.code-block__lang { font-family: var(--font-mono); text-transform: lowercase; }

.code-block__action {
  background: none;
  border: none;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--text-dim);
  font: inherit;
  cursor: pointer;
}
.code-block__action:hover { background: var(--bg); color: var(--text); }

.code-block__pre {
  margin: 0;
  padding: 10px 12px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
}

.code-block__code { white-space: pre; }

/* 默认不换行以保护缩进：换行会让缩进四层的语句续行顶到最左边，
   Python / YAML 这类"缩进即语义"的代码会变得不可读。
   用户可通过上方开关显式切换。 */
.code-block.is-wrapped .code-block__pre { overflow-x: hidden; }
.code-block.is-wrapped .code-block__code { white-space: pre-wrap; overflow-wrap: anywhere; }
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -8
```

预期：全部通过。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add web/src/highlight.ts web/src/components/CodeBlock.tsx web/src/components/CodeBlock.test.tsx web/src/styles.css && git commit -F - <<'EOF'
feat(webui): 代码块——语法高亮、复制、自动换行开关

highlight.js 按需注册 14 种语言：全量约 1MB，按需后约 100KB。

代码块留在气泡内但贴边（自身无左右内边距）。设计文档 §4.3 记录了这个
取舍：初稿曾提议"突破气泡内边距全宽"，实测只有约 16% 宽度损失，
不足以论证那个会让代码块视觉上脱离气泡的方案。

默认不换行以保护缩进——换行会让缩进四层的语句续行顶到最左边，
Python / YAML 这类"缩进即语义"的代码会变得不可读。用户可显式切换，
偏好全局共享并持久化。

高亮失败时回退到转义原文而非抛错：代码内容来自模型输出，
什么语言标记都可能出现。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 9: Markdown 渲染与流式光标

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\web\src\components\Markdown.tsx`
- Create: `C:\Users\CY\Desktop\CCoder\web\src\bridge.ts`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\components\Transcript.tsx`
- Modify: `C:\Users\CY\Desktop\CCoder\web\src\styles.css`
- Test: `C:\Users\CY\Desktop\CCoder\web\src\components\Markdown.test.tsx`

**Interfaces:**
- Consumes: `CodeBlock`（Task 8）
- Produces:
  - `<Markdown text={string} />`
  - `bridge.ts` 导出 `openLink(url: string): void`

- [ ] **Step 1: 实现 bridge.ts（先建，供测试 mock）**

`web/src/bridge.ts`：

```ts
/**
 * JS → Kotlin 的通道。Task 6 在页面里注入 window.ccoder.send。
 *
 * 打开链接走这里而不是 window.open：JCEF 里的页面导航会把整个应用
 * 页面替换掉。Kotlin 侧用 BrowserUtil.browse 交给系统浏览器。
 */
export function openLink(url: string): void {
  window.ccoder?.send?.(JSON.stringify({ op: 'openLink', url }))
}
```

- [ ] **Step 2: 写失败的测试**

`web/src/components/Markdown.test.tsx`：

```tsx
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Markdown } from './Markdown'
import { openLink } from '../bridge'

vi.mock('../bridge', () => ({ openLink: vi.fn() }))

beforeEach(() => vi.clearAllMocks())

describe('Markdown', () => {
  it('渲染段落', () => {
    render(<Markdown text="普通文本" />)
    expect(screen.getByText('普通文本')).toBeInTheDocument()
  })

  it('渲染行内代码', () => {
    render(<Markdown text="调用 `foo()` 即可" />)
    expect(screen.getByText('foo()')).toBeInTheDocument()
  })

  it('围栏代码块走 CodeBlock 组件', () => {
    render(<Markdown text={'```python\nx = 1\n```'} />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.getByTestId('code-lang')).toHaveTextContent('python')
  })

  it('无语言标记的代码块也能渲染', () => {
    render(<Markdown text={'```\nplain\n```'} />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.getByText(/plain/)).toBeInTheDocument()
  })

  it('链接点击走桥而非默认导航', async () => {
    const user = userEvent.setup()
    render(<Markdown text="见 [文档](https://example.com)" />)

    await user.click(screen.getByText('文档'))

    expect(openLink).toHaveBeenCalledWith('https://example.com')
  })

  it('链接点击阻止默认行为', async () => {
    const user = userEvent.setup()
    render(<Markdown text="[x](https://example.com)" />)

    const link = screen.getByText('x')
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    const prevented = !link.dispatchEvent(event)

    expect(prevented).toBe(true)
  })

  it('渲染无序列表', () => {
    render(<Markdown text={'- 一\n- 二'} />)
    expect(screen.getByText('一')).toBeInTheDocument()
    expect(screen.getByText('二')).toBeInTheDocument()
  })

  it('渲染粗体与斜体', () => {
    render(<Markdown text="**粗** 与 *斜*" />)
    expect(screen.getByText('粗').tagName).toBe('STRONG')
    expect(screen.getByText('斜').tagName).toBe('EM')
  })

  it('不执行内联脚本（XSS 防护）', () => {
    render(<Markdown text={'<script>window.__pwned = true</script>'} />)
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
  })

  it('不注入事件处理器属性', () => {
    const { container } = render(<Markdown text={'<img src=x onerror="window.__pwned2=true">'} />)
    const img = container.querySelector('img')
    // 要么被转义成文本、要么被剥掉属性，总之不能带 onerror
    expect(img?.getAttribute('onerror')).toBeNull()
  })

  it('空文本不报错', () => {
    expect(() => render(<Markdown text="" />)).not.toThrow()
  })
})
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -6
```

预期：FAIL，`Failed to resolve import "./Markdown"`。

- [ ] **Step 4: 实现 Markdown.tsx**

`web/src/components/Markdown.tsx`：

```tsx
import { useMemo, type ReactNode } from 'react'
import { marked, type Tokens } from 'marked'
import { CodeBlock } from './CodeBlock'
import { openLink } from '../bridge'

/**
 * 把模型输出的 Markdown 渲染为 React 元素。
 *
 * **不用 dangerouslySetInnerHTML 渲染整段**：那样模型输出里的
 * <script> 与 onerror 之类会真的生效。这里把 marked 解析成 token，
 * 再逐节点构造 React 元素 —— React 的文本插值天然转义，
 * 只有代码高亮那一处是受控的 dangerouslySetInnerHTML（Task 8）。
 */
export function Markdown({ text }: { text: string }) {
  const tokens = useMemo(() => marked.lexer(text), [text])
  return <>{tokens.map((t, i) => renderToken(t, i))}</>
}

function renderToken(token: Tokens.Generic, key: number): ReactNode {
  switch (token.type) {
    case 'paragraph':
      return <p key={key}>{renderInline(token.tokens ?? [])}</p>

    case 'heading': {
      const depth = Math.min(Math.max(token.depth ?? 1, 1), 6)
      const Tag = `h${depth}` as 'h1'
      return <Tag key={key}>{renderInline(token.tokens ?? [])}</Tag>
    }

    case 'code':
      return <CodeBlock key={key} code={token.text ?? ''} lang={token.lang ?? ''} />

    case 'list': {
      const Tag = token.ordered ? 'ol' : 'ul'
      return (
        <Tag key={key}>
          {(token.items ?? []).map((item: Tokens.ListItem, i: number) => (
            <li key={i}>{renderInline(item.tokens ?? [])}</li>
          ))}
        </Tag>
      )
    }

    case 'blockquote':
      return <blockquote key={key}>{renderToken({ ...token, type: 'paragraph' } as Tokens.Generic, 0)}</blockquote>

    case 'hr':
      return <hr key={key} />

    case 'space':
      return null

    default:
      // 未知 token 类型：按纯文本输出，不丢内容也不注入
      return token.raw ? <p key={key}>{token.raw}</p> : null
  }
}

function renderInline(tokens: Tokens.Generic[]): ReactNode[] {
  return tokens.map((t, i) => {
    switch (t.type) {
      case 'text':
        return <TextToken key={i} token={t} />

      case 'codespan':
        return <code key={i} className="inline-code">{t.text}</code>

      case 'strong':
        return <strong key={i}>{renderInline(t.tokens ?? [])}</strong>

      case 'em':
        return <em key={i}>{renderInline(t.tokens ?? [])}</em>

      case 'del':
        return <del key={i}>{renderInline(t.tokens ?? [])}</del>

      case 'link':
        return (
          <a
            key={i}
            href={t.href}
            className="md-link"
            onClick={(e) => {
              e.preventDefault()
              if (t.href) openLink(t.href)
            }}
          >
            {renderInline(t.tokens ?? [])}
          </a>
        )

      case 'br':
        return <br key={i} />

      default:
        return t.raw ? <span key={i}>{t.raw}</span> : null
    }
  })
}

/**
 * 文本 token 可能自带子 token（marked 在链接、强调等处会嵌套），
 * 但纯文本 token 的 text 里可能含未解析的 < > &，交给 React 转义即可。
 */
function TextToken({ token }: { token: Tokens.Generic }) {
  if (token.tokens && token.tokens.length > 0) {
    return <>{renderInline(token.tokens)}</>
  }
  return <>{token.text ?? token.raw}</>
}
```

- [ ] **Step 5: 在 Transcript 中接入 Markdown**

修改 `web/src/components/Transcript.tsx`：

把 `assistant` 分支改为：

```tsx
    case 'assistant':
      return (
        <div className="entry">
          <AssistantBubble>
            <Markdown text={item.text} />
          </AssistantBubble>
          <Timestamp ts={item.ts} />
        </div>
      )
```

并在文件顶部加 `import { Markdown } from './Markdown'`。

把进行中气泡同样改为 Markdown：

```tsx
      {liveText !== undefined && (
        <div className="entry">
          <AssistantBubble>
            <Markdown text={liveText} />
            <StreamingCursor />
          </AssistantBubble>
        </div>
      )}
```

**注意**：用户消息、错误消息**不**走 Markdown —— 用户输入原样显示（避免把用户敲的 `*` 当语法），错误文本也保持原文。

- [ ] **Step 6: 追加样式**

```css
/* ---- Markdown 排版 ---- */
.bubble__text p { margin: 0 0 8px; }
.bubble__text p:last-child { margin-bottom: 0; }
.bubble__text h1, .bubble__text h2, .bubble__text h3 { margin: 12px 0 6px; font-size: 1.15em; }
.bubble__text ul, .bubble__text ol { margin: 6px 0; padding-left: 20px; }
.bubble__text li { margin: 2px 0; }
.bubble__text blockquote {
  margin: 6px 0;
  padding-left: 10px;
  border-left: 3px solid var(--border);
  color: var(--text-dim);
}
.bubble__text hr { border: none; border-top: 1px solid var(--border); margin: 12px 0; }

.inline-code {
  font-family: var(--font-mono);
  font-size: 0.92em;
  background: var(--code-bg);
  border-radius: 4px;
  padding: 1px 5px;
}

.md-link { color: var(--accent); text-decoration: underline; cursor: pointer; }
```

- [ ] **Step 7: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/web" && npm test 2>&1 | tail -8
```

预期：全部通过。

- [ ] **Step 8: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add web/src/components/Markdown.tsx web/src/components/Markdown.test.tsx web/src/components/Transcript.tsx web/src/bridge.ts web/src/styles.css && git commit -F - <<'EOF'
feat(webui): Markdown 渲染与链接桥

不用 dangerouslySetInnerHTML 渲染整段：那样模型输出里的 <script> 与
onerror 会真的生效。这里把 marked 解析成 token 再逐节点构造 React 元素，
React 的文本插值天然转义，只有代码高亮那一处是受控的
dangerouslySetInnerHTML。有专门的 XSS 用例覆盖。

链接点击走桥而非默认导航——JCEF 里的页面导航会把整个应用页面替换掉。
Kotlin 侧用 BrowserUtil.browse 交给系统浏览器。

用户消息与错误消息不走 Markdown：前者避免把用户敲的 * 当语法，
后者保持原文。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 10: Gradle 集成与 ClaudePanel 接入

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\build.gradle.kts`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`

**Interfaces:**
- Consumes: `ClaudeTranscriptView`（Task 6）、`TranscriptOp`/`TranscriptItem`（Task 3）、`MessageRenderer`（已有）
- Produces: 转写区由 JCEF 承担，`ClaudePanel` 不再直接创建消息 Swing 组件

- [ ] **Step 1: 加入 buildWebUi 任务**

在 `build.gradle.kts` 的 `generateSidecarManifest` 之后追加：

```kotlin
// ---- web 前端构建 ----
// 用 Vite 的代价是构建里多一个 npm 步骤，所以给一个开关：
// 只改 Kotlin 时用 -PskipWeb 跳过，不必每次都等前端构建。

val webDir = layout.projectDirectory.dir("web")

val buildWebUi by tasks.registering {
    val src = webDir.asFile
    val outDir = layout.buildDirectory.dir("generated/webui-resources")
    val skip = providers.gradleProperty("skipWeb").isPresent

    inputs.dir(src.resolve("src"))
    inputs.file(src.resolve("index.html"))
    inputs.file(src.resolve("package.json"))
    inputs.file(src.resolve("vite.config.ts"))
    outputs.dir(outDir)

    doLast {
        val dest = outDir.get().asFile
        dest.deleteRecursively()

        if (skip) {
            // 跳过时不留半成品：没有 webui/index.html 的话
            // ClaudeTranscriptView 会显示"资源缺失"，那是诚实的
            logger.lifecycle("已跳过 web 构建（-PskipWeb）")
            dest.mkdirs()
            return@doLast
        }

        if (!src.resolve("node_modules").isDirectory) {
            // 用 ci 而非 install：lockfile 已入库，构建应当可复现。
            // 只在 node_modules 缺失时执行，不会每次构建都重装。
            logger.lifecycle("web/node_modules 不存在，执行 npm ci…")
            exec {
                workingDir = src
                commandLine("npm", "ci", "--no-audit", "--no-fund")
            }
        }

        exec {
            workingDir = src
            commandLine("npm", "run", "build")
        }

        val dist = src.resolve("dist")
        require(dist.isDirectory) { "web 构建未产出 dist/ 目录" }
        dist.copyRecursively(dest, overwrite = true)
        logger.lifecycle("web UI 已打包：${dest.resolve("index.html").length()} 字节")
    }
}

sourceSets.named("main") {
    resources.srcDir(buildWebUi)
}
```

> Windows 上 `commandLine("npm", ...)` 需要 `npm.cmd`。若报 "CreateProcess error=2"，把两处 `"npm"` 改为 `if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"`。

- [ ] **Step 2: 验证构建产出**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew clean buildPlugin --no-daemon 2>&1 | grep -E "(BUILD|web UI|e: |FAILED)" | head -8
```

预期：BUILD SUCCESSFUL，且日志出现 `web UI 已打包`。

验证 jar 内含 webui/index.html：

```bash
cd "C:/Users/CY/Desktop/CCoder" && unzip -l build/distributions/CCoder-0.1.0.zip | grep -c webui
```

若内层 jar 结构导致此处查不到，改为解压后查内层 jar：

```bash
cd /tmp && rm -rf webcheck && mkdir webcheck && cd webcheck && unzip -q "C:/Users/CY/Desktop/CCoder/build/distributions/CCoder-0.1.0.zip" && unzip -l CCoder/lib/CCoder-0.1.0.jar | grep -E "webui/(index\.html|assets)" | head -5
```

预期：至少出现 `webui/index.html`。

- [ ] **Step 3: 改 ClaudePanel —— 替换转写区**

修改 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`：

**(a)** 把 `private val transcript = JPanel().apply { ... }` 与 `private val scroll = JBScrollPane(transcript)...` 两处替换为：

```kotlin
    private val transcriptView = ClaudeTranscriptView(project)
```

**(b)** 在 `init` 的布局装配中，把 `add(scroll, BorderLayout.CENTER)` 改为：

```kotlin
        add(transcriptView, BorderLayout.CENTER)
```

**(c)** 删除以下成员与方法（它们已由 React 侧承担）：
- `liveAssistant: JBTextArea?`
- `consume(item: RenderItem)`
- `newLiveBubble()`
- `appendItem(item: RenderItem)`
- `componentFor(item: RenderItem)`
- `bubble(...)`、`collapsed(...)`、`centered(...)`
- `scrollToBottom()`
- `USER_BG` / `ASSISTANT_BG` / `ERROR_BG` 三个常量

**(d)** 新增一个把 `RenderItem` 翻译为 `TranscriptOp` 的方法：

```kotlin
    /**
     * 把渲染项转为转写操作。
     *
     * 这是 Kotlin 渲染逻辑（[MessageRenderer]）与 React 之间的最后一步转换。
     * [MessageRenderer] 本身不动 —— 它做的是"SDK 事件 → RenderItem"，
     * 与用什么渲染无关。
     */
    private fun toOp(item: RenderItem): TranscriptOp = when (item) {
        is RenderItem.UserText -> TranscriptOp.Append(
            TranscriptItem.User(id = nextMessageId(), ts = System.currentTimeMillis(), text = item.text)
        )

        is RenderItem.AssistantText -> TranscriptOp.FinalizeDelta("assistant", item.text)

        is RenderItem.AssistantDelta -> TranscriptOp.AppendDelta("assistant", item.text)

        // 思考流的逐字渲染刻意丢弃：它持续刷屏，对决策价值远低于正文（设计文档 §3.1）
        is RenderItem.ThinkingDelta -> TranscriptOp.ClearDelta("thinking")

        is RenderItem.Thinking -> TranscriptOp.Append(
            TranscriptItem.Thinking(id = nextMessageId(), ts = System.currentTimeMillis(), text = item.text)
        )

        is RenderItem.ToolUse -> TranscriptOp.Append(
            TranscriptItem.ToolUse(
                id = nextMessageId(), ts = System.currentTimeMillis(),
                name = item.name, input = item.input,
            )
        )

        is RenderItem.ErrorItem -> TranscriptOp.Append(
            TranscriptItem.Error(id = nextMessageId(), ts = System.currentTimeMillis(), text = item.message)
        )

        is RenderItem.Result -> TranscriptOp.Append(
            TranscriptItem.Result(
                id = nextMessageId(), ts = System.currentTimeMillis(),
                subtype = item.subtype, costUsd = item.costUsd, durationMs = item.durationMs,
            )
        )

        is RenderItem.SystemNote -> TranscriptOp.Append(
            TranscriptItem.SystemNote(id = nextMessageId(), ts = System.currentTimeMillis(), text = item.text)
        )
    }
```

**(e)** 把 `onMessage` 里的 `MessageRenderer.render(msg).forEach(::consume)` 改为：

```kotlin
                is SidecarMessage.Event -> MessageRenderer.render(msg).forEach { transcriptView.push(toOp(it)) }
```

**(f)** 把 `sendCurrentInput()` 里的 `appendItem(RenderItem.UserText(text))` 改为：

```kotlin
        transcriptView.push(toOp(RenderItem.UserText(text)))
```

并把 `liveAssistant = null` 一行删除。

**(g)** 加 `nextMessageId()`：

```kotlin
    private var messageCounter = 0L
    private fun nextMessageId(): String = "m${messageCounter++}"
```

**(h)** 在 `dispose()` 中加入：

```kotlin
        Disposer.dispose(transcriptView)
```

**(i)** 补 import：

```kotlin
import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.intellij.openapi.util.Disposer
```

- [ ] **Step 4: 编译并跑测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "(BUILD|e: |FAILED)" | head -10
```

预期：BUILD SUCCESSFUL，Kotlin 侧测试全绿（101 + 本次新增）。

若有 `Unused import` 之类的警告，清理掉 —— 被删掉的 Swing 组件相关的 import 也要一并删。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add build.gradle.kts src/main/kotlin/com/ccoder/ui/ClaudePanel.kt && git commit -F - <<'EOF'
feat(transcript): 转写区换成 JCEF，接入 React 渲染

MessageRenderer 一行不动——它做的是"SDK 事件 → RenderItem"，
与用什么渲染无关。新增的 toOp() 是 RenderItem → TranscriptOp 的最后一步转换，
两侧职责清晰、可分别测试。

ThinkingDelta 转为 ClearDelta 而非丢弃：思考流的逐字渲染是刻意放弃的
（刷屏且决策价值低），但必须显式清掉进行中的状态，
否则它会影响 React 侧对 assistant 气泡的判断。

Gradle 加了 buildWebUi 任务与 -PskipWeb 开关：用 Vite 的代价是构建里
多一个 npm 步骤，只改 Kotlin 时不该付这个代价。

ClaudePanel 删掉了全部消息渲染相关的 Swing 代码（bubble/collapsed/centered
等）与三个颜色常量——它们已由 React 侧承担。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 11: 端到端冒烟（含前后对照）

这次改动换掉了整个渲染层，**必须区分"新引入的问题"与"本来就没验过的问题"**。因此先跑一遍基线，再跑改动后的版本。

**Files:** 无（纯手工验证）

- [ ] **Step 1: 建立基线（在改动前的提交上）**

> 若 Task 1-10 已全部完成，用 `git stash` 或 `git worktree` 检出 `6a67c22`（UI 重设计 spec 提交，即改动前）。**不要**在未验证基线的情况下跳过这一步 —— 没有基线就无法区分新旧问题。

```bash
cd "C:/Users/CY/Desktop/CCoder" && git worktree add /tmp/ccoder-baseline 6a67c22
cd /tmp/ccoder-baseline && ./gradlew runIde --no-daemon
```

在沙箱 PyCharm 中逐条验证：

| # | 操作 | 期望 |
|---|---|---|
| B1 | 发 `reply with exactly: OK` | 收到 `OK`，显示成本与耗时 |
| B2 | 发 `读一下 README.md` | 权限卡片出现 → 点「允许」→ Claude 继续 |
| B3 | 卡片出现时不点，切走工具窗口 | 粘性通知出现 |
| B4 | 卡片出现时关闭项目，再打开 | 无残留挂起 |
| B5 | 会话中关闭沙箱 IDE | 任务管理器中无残留 `node.exe` / `claude.exe` |

记录每条的实际结果。**B3 在基线中预期失败** —— 通知组的 `displayType` 问题是 Task 1 才修的。

- [ ] **Step 2: 清理基线工作区**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git worktree remove /tmp/ccoder-baseline --force
```

- [ ] **Step 3: 跑改动后的冒烟**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew runIde --no-daemon
```

| # | 操作 | 期望 |
|---|---|---|
| A1 | 打开 CCoder 工具窗口 | 显示空转写区，无白屏、无"资源缺失"提示 |
| A2 | 明暗主题各看一遍 | 配色跟随 IDE，无硬编码色块 |
| A3 | 发 `reply with exactly: OK` | 逐字平滑流入 + 光标闪烁，无卡顿 |
| A4 | 发 `用 Python 写一个带四层缩进的函数` | 代码块有高亮、语言标签、复制按钮；默认不换行、横向可滚、缩进完整 |
| A5 | 点「自动换行」 | 切换为换行显示；重开工具窗口后状态保持 |
| A6 | 点代码块「复制」 | 按钮变「已复制」，剪贴板内容与显示一致 |
| A7 | 回复里含 Markdown 链接时点它 | 在系统浏览器打开，**界面不被导航掉** |
| A8 | B2 的权限流程 | 与基线一致（卡片在固定槽位，不被滚动带走） |
| A9 | B3 的粘性通知 | **与基线不同：现在应当出现**（Task 1 的修复） |
| A10 | B4 / B5 | 与基线一致（无残留挂起、无孤儿进程） |

- [ ] **Step 4: 降级路径验证**

在沙箱 IDE 中把 JCEF 关掉（Help → Find Action → Registry → 取消 `ide.browser.jcef.enabled`），重启插件：

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew runIde --no-daemon -Dide.browser.jcef.enabled=false
```

预期：转写区显示"当前 IDE 未启用 JCEF 嵌入浏览器"的提示，**不是空白面板**，且输入区与权限卡片仍可用。

- [ ] **Step 5: 记录结果并提交**

把两次冒烟的实际结果写进 `docs/superpowers/plans/2026-09-11-ccoder-ui-redesign-smoke.md`：

```markdown
# UI 重设计冒烟记录

日期：<填写>
基线提交：6a67c22
验收提交：<填写>

## 基线

| # | 结果 | 备注 |
|---|---|---|
| B1 | | |
| B2 | | |
| B3 | | |
| B4 | | |
| B5 | | |

## 验收

| # | 结果 | 备注 |
|---|---|---|
| A1 | | |
| ... | | |

## 差异分析

（逐条说明与基线不一致的项，以及是否属于预期变更）
```

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add docs/superpowers/plans/2026-09-11-ccoder-ui-redesign-smoke.md && git commit -F - <<'EOF'
docs: UI 重设计冒烟记录

含改动前后的对照。这次换掉了整个渲染层，没有基线就无法区分
"新引入的问题"与"本来就没验过的问题"。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 完成标准

- [ ] `cd sidecar && npm test` 全绿（71 个测试，本次未改动）
- [ ] `cd web && npm test` 全绿（本次新增，约 55 个用例）
- [ ] `./gradlew test` 全绿（Kotlin 侧 101 个原有 + 本次新增约 24 个）
- [ ] `./gradlew buildPlugin` 产出插件包，jar 内含 `webui/index.html`
- [ ] Task 11 的 A1–A10 全部通过，且与基线的差异都能解释
- [ ] 降级路径（JCEF 不可用）显示提示而非空白

## 已知未覆盖项

以下由 spec §1.3 明确划为"不做"，实现时**不要**顺手加上：

- 消息重新生成、编辑已发消息、导出对话（需改 sidecar 协议）
- 转写区虚拟滚动
- 消息搜索/过滤
- 附件、图片渲染
