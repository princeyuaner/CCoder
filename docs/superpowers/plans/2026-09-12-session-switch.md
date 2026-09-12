# CCoder 会话切换实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在工具窗口顶部加一个会话标签，点开列出 `~/.claude/projects/` 里的历史会话，选中后 `resume` 接着聊，并把历史回放进转写区。

**Architecture:** 不引入第二个工具窗口、不抽 project service —— `ClaudePanel` 继续持有 sidecar 与全部会话状态。协议新增 `listSessions` / `loadHistory` 两个请求-响应方法与 `start` 的 `resumeSessionId` 字段；`SidecarClient` 补一张待决表做 id 配对。回放**复用既有渲染管线**（历史消息与流式事件同构），React 侧零改动。

**Tech Stack:** Kotlin + IntelliJ Platform Gradle Plugin 2.18.0；Node 18+ + `@anthropic-ai/claude-agent-sdk@0.3.268`；JUnit 5（Kotlin）、`node --test`（sidecar）。**不引入新依赖。**

**Spec:** `docs/superpowers/specs/2026-09-12-session-switch-design.md`

**设计稿:** `docs/design/session-switch.html`

## Global Constraints

- **平台目标**：PyCharm `2025.3.1.1`，JDK 21
- **SDK 版本固定** `@anthropic-ai/claude-agent-sdk@0.3.268`，不升级
- **不抽 project service，不新建工具窗口**（spec §2.1）—— 这是本设计最大的一处减法
- **不新增 React 代码**：回放复用既有管线（spec §6.1）
- **`SessionSwitchState` 必须是纯函数**：`ClaudePanel` 依赖平台类、不可单测（`build.gradle.kts:32-35` 明确不引平台测试框架），照 `MainButtonState.kt` 的做法
- **未知即忽略，畸形即丢弃**（spec §3.3）：新协议消息缺 `id` 整条丢弃；列表里单条坏数据只跳过它
- **不碰 `ready.sessionId`**：它回显的是请求参数（`sidecar/index.js:72`），全新会话时为 null。真正的会话 id 只从 `system`/`init` 事件取（spec §4.4）
- **提交信息**结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`
- **单测命令**：Kotlin `./gradlew test`；sidecar `cd sidecar && npm test`
- **前置工作**：开工前必须先处理工作区里那批未提交的 `v0.2.0-dev` 改动（输入区卡片化、任务与子代理条、AskUserQuestion 卡片等）。先提交或另起分支 —— 否则本计划的 11 次提交会和那批不相关的 UI 工作混在一起，出问题时没法二分定位。

---

## 文件结构

```
CCoder/
├── sidecar/
│   ├── index.js                                 # Task 3：+ listSessions / loadHistory 分发
│   ├── session.js                               # Task 3：+ resumeSessionId → options.resume
│   └── test/index.test.js                       # Task 3：+ 4 个用例
└── src/
    ├── main/kotlin/com/ccoder/
    │   ├── sidecar/Protocol.kt                  # Task 2：+ 2 数据类、3 encode、2 parse 分支、responseIdOf
    │   ├── sidecar/SidecarClient.kt             # Task 4：+ 待决表与 request()
    │   └── ui/
    │       ├── MessageRenderer.kt               # Task 5：+ renderPrompt（只走回放路径）
    │       ├── TranscriptPump.kt                # Task 6：+ 批大小上限
    │       ├── SessionSwitchState.kt            # Task 7：新建，忙时判定纯函数
    │       ├── SessionLabel.kt                  # Task 8：新建，会话标签
    │       ├── SessionList.kt                   # Task 8：新建，列表与行渲染
    │       └── ClaudePanel.kt                   # Task 9、10：接线
    └── test/kotlin/com/ccoder/
        ├── sidecar/ProtocolTest.kt              # Task 2：+
        ├── sidecar/SidecarClientTest.kt         # Task 4：+
        └── ui/
            ├── MessageRendererTest.kt           # Task 5：+
            ├── TranscriptPumpTest.kt            # Task 6：+
            ├── SessionSwitchStateTest.kt        # Task 7：新建
            └── SessionListTest.kt               # Task 8：新建
```

---

## Task 1: 探针 —— `resume` 之后 `init` 事件带的是哪个 session_id

**为什么先做这个**：spec §4.4 规定当前会话 id 有两个来源（resume 时构造即知、全新会话从 `init` 事件取）。如果 resume 之后 `init` 事件**不再出现**，那么切换后"模型名标签会不会自己更新"就是否定的，需要在代码里显式处理。这个答案会改变 Task 10 的写法，所以先问清楚。

**代价**：一次真实的 API 调用（一个极短的回合）。**用 `forkSession: true` 做探针**——它会新建一个会话，原会话一个字节都不动。

**Files:**
- 不修改任何文件。这是一次性探针。

> **执行记录（2026-09-12，已跑完）**：结论是 **`init` 会重发，且 id 与传入的一致**，
> Task 10 不需要为 resume 路径做特殊处理。完整实测写进了 spec §9.7。
>
> **下面这版探针是修过的。** 初版有两处缺陷，都已踩过：
> 1. 它用 `forkSession: true` —— fork 必然产生新 id，于是「init 报的 id 与 resume 一致」
>    这条判读**永远不可能成立**，跑出来是个无法判读的结果。
> 2. 它把 `q.interrupt?.()` 放在 `break` 之后调用，此时传输已关，脚本抛
>    `ProcessTransport is not ready for writing` **死在清理之前**，留下一个 fork 会话没删。
>
> 修法是：**不 fork，改成自造一个一次性会话再 resume 它**。既满足"不污染真实会话"，
> 又保住了判读力；而且全程只碰自造的会话，问完即删。

- [x] **Step 1: 跑探针**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --input-type=module -e "
const m = await import('./node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs');
const dir = 'C:/Users/CY/Desktop/CCoder';
const env = { PATH: process.env.PATH, HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE };

async function* one() {
  yield { type:'user', message:{ role:'user', content:'1' }, parent_tool_use_id:null };
}

// 1. 造一个一次性会话（不碰任何真实会话）
let created = null;
const q1 = m.query({ prompt: one(), options: { cwd: dir, permissionMode:'default', includePartialMessages:false, env } });
for await (const msg of q1) {
  if (msg.type === 'system' && msg.subtype === 'init') created = msg.session_id;
  if (msg.type === 'result') break;
}
console.log('造出来的会话:', created);

// 2. resume 它 —— 不 fork，走的正是插件切换时那条路
let initId = null, model = null, initCount = 0;
const q2 = m.query({ prompt: one(), options: { cwd: dir, permissionMode:'default', resume: created, includePartialMessages:false, env } });
for await (const msg of q2) {
  if (msg.type === 'system' && msg.subtype === 'init') { initId = msg.session_id; model = msg.model; initCount++; }
  if (msg.type === 'result') break;
}

console.log('init 出现次数:', initCount);
console.log('resume 传的 id:', created);
console.log('init 报的 id  :', initId);
console.log('两者一致:', initId === created);
console.log('init 带的 model:', model);

// 3. 清理：两个都删
await m.deleteSession(created, { dir });
console.log('（一次性会话已删除）');
"
```

**注意**：不要在 `break` 之后调 `q.interrupt?.()` —— 那一刻传输已在关闭，会抛错并跳过清理。

- [x] **Step 2: 记录结论**

实测结果（原始输出见 spec §9.7）：

- `resume` 之后是否重发 `system`/`init`：**是**
- 若是，`init` 报的 id：**与 `resume` 的 id 相同**

**判读**：
- 若 `init` 出现且 id 与 resume 的一致 → Task 10 什么都不用特殊处理，现有的 `init` 分支照常更新模型名标签 ← **本次落在这条**
- 若 `init` **不出现** → Task 10 需要在 resume 路径上把模型名标签置成占位（因为那个标签只在 `init` 里更新），并把这一条写进 spec
- 若 `init` 报的是**另一个 id**（不 fork 也这样）→ 这是严重问题，**停下来**，说明 `resume` 的语义和文档不符，整个 §5 的切换流程要重新设计

- [x] **Step 3: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add docs/superpowers/specs/2026-09-12-session-switch-design.md && git commit -F - <<'EOF'
docs(spec): 补上 resume 后 init 事件的实测结论

Task 1 探针结果：见 §10「待实现时确认」。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: 协议扩展 —— 新消息的编解码

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\Protocol.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt`

**Interfaces:**
- Consumes: 无（本任务是链条起点）
- Produces:
  - `data class SessionInfo(val sessionId: String, val summary: String?, val firstPrompt: String?, val lastModified: Long)`
  - `SidecarMessage.SessionList(val requestId: String, val sessions: List<SessionInfo>)`
  - `SidecarMessage.History(val requestId: String, val sessionId: String, val items: List<JsonObject>)`
  - `StartParams` 新增字段 `val resumeSessionId: String? = null`
  - `Protocol.encodeListSessions(id: String, dir: String, limit: Int, offset: Int): String`
  - `Protocol.encodeLoadHistory(id: String, dir: String, sessionId: String): String`
  - `Protocol.responseIdOf(msg: SidecarMessage): String?`

> **状态：已完成（2026-09-12）**
>
> **执行记录**：Step 4 预期的 `BUILD SUCCESSFUL` 第一次**没有**出现 ——
> 给 sealed interface 加两个子类型后，`ClaudePanel.onMessage` 的 `when` 穷尽性检查失败
> （它刻意没有 `else` 分支，新消息类型必须显式处理）。**计划漏了这一步。**
>
> 补法：在 `ClaudePanel.kt` 的 `when` 里加
> `is SidecarMessage.SessionList, is SidecarMessage.History -> Unit`，注释说明这两条应答
> 本该被 Task 4 的待决表按 id 截走、到不了这里，列出只为穷尽性。
>
> **这是新增消息类型时的固定代价** —— Task 9/10 若再加消息类型，同样要先补穷尽性分支，
> 否则会在一个与本次改动无关的文件上报编译失败。

- [x] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt`（文件末尾的最后一个 `}` 之前）。import 区补两条（现有 import 里还没有）：

```kotlin
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
```

```kotlin
    // ---- 会话列表与历史（Task 2）----

    @Test
    fun `sessions 消息解析出列表`() {
        val line = """{"type":"sessions","id":"r1","sessions":[
            {"sessionId":"a","summary":"标题甲","firstPrompt":"甲","lastModified":111},
            {"sessionId":"b","summary":null,"firstPrompt":null,"lastModified":222}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals("r1", msg.requestId)
        assertEquals(2, msg.sessions.size)
        assertEquals("标题甲", msg.sessions[0].summary)
        assertEquals(111L, msg.sessions[0].lastModified)
        assertNull(msg.sessions[1].summary)
    }

    @Test
    fun `sessions 缺 id 时整条丢弃`() {
        // 没有 id 就无从配对，留着只会变成一个永远等不到结果的占位
        val line = """{"type":"sessions","sessions":[{"sessionId":"a"}]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `sessions 里坏条目只跳过它自己`() {
        // 一条坏数据不该让另外 49 个会话都看不见
        val line = """{"type":"sessions","id":"r1","sessions":[
            {"summary":"没有 id"},
            "不是对象",
            {"sessionId":"good","summary":"好的","lastModified":5}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals(1, msg.sessions.size)
        assertEquals("good", msg.sessions[0].sessionId)
    }

    @Test
    fun `sessions 缺 lastModified 时给 0 而不是丢弃`() {
        val line = """{"type":"sessions","id":"r1","sessions":[{"sessionId":"a"}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals(0L, msg.sessions[0].lastModified)
    }

    @Test
    fun `history 消息解析出条目`() {
        val line = """{"type":"history","id":"r2","sessionId":"s1","items":[
            {"type":"user","message":{"role":"user","content":"你好"}},
            {"type":"assistant","message":{"role":"assistant","content":[]}}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.History

        assertEquals("r2", msg.requestId)
        assertEquals("s1", msg.sessionId)
        assertEquals(2, msg.items.size)
        assertEquals("user", msg.items[0].get("type").asString)
    }

    @Test
    fun `history 缺 sessionId 时丢弃`() {
        val line = """{"type":"history","id":"r2","items":[]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `history 里非对象条目被过滤`() {
        val line = """{"type":"history","id":"r2","sessionId":"s1","items":[1,"x",{"type":"user"}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.History

        assertEquals(1, msg.items.size, "只有对象才该留下")
    }

    @Test
    fun `encodeStart 带 resumeSessionId`() {
        val json = Protocol.encodeStart(
            "r1",
            StartParams(cwd = "/tmp", permissionMode = "default", resumeSessionId = "sess-1"),
        )
        val obj = JsonParser.parseString(json.trim()).asJsonObject

        assertEquals("sess-1", obj.getAsJsonObject("params").get("resumeSessionId").asString)
    }

    @Test
    fun `encodeStart 不带 resumeSessionId 时不写这个字段`() {
        // 传 null 与传空字符串语义不同 —— 绝不能写成空串
        val json = Protocol.encodeStart("r1", StartParams(cwd = "/tmp", permissionMode = "default"))
        val obj = JsonParser.parseString(json.trim()).asJsonObject

        assertFalse(obj.getAsJsonObject("params").has("resumeSessionId"))
    }

    @Test
    fun `encodeListSessions 与 encodeLoadHistory 的形状`() {
        val a = JsonParser.parseString(
            Protocol.encodeListSessions("r1", "C:/proj", 50, 0).trim()
        ).asJsonObject
        assertEquals("listSessions", a.get("method").asString)
        assertEquals(50, a.getAsJsonObject("params").get("limit").asInt)
        assertEquals("C:/proj", a.getAsJsonObject("params").get("dir").asString)

        val b = JsonParser.parseString(
            Protocol.encodeLoadHistory("r2", "C:/proj", "sess-1").trim()
        ).asJsonObject
        assertEquals("loadHistory", b.get("method").asString)
        assertEquals("sess-1", b.getAsJsonObject("params").get("sessionId").asString)
    }

    @Test
    fun `responseIdOf 只认响应类消息`() {
        assertEquals(
            "r1",
            Protocol.responseIdOf(
                SidecarMessage.SessionList("r1", emptyList())
            ),
        )
        assertEquals(
            "r2",
            Protocol.responseIdOf(SidecarMessage.History("r2", "s", emptyList<JsonObject>())),
        )
        // 非响应消息必须返回 null，否则 SidecarClient 会把它们从 listener 那里截走
        assertNull(Protocol.responseIdOf(SidecarMessage.Ready("s", "m")))
        assertNull(Protocol.responseIdOf(SidecarMessage.Unknown("whatever")))
    }
```

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.sidecar.ProtocolTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: SessionList` / `resumeSessionId` / `encodeListSessions` 等。

- [x] **Step 3: 实现**

在 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\Protocol.kt` 里：

**(a)** 在 `sealed interface SidecarMessage` 内、`Unknown` 之前插入两个成员：

```kotlin
    /**
     * `listSessions` 的应答。
     *
     * 带 [requestId] 是为了配对 —— 这是协议里第一批请求-响应式消息。
     */
    data class SessionList(val requestId: String, val sessions: List<SessionInfo>) : SidecarMessage

    /**
     * `loadHistory` 的应答。
     *
     * [items] 是 SDK 的原始消息，**形状与流式事件同构** —— 所以回放能直接
     * 复用 MessageRenderer 与转写管线，不需要另一套渲染代码（spec §6.1）。
     */
    data class History(
        val requestId: String,
        val sessionId: String,
        val items: List<JsonObject>,
    ) : SidecarMessage
```

**(b)** 在 `sealed interface SidecarMessage` 之后、`StartParams` 之前插入：

```kotlin
/**
 * 会话列表中的一条。
 *
 * 字段是从 SDK 的 `SDKSessionInfo` 里**裁剪**出来的（sdk.d.ts:5154）：
 * 只留界面要用的四个。`gitBranch` 等刻意不带过来 —— 实测本机全部会话
 * 都在同一分支，没有信息量（spec §9.5）。
 */
data class SessionInfo(
    val sessionId: String,
    val summary: String?,
    val firstPrompt: String?,
    val lastModified: Long,
)
```

**(c)** `StartParams` 增加一个字段（放在末尾，保持默认值）：

```kotlin
data class StartParams(
    val cwd: String,
    val permissionMode: String,
    val model: String? = null,
    val claudePath: String? = null,
    val extraDirs: List<String> = emptyList(),
    val envOverrides: Map<String, String> = emptyMap(),
    /** 非空则恢复该会话（SDK 的 `Options.resume`）。空 = 开新会话。 */
    val resumeSessionId: String? = null,
)
```

**(d)** 在 `parse` 的 `when` 里、`"error"` 分支之前插入两个分支：

```kotlin
            // 缺 id 就无从配对，整条丢弃 —— 留着只会变成一个永远等不到结果的占位
            "sessions" -> obj.str("id")?.let { rid ->
                SidecarMessage.SessionList(rid, parseSessionList(obj.arr("sessions")))
            }

            "history" -> {
                val rid = obj.str("id")
                val sid = obj.str("sessionId")
                if (rid == null || sid == null) {
                    null
                } else {
                    SidecarMessage.History(
                        rid,
                        sid,
                        obj.arr("items")
                            ?.filter { it.isJsonObject }
                            ?.map { it.asJsonObject }
                            ?: emptyList(),
                    )
                }
            }
```

**(e)** 在 `object Protocol` 里、`encodeStart` 之前插入三个 encode：

```kotlin
    fun encodeListSessions(id: String, dir: String, limit: Int, offset: Int): String =
        line(id, "listSessions", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("limit", limit)
            addProperty("offset", offset)
        })

    fun encodeLoadHistory(id: String, dir: String, sessionId: String): String =
        line(id, "loadHistory", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("sessionId", sessionId)
        })
```

并在 `encodeStart` 的 `p` 里追加一行（放在 `envOverrides` 那段之后）：

```kotlin
            params.resumeSessionId?.let { addProperty("resumeSessionId", it) }
```

**(f)** 在 `object Protocol` 里、`encodeStart` 之前插入 `responseIdOf`：

```kotlin
    /**
     * 响应类消息的关联 id。非响应消息返回 null。
     *
     * 放在这里而不是 [SidecarClient]：客户端不必认识每一种消息类型，
     * 将来新增一种响应消息时也只改这一处。
     */
    fun responseIdOf(msg: SidecarMessage): String? = when (msg) {
        is SidecarMessage.SessionList -> msg.requestId
        is SidecarMessage.History -> msg.requestId
        else -> null
    }
```

**(g)** 在 `object Protocol` 的容错取值区之前插入私有解析：

```kotlin
    /**
     * 逐条解析会话列表。
     *
     * 缺 `sessionId` 的条目**跳过而非废掉整个列表** —— 一条坏数据不该让
     * 另外 49 个会话都看不见。`lastModified` 缺失给 0，排序时自然沉底。
     */
    private fun parseSessionList(arr: JsonArray?): List<SessionInfo> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val sid = o.str("sessionId") ?: return@mapNotNull null
            SessionInfo(
                sessionId = sid,
                summary = o.str("summary"),
                firstPrompt = o.str("firstPrompt"),
                lastModified = o.long("lastModified") ?: 0L,
            )
        }
    }
```

**(h)** 在容错取值区追加 long 取值（`num` 返回的是 Int，装不下毫秒时间戳）：

```kotlin
    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
```

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.sidecar.ProtocolTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`，无 `FAILED`。**实测**：37 通过 / 0 失败（原 26 + 新增 11）。

- [x] **Step 5: 跑全量测试确认没打破既有契约**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。**实测**：342 通过 / 0 失败 / 0 错误（开工前 331）。

- [x] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/Protocol.kt src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt && git commit -F - <<'EOF'
feat(protocol): 会话列表与历史的编解码

新增 sessions / history 两种响应消息（协议里第一批请求-响应式消息，
带 id 回显用于配对），以及 listSessions / loadHistory 的编码。
StartParams 增加 resumeSessionId。

容错遵循既有原则：缺 id 的响应整条丢弃（无从配对），列表里单条坏数据
只跳过它自己（一条坏数据不该让另外 49 个会话看不见）。

SessionInfo 只带界面要用的四个字段，SDK 的 gitBranch 等刻意不传 ——
实测本机全部会话在同一分支，没有信息量。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 3: sidecar 侧分发

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\sidecar\index.js`
- Modify: `C:\Users\CY\Desktop\CCoder\sidecar\session.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js`

**Interfaces:**
- Consumes: 无（与 Task 2 是同一协议的两端，可并行开发；但联调要两个都完成）
- Produces:
  - `createDispatcher({ sessionFactory, out, sessionApi })` —— `sessionApi` 新增可选注入点，形状 `{ listSessions, getSessionMessages }`
  - 线上消息：`{type:'sessions', id, sessions:[{sessionId, summary, firstPrompt, lastModified}]}`
  - 线上消息：`{type:'history', id, sessionId, items:[...]}`
  - `createSession({ ..., resumeSessionId })` → SDK `options.resume`

> **状态：已完成（2026-09-12）**
>
> **执行记录**：Step 5 预期「`# pass` 比改动前多 6 条」，实际多了 **7** 条。
> 多出来的那条是 `start 把 resumeSessionId 透传给 sessionFactory` —— 它**一写出来就通过**：
> dispatcher 的 start 分支用 `...params` 展开，resumeSessionId 本来就透传了，不需要任何生产改动。
> 它不是驱动新代码的测试，是**回归守卫**（防止将来有人把展开收窄成显式字段列表）。
> 留着有价值，但别把它当成"这条链路是这次新建的"。
>
> 实测：sidecar 79 → 86 用例，`fail 0`。

- [x] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js` 末尾。

```js
// ---- 会话列表与历史（Task 3）----

/** 假的 SDK 会话 API，用来把真实 SDK 挡在单测之外。 */
function fakeSessionApi({ listError = null, historyError = null } = {}) {
  const calls = [];
  return {
    calls,
    api: {
      listSessions: async (opts) => {
        calls.push(['listSessions', opts]);
        if (listError) throw new Error(listError);
        return [
          {
            sessionId: 'a', summary: '标题甲', firstPrompt: '甲', lastModified: 111,
            gitBranch: 'v0.2.0-dev', fileSize: 999, cwd: '/x', tag: null,
          },
        ];
      },
      getSessionMessages: async (sid, opts) => {
        calls.push(['getSessionMessages', sid, opts]);
        if (historyError) throw new Error(historyError);
        return [{ type: 'user', message: { role: 'user', content: '你好' } }];
      },
    },
  };
}

test('listSessions 不需要活会话也能应答', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  // 刻意不 start —— 面板一打开就要能列出历史，这是设计的关键点
  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj', limit: 50, offset: 0 } });

  return tick().then(() => {
    const msg = out.find((m) => m.type === 'sessions');
    assert.ok(msg, '没有活会话时也必须应答');
    assert.equal(msg.id, 'r1', '响应必须回显请求 id');
    assert.equal(msg.sessions.length, 1);
    assert.equal(msg.sessions[0].summary, '标题甲');
    assert.equal(fa.calls[0][1].dir, '/proj');
  });
});

test('listSessions 只传界面要用的字段', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const s = out.find((m) => m.type === 'sessions').sessions[0];
    assert.deepEqual(
      Object.keys(s).sort(),
      ['firstPrompt', 'lastModified', 'sessionId', 'summary'],
      'SDK 的 gitBranch / fileSize / cwd / tag 不该过线',
    );
  });
});

test('listSessions 不传 includeProgrammatic', () => {
  // 回归测试。SDK 文档说 IDE 选择器该传 false —— 但那会把插件自己的
  // 会话也滤掉（实测本机 19/19 全被滤）。传 false 不报错，只是安静地
  // 返回空，所以必须由测试守住这条
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const opts = fa.calls[0][1];
    assert.ok(!('includeProgrammatic' in opts), '传了它会把插件自己的会话一起滤掉');
    assert.ok(!('includeWorktrees' in opts), 'worktree 参数本版用不上，不要顺手带上');
  });
});

test('listSessions 出错时以 error 回执而非静默', () => {
  const out = [];
  const fa = fakeSessionApi({ listError: '读不了' });
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const err = out.find((m) => m.type === 'error');
    assert.equal(err.code, 'LIST_SESSIONS_FAILED');
    assert.match(err.message, /读不了/);
    assert.equal(err.fatal, false, '列不出会话不该杀掉会话');
  });
});

test('loadHistory 把条目原样透传', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r2', method: 'loadHistory', params: { dir: '/proj', sessionId: 'sess-1' } });

  return tick().then(() => {
    const msg = out.find((m) => m.type === 'history');
    assert.equal(msg.id, 'r2');
    assert.equal(msg.sessionId, 'sess-1');
    assert.equal(msg.items.length, 1);
    assert.equal(msg.items[0].type, 'user');
    assert.deepEqual(fa.calls[0], ['getSessionMessages', 'sess-1', { dir: '/proj' }]);
  });
});

test('loadHistory 出错时以 error 回执', () => {
  const out = [];
  const fa = fakeSessionApi({ historyError: '会话不存在' });
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r2', method: 'loadHistory', params: { dir: '/proj', sessionId: 'nope' } });

  return tick().then(() => {
    const err = out.find((m) => m.type === 'error');
    assert.equal(err.code, 'LOAD_HISTORY_FAILED');
    assert.match(err.message, /会话不存在/);
  });
});

test('start 把 resumeSessionId 透传给 sessionFactory', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {}, sessionApi: fakeSessionApi().api });

  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default', resumeSessionId: 'sess-9' } });

  const created = sf.calls.find((c) => c[0] === 'create')[1];
  assert.equal(created.resumeSessionId, 'sess-9');
});
```

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test 2>&1 | grep -E "^# (pass|fail)|not ok" | head -20
```

预期：`# fail` 大于 0，失败的正是上面这几条（`sessionApi` 参数还不存在，`listSessions` 走到 `default` 分支回了 `UNKNOWN_METHOD`）。

- [x] **Step 3: 实现 session.js 的 resume 透传**

在 `C:\Users\CY\Desktop\CCoder\sidecar\session.js` 的 `createSession` 参数解构里加 `resumeSessionId`：

```js
export function createSession({
  cwd,
  permissionMode,
  model,
  claudePath,
  extraDirs,
  envOverrides,
  resumeSessionId,
  onEvent = () => {},
  onPermission = () => {},
  queryFn = defaultQueryFn,
} = {}) {
```

并在 `if (claudePath) options.pathToClaudeCodeExecutable = claudePath;` 之后加：

```js
  // 恢复既有会话。与 continue 互斥、可配 forkSession —— 本插件只用
  // "接着写"这一种语义（spec §1.2），所以这里不带 forkSession。
  if (resumeSessionId) options.resume = resumeSessionId;
```

- [x] **Step 4: 实现 index.js 的分发**

**(a)** 文件顶部 import 区加：

```js
import {
  listSessions as sdkListSessions,
  getSessionMessages as sdkGetSessionMessages,
} from '@anthropic-ai/claude-agent-sdk';
```

**(b)** `createDispatcher` 的签名与文档注释改为：

```js
/**
 * 把 NDJSON 方法调用分发到 session。
 *
 * 与进程 IO 解耦，便于测试注入假 session。
 *
 * @param {object} deps
 * @param {Function} deps.sessionFactory (opts) => Session
 * @param {Function} deps.out            (message) => void
 * @param {object}   [deps.sessionApi]   { listSessions, getSessionMessages }。
 *   这两个是 SDK 的**独立函数**，不属于任何会话 —— 注入是为了把真实 SDK
 *   挡在单测之外。生产路径用真实实现。
 */
export function createDispatcher({
  sessionFactory,
  out,
  sessionApi = { listSessions: sdkListSessions, getSessionMessages: sdkGetSessionMessages },
}) {
```

**(c)** 在 `handle` 的 `switch` 里、`case 'interrupt':` 之前插入两个分支：

```js
      // 列表与回放**不需要活会话** —— 它们是 SDK 的独立函数。
      // 这是设计的关键点：面板一打开就能列出历史，不必先起一个会话。
      case 'listSessions': {
        // 刻意**不传** includeProgrammatic。SDK 文档说 "IDE session pickers
        // pass false for parity with terminal /resume"，看着正该传 —— 但实测
        // 那会把 19 条会话全滤光：插件自己开的会话也是 SDK 会话
        // （entrypoint sdk-ts），会一并被滤掉，于是列表里永远看不到自己刚
        // 恢复过的那个。传 false 不报错，只是安静地返回空，所以这个坑不
        // 实测根本看不出来（spec §4.2）。
        const { dir, limit = 50, offset = 0 } = params;
        Promise.resolve(sessionApi.listSessions({ dir, limit, offset }))
          .then((sessions) => out({
            type: 'sessions',
            id: msg.id,
            // 只把界面要用的字段送过线。SDK 的 SDKSessionInfo 有十来个字段，
            // 全送过去等于把 SDK 的结构钉进协议里
            sessions: (sessions ?? []).map((s) => ({
              sessionId: s.sessionId,
              summary: s.summary ?? null,
              firstPrompt: s.firstPrompt ?? null,
              lastModified: s.lastModified ?? 0,
            })),
          }))
          .catch((err) => fail('LIST_SESSIONS_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'loadHistory': {
        const { dir, sessionId } = params;
        Promise.resolve(sessionApi.getSessionMessages(sessionId, { dir }))
          .then((items) => out({
            type: 'history',
            id: msg.id,
            sessionId,
            // 条目原样透传：它们与流式事件同构，插件侧直接复用既有渲染管线
            items: items ?? [],
          }))
          .catch((err) => fail('LOAD_HISTORY_FAILED', String(err?.message ?? err), false));
        return session;
      }
```

- [x] **Step 5: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test 2>&1 | grep -E "^# (pass|fail)|not ok" | head -20
```

预期：`# fail 0`，`# pass` 比改动前多 6 条。

- [x] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/index.js sidecar/session.js sidecar/test/index.test.js && git commit -F - <<'EOF'
feat(sidecar): 会话列表与历史的转发；start 支持 resume

listSessions / loadHistory 都**不需要活会话** —— 它们是 SDK 的独立函数。
这是设计的关键点：面板一打开就能列出历史，不必先起一个会话。

送过线的字段是裁剪过的：SDK 的 SDKSessionInfo 有十来个字段，全送过去
等于把 SDK 的结构钉进协议里。

sessionApi 做成可注入，把真实 SDK 挡在单测之外；生产路径用真实实现。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: SidecarClient 待决表（请求-响应配对）

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarClient.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarClientTest.kt`

**Interfaces:**
- Consumes: `Protocol.responseIdOf(msg)`（Task 2）
- Produces:
  - `sealed interface RequestOutcome { data class Answered(val message: SidecarMessage); data class Failed(val reason: String) }`
  - `SidecarClient.request(id: String, json: String, callback: (RequestOutcome) -> Unit)`
  - `SidecarClient` 构造器新增可选参数 `requestTimeoutMs: Long = 10_000L`

- [ ] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarClientTest.kt` 的 `class SidecarClientTest` 内部（最后一个 `}` 之前）。

```kotlin
    // ---- 待决表（Task 4）----

    /** 一条会一直读到流末尾的输入。用来驱动配对逻辑。 */
    private fun respondAfter(input: String, rec: SidecarListener, timeoutMs: Long = 10_000) =
        SidecarClient(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            ByteArrayOutputStream(),
            rec,
            requestTimeoutMs = timeoutMs,
        )

    @Test
    @Timeout(30)
    fun `请求收到同 id 响应时回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter(
            "{\"type\":\"sessions\",\"id\":\"r1\",\"sessions\":[]}\n",
            Recorder(),
        )
        client.start()
        client.request("r1", Protocol.encodeListSessions("r1", "/p", 50, 0)) { outcome.complete(it) }

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Answered, "实际：$got")
        assertTrue((got as RequestOutcome.Answered).message is SidecarMessage.SessionList)
        client.close()
    }

    @Test
    @Timeout(30)
    fun `已被配对的响应不再送给 listener`() {
        val rec = Recorder()
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter("{\"type\":\"sessions\",\"id\":\"r1\",\"sessions\":[]}\n", rec)
        client.start()
        client.request("r1", "{}") { outcome.complete(it) }
        outcome.get(5, TimeUnit.SECONDS)

        assertTrue(
            rec.messages.none { it is SidecarMessage.SessionList },
            "被 request 消费掉的响应不该再走 listener，否则会被处理两遍",
        )
        client.close()
    }

    @Test
    @Timeout(30)
    fun `id 对不上的响应仍走 listener`() {
        // 宁可让它流到 listener 被看见，也不要静默丢弃 —— 静默丢弃会让
        // "请求超时"和"消息丢了"两种故障长得一模一样
        val rec = Recorder()
        val client = respondAfter("{\"type\":\"sessions\",\"id\":\"other\",\"sessions\":[]}\n", rec)
        client.start()

        assertTrue(rec.latch.await(5, TimeUnit.SECONDS), "5 秒内未收到消息")
        assertTrue(rec.messages[0] is SidecarMessage.SessionList)
        client.close()
    }

    @Test
    @Timeout(30)
    fun `超时后以 Failed 回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        // 输入流不含任何响应；超时设成 150ms 免得测试等太久
        val client = respondAfter("", Recorder(), timeoutMs = 150)
        client.start()
        client.request("r1", "{}") { outcome.complete(it) }

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "实际：$got")
        client.close()
    }

    @Test
    @Timeout(30)
    fun `close 时待决请求全部以 Failed 回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter("", Recorder(), timeoutMs = 60_000)
        client.start()
        client.request("r1", "{}") { outcome.complete(it) }
        client.close()

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "通道关掉后不能有请求永远挂着")
    }

    @Test
    @Timeout(30)
    fun `输入流结束时待决请求以 Failed 回调`() {
        // sidecar 进程退出 = 读线程走到流末尾。这之后不可能再有响应，
        // 挂着的请求必须立刻失败，否则界面会永远停在"载入中"
        val outcome = CompletableFuture<RequestOutcome>()
        val rec = Recorder()
        val client = SidecarClient(
            ByteArrayInputStream("{\"type\":\"ready\"}\n".toByteArray(Charsets.UTF_8)),
            ByteArrayOutputStream(),
            rec,
            requestTimeoutMs = 60_000,
        )
        client.start()
        assertTrue(rec.latch.await(5, TimeUnit.SECONDS))
        client.request("r1", "{}") { outcome.complete(it) }

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "实际：$got")
        client.close()
    }
```

import 区补一条（下面的用例都用短名）：

```kotlin
import java.util.concurrent.CompletableFuture
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.sidecar.SidecarClientTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: RequestOutcome` / `request` / `requestTimeoutMs`。

- [ ] **Step 3: 实现**

在 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarClient.kt` 里：

**(a)** 在 `interface SidecarListener` 之后插入：

```kotlin
/** 一次请求的结果。 */
sealed interface RequestOutcome {
    data class Answered(val message: SidecarMessage) : RequestOutcome

    /** 超时、通道关闭或 sidecar 退出。三种都意味着"不会再有答案了"。 */
    data class Failed(val reason: String) : RequestOutcome
}
```

**(b)** 替换 `SidecarClient` 的类头与新增字段：

```kotlin
class SidecarClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: SidecarListener,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null

    private class Pending(val timer: TimerTask, val callback: (RequestOutcome) -> Unit)

    /**
     * 已发出但未配对的请求。
     *
     * 与 sidecar 侧的权限待决表（session.js 的 `pending`）对称 —— 两侧都
     * 有一个"发出去了、还没回来"的窗口，都必须保证它最终被关闭。
     */
    private val pending = ConcurrentHashMap<String, Pending>()

    private val timer = Timer("ccoder-sidecar-request-timeout", true)
```

**(c)** 在 `sendLine` 之后插入 `request`：

```kotlin
    /**
     * 发一条请求，在收到**带同一 id 的响应**时回调。
     *
     * 回调保证恰好发生一次，三条路径都会走到：响应到达、超时、通道关闭
     * 或 sidecar 退出。这与 spec §6.2 规则① 同源 —— 挂起的回调不能泄漏，
     * 否则界面会永远停在"载入中"。
     *
     * 回调在**读取线程**上执行。调用方要碰 Swing 得自己 invokeLater。
     */
    fun request(id: String, json: String, callback: (RequestOutcome) -> Unit) {
        if (closed.get()) {
            callback(RequestOutcome.Failed("通道已关闭"))
            return
        }

        val task = object : TimerTask() {
            override fun run() {
                pending.remove(id)?.callback?.invoke(RequestOutcome.Failed("请求超时"))
            }
        }
        // 先登记再调度：反过来的话，一个极短的超时可能在登记前就触发，
        // 那时 remove 拿不到东西，回调就永远丢了
        pending[id] = Pending(task, callback)
        timer.schedule(task, requestTimeoutMs)
        sendLine(json)
    }

    /** @return true 表示这条消息已被某个待决请求消费 */
    private fun resolvePending(id: String, msg: SidecarMessage): Boolean {
        val p = pending.remove(id) ?: return false
        p.timer.cancel()
        p.callback(RequestOutcome.Answered(msg))
        return true
    }

    private fun failAllPending(reason: String) {
        for (id in pending.keys.toList()) {
            val p = pending.remove(id) ?: continue
            p.timer.cancel()
            p.callback(RequestOutcome.Failed(reason))
        }
    }
```

**(d)** `dispatch` 改为：

```kotlin
    private fun dispatch(lines: List<String>) {
        for (line in lines) {
            // parse 返回 null 表示非 JSON、空行或畸形消息 —— 跳过即可
            val msg = Protocol.parse(line) ?: continue

            // 已被配对的响应不再往下走，否则它会被处理两遍。
            // id 对不上的仍然送给 listener —— 静默丢弃会让"请求超时"
            // 和"消息丢了"两种故障长得一模一样
            val rid = Protocol.responseIdOf(msg)
            if (rid != null && resolvePending(rid, msg)) continue

            listener.onMessage(msg)
        }
    }
```

**(e)** `start()` 的读取循环末尾（`dispatch(framer.flush())` 之后）加：

```kotlin
                // 读线程走到这里 = 流结束 = sidecar 不会再回话了。
                // 挂着的请求必须立刻失败，不能等超时
                failAllPending("sidecar 已退出")
```

**(f)** `close()` 改为：

```kotlin
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        failAllPending("通道已关闭")
        runCatching { output.close() }
        readerThread?.interrupt()
        timer.cancel()
    }
```

**(g)** `companion object` 加常量（若类内还没有 companion，则在类末尾新增）：

```kotlin
    companion object {
        /** 列表与回放都是本地读取，实测 140ms 量级。10 秒是很宽的余量。 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
    }
```

**(h)** import 区补 `import java.util.Timer` 和 `import java.util.TimerTask`。

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.sidecar.SidecarClientTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 跑全量测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/SidecarClient.kt src/test/kotlin/com/ccoder/sidecar/SidecarClientTest.kt && git commit -F - <<'EOF'
feat(sidecar): SidecarClient 补上请求-响应配对

协议里第一批请求-响应式消息（sessions / history）需要按 id 配对。
原先只有单向收发 + 一个 listener。

回调保证恰好发生一次，三条终止路径都覆盖：响应到达、超时、通道关闭
或 sidecar 退出。漏掉任何一条，界面就会永远停在"载入中"——这与
spec §6.2 规则① 是同一个问题（挂起的 Promise 不能泄漏）。

已被配对的响应不再送给 listener（否则处理两遍）；id 对不上的仍然
送给 listener——静默丢弃会让"请求超时"和"消息丢了"长得一模一样。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: `MessageRenderer.renderPrompt` —— 回放路径的 user 消息

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\MessageRenderer.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\MessageRendererTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `MessageRenderer.renderPrompt(item: JsonObject): String?`

**背景**：`renderEvent`（`MessageRenderer.kt:38-45`）只认 `assistant` / `result` / `system` / `stream_event`，`type: "user"` 落到 `else` 返回空。live 路径下没问题（用户气泡是 `sendCurrentInput()` 直接推的），但回放必须从事件里把提问挖出来。实测最大会话的 247 条 user 消息里，**236 条是工具结果，真实提问只有 11 条** —— 不过滤会把转写区淹掉。

- [ ] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\MessageRendererTest.kt`（最后一个 `}` 之前）。import 区补一条（现有 import 里还没有，而下面的用例大量用它）：

```kotlin
import org.junit.jupiter.api.Assertions.assertNull
```

```kotlin
    // ---- 回放路径的 user 消息（Task 5）----

    private fun prompt(json: String) = MessageRenderer.renderPrompt(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `纯文本提问被取出`() {
        val text = prompt("""{"type":"user","message":{"role":"user","content":"这是什么项目"}}""")
        assertEquals("这是什么项目", text)
    }

    @Test
    fun `工具结果被丢弃`() {
        // 实测：247 条 user 消息里 236 条是这个形状。不过滤会把转写区淹掉
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"t1","content":"一堆文件内容"}]}}"""
        )
        assertNull(text, "工具结果不是提问")
    }

    @Test
    fun `数组形式的文本块被拼接`() {
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"text","text":"第一段"},
                {"type":"text","text":"第二段"}]}}"""
        )
        assertEquals("第一段\n第二段", text)
    }

    @Test
    fun `同时含文本块与工具结果时整条丢弃`() {
        // 真实提问不会和 tool_result 混在一条里。混着出现说明这是工具回合，
        // 那点文本是工具上下文而非用户输入
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"text","text":"顺带一提"},
                {"type":"tool_result","tool_use_id":"t1","content":"x"}]}}"""
        )
        assertNull(text)
    }

    @Test
    fun `空白提问返回 null`() {
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":"   "}}"""))
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":[]}}"""))
    }

    @Test
    fun `非 user 类型返回 null`() {
        assertNull(prompt("""{"type":"assistant","message":{"role":"assistant","content":"x"}}"""))
        assertNull(prompt("""{"type":"system","subtype":"init"}"""))
    }

    @Test
    fun `畸形输入不抛错`() {
        // 回放会把整份历史喂进来，任何一条畸形都不能让整个过程崩掉
        assertNull(prompt("""{"type":"user"}"""))
        assertNull(prompt("""{"type":"user","message":"不是对象"}"""))
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":42}}"""))
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.MessageRendererTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: renderPrompt`。

- [ ] **Step 3: 实现**

在 `MessageRenderer.kt` 的 `fun render(msg: SidecarMessage)` 之后插入：

```kotlin
    /**
     * 从一条**历史**消息里取出"真实提问"的文本。
     *
     * **只供回放路径调用。** live 路径下用户气泡是 `sendCurrentInput()` 直接
     * 推的，这里再产一次就会变成两条 —— 所以刻意不并进 [renderEvent]。
     *
     * 必须过滤工具结果：实测最大会话的 247 条 user 消息里，236 条是工具结果，
     * 真实提问只有 11 条。全渲染出来会把转写区淹掉。
     *
     * @return 提问文本；不是提问（工具结果、畸形、空白）时返回 null
     */
    fun renderPrompt(item: JsonObject): String? {
        if (item.str("type") != "user") return null
        val content = item.obj("message")?.get("content") ?: return null

        // 形式一：纯文本提问
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            return content.asString.takeIf { it.isNotBlank() }
        }
        if (!content.isJsonArray) return null

        val blocks = content.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }

        // 含工具结果即判定为工具回合。真实提问不会和 tool_result 混在一条里，
        // 混着出现时那点文本是工具上下文而非用户输入
        if (blocks.any { it.str("type") == "tool_result" }) return null

        val text = blocks
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
            .trim()

        return text.takeIf { it.isNotEmpty() }
    }
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.MessageRendererTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/MessageRenderer.kt src/test/kotlin/com/ccoder/ui/MessageRendererTest.kt && git commit -F - <<'EOF'
feat(render): 从历史消息里取出真实提问

回放需要一个新入口：renderEvent 只认 assistant/result/system/stream_event，
type:"user" 落在 else。live 路径下无所谓（用户气泡由 sendCurrentInput
直接推），回放不行。

必须过滤工具结果——实测最大会话的 247 条 user 消息里 236 条是工具结果，
真实提问只有 11 条。

刻意不并进 renderEvent：并进去的话 live 路径会双重渲染。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: `TranscriptPump` 批大小上限

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\TranscriptPump.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\TranscriptPumpTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `TranscriptPump(exec, throttleMs, maxBatch)`；常量 `DEFAULT_MAX_BATCH = 200`

**背景**：`flushNow` 会把缓冲区**全部**发出去。回放一次性 enqueue 804 条（实测最大会话），下一拍就是一批 2.6MB 过 JCEF 桥。加上限后，多出来的留到下一拍——代价只是多几帧。

- [ ] **Step 1: 写失败的测试**

在 `TranscriptPumpTest.kt` 的 `withPump` 辅助函数里加上 `maxBatch` 参数：

```kotlin
    private fun withPump(
        throttleMs: Long = 10_000,
        maxBatch: Int = TranscriptPump.DEFAULT_MAX_BATCH,
        exec: (String) -> Unit,
        block: (TranscriptPump) -> Unit,
    ) {
        val pump = TranscriptPump(exec = exec, throttleMs = throttleMs, maxBatch = maxBatch)
        try {
            block(pump)
        } finally {
            pump.dispose()
        }
    }
```

然后在类末尾追加：

```kotlin
    // ---- 批大小上限（Task 6）----

    /** 数一批推送里装了多少个操作。 */
    private fun opsIn(json: String): Int = JsonParser.parseString(json).asJsonArray.size()

    @Test
    fun `单批不超过上限`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(500) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
            pump.flushNow()

            assertEquals(1, batches.size)
            assertEquals(200, batches[0], "回放的 804 条一次全发出去就是一次几 MB 的跨边界调用")
        }
    }

    @Test
    fun `超出的部分留到下一拍而不是丢弃`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(500) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
            pump.flushNow()
            pump.flushNow()
            pump.flushNow()
            pump.flushNow()

            assertEquals(listOf(200, 200, 100), batches, "一共 500 条，一条都不能少")
        }
    }

    @Test
    fun `不足一批时一次发完`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(5) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
            pump.flushNow()

            assertEquals(listOf(5), batches)
        }
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.TranscriptPumpTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: maxBatch` / `DEFAULT_MAX_BATCH`。

- [ ] **Step 3: 实现**

在 `TranscriptPump.kt` 里：

**(a)** 类头加参数：

```kotlin
class TranscriptPump(
    private val exec: (String) -> Unit,
    throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
) {
```

**(b)** `flushNow` 里取批的那段改为：

```kotlin
        val batch = synchronized(lock) {
            if (buffer.isEmpty()) return
            // 单批上限。回放会把几百条一次塞进来（实测最大会话 804 条），
            // 全发出去就是一次几 MB 的 executeJavaScript，跨 CEF 进程边界
            // 会明显卡顿。留一部分给下一拍，代价只是多几帧。
            val n = minOf(buffer.size, maxBatch)
            val head = buffer.take(n)
            buffer.subList(0, n).clear()
            head
        }
```

**(c)** companion object 加常量：

```kotlin
        const val DEFAULT_THROTTLE_MS = 16L

        /**
         * 单批操作数上限。
         *
         * 200 是估的：正常流式推送一拍只有几条到几十条，这个值不影响它；
         * 而 804 条的回放会被切成 5 拍（约 80ms）推完，用户看不出来。
         */
        const val DEFAULT_MAX_BATCH = 200
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.TranscriptPumpTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`，包括原有的 12 条。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/TranscriptPump.kt src/test/kotlin/com/ccoder/ui/TranscriptPumpTest.kt && git commit -F - <<'EOF'
perf(pump): 单批操作数加上限

原实现一次 flush 会把缓冲区全部发出去。回放一次性 enqueue 804 条
（实测最大会话）时，下一拍就是一批 2.6MB 过 JCEF 桥。

加上限后多出来的留到下一拍，804 条被切成 5 拍（约 80ms）推完。
正常流式推送一拍只有几条到几十条，不受影响。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: `SessionSwitchState` —— 忙时能否切换的纯判定

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionSwitchState.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal enum class SwitchBlock { None, TurnRunning, PermissionPending }`
  - `internal fun switchBlock(busy: Boolean, pendingPermissions: Int): SwitchBlock`
  - `internal fun switchBlockNotice(block: SwitchBlock): String?`

**为什么是纯函数**：`ClaudePanel` 依赖平台类、起不了单测（`build.gradle.kts:32-35` 明确不引平台测试框架）。照 `MainButtonState.kt` 的既有做法把它抽出来，`ClaudePanel` 只负责把结果画出来。

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 忙时能否切换会话。
 *
 * 拦住的理由不是"体验"，是正确性：切换会 stopSession()，正在跑的回合被腰斩，
 * 挂着的权限询问也会一并作废（sidecar 的 denyAllPending）。
 */
class SessionSwitchStateTest {

    @Test
    fun `空闲时可切`() {
        assertEquals(SwitchBlock.None, switchBlock(busy = false, pendingPermissions = 0))
        assertNull(switchBlockNotice(SwitchBlock.None), "可切时没有话要说")
    }

    @Test
    fun `回合进行中拦住`() {
        assertEquals(SwitchBlock.TurnRunning, switchBlock(busy = true, pendingPermissions = 0))
        assertNotNull(switchBlockNotice(SwitchBlock.TurnRunning))
    }

    @Test
    fun `有权限询问挂着时拦住`() {
        assertEquals(SwitchBlock.PermissionPending, switchBlock(busy = false, pendingPermissions = 1))
    }

    @Test
    fun `权限挂起优先于回合进行中`() {
        // 有权限挂着时 busy 通常也是 true，但"先处理那条询问"才是用户
        // 该做的动作 —— 提示得更具体才有用
        assertEquals(SwitchBlock.PermissionPending, switchBlock(busy = true, pendingPermissions = 2))
    }

    @Test
    fun `两句提示都指向具体动作`() {
        // 光说"不能切"没用，得说清先做什么
        val running = switchBlockNotice(SwitchBlock.TurnRunning)!!
        val pending = switchBlockNotice(SwitchBlock.PermissionPending)!!
        assertEquals(true, running.contains("停止"), "实际：$running")
        assertEquals(true, pending.contains("权限"), "实际：$pending")
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.SessionSwitchStateTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: switchBlock`。

- [ ] **Step 3: 实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionSwitchState.kt`：

```kotlin
package com.ccoder.ui

/** 会话切换被什么挡住了。 */
internal enum class SwitchBlock {
    /** 可以切 */
    None,

    /** 回合进行中 */
    TurnRunning,

    /** 有权限询问挂着 */
    PermissionPending,
}

/**
 * 忙时能否切换会话。
 *
 * 抽成纯函数的原因同 [mainButtonState]：ClaudePanel 依赖 Swing 与平台、
 * 起不了单测，而"什么时候该拦住"正是这次改动里最容易写错的部分。
 *
 * 拦住的理由不是体验而是正确性 —— 切换要 `stopSession()`：
 *  - 正在跑的回合会被腰斩
 *  - 挂着的权限询问会一并作废（sidecar 收到 stop 后的 denyAllPending）
 *
 * 权限挂起**优先于**回合进行：有询问挂着时 busy 通常也是 true，但
 * "先处理那条询问"才是用户该做的动作。提示得更具体才有用。
 */
internal fun switchBlock(busy: Boolean, pendingPermissions: Int): SwitchBlock = when {
    pendingPermissions > 0 -> SwitchBlock.PermissionPending
    busy -> SwitchBlock.TurnRunning
    else -> SwitchBlock.None
}

/**
 * 拦住时给用户看的一句话。可切时返回 null。
 *
 * 两句话都必须指向**具体动作** —— 光说"不能切"用户不知道下一步做什么。
 */
internal fun switchBlockNotice(block: SwitchBlock): String? = when (block) {
    SwitchBlock.None -> null

    SwitchBlock.TurnRunning ->
        "当前回合还在跑。先按「停止」再切会话 —— 否则这个回合会被腰斩。"

    SwitchBlock.PermissionPending ->
        "还有权限询问没处理。先把它处理掉再切会话 —— 否则那条询问会作废。"
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.SessionSwitchStateTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/SessionSwitchState.kt src/test/kotlin/com/ccoder/ui/SessionSwitchStateTest.kt && git commit -F - <<'EOF'
feat(ui): 忙时能否切换会话的纯判定

抽成纯函数的原因同 MainButtonState：ClaudePanel 依赖平台、起不了单测，
而"什么时候该拦住"正是最容易写错的部分。

拦住的理由不是体验而是正确性：切换要 stopSession()，正在跑的回合会被
腰斩，挂着的权限询问也会一并作废。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 8: `SessionLabel` 与 `SessionList` 组件

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionLabel.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionList.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionListTest.kt`

**Interfaces:**
- Consumes: `SessionInfo`（Task 2）、`SwitchBlock` / `switchBlockNotice`（Task 7）
- Produces:
  - `internal class SessionLabel(private val onOpen: () -> Unit) : JLabel()`，方法 `setTitle(text: String?, enabled: Boolean)`
  - `internal fun buildSessionList(sessions: List<SessionInfo>, currentSessionId: String?, block: SwitchBlock, onPick: (SessionInfo) -> Unit): JComponent`
  - `internal fun relativeTime(nowMs: Long, thenMs: Long): String`

**形状照 `ModeLabel`（`ComposerMode.kt:39`）与 `buildModeList`（`ComposerMode.kt:89`）** —— 那个控件已经解决了"可点、点开弹列表、选中项打勾、未选中也占同样缩进位"这几件事，踩过的坑不必再踩一遍。

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionListTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * 会话列表的表层。
 *
 * 这里断言的是**用户能看见什么** —— 标题、时间、当前会话的勾、忙时的说明行。
 * 布局细节（间距、颜色）不在单测范围内，那属于设计稿与手工冒烟。
 */
class SessionListTest {

    private val now = 1_700_000_000_000L

    private val sessions = listOf(
        SessionInfo("s1", "还可以做什么功能", "这是什么项目", now - 30_000),
        SessionInfo("s2", "PyCharm插件调用Claude Code", null, now - 3_600_000),
        SessionInfo("s3", null, "你好", now - 90_000_000),
    )

    private fun textsIn(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel) out += child.text
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 树里所有挂了鼠标点击响应的容器 —— 也就是"能被点的行"。 */
    private fun clickableRows(root: Container): List<Component> {
        val out = mutableListOf<Component>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is Container && child.mouseListeners.isNotEmpty()) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun click(component: Component) {
        component.dispatchEvent(
            MouseEvent(
                component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, 5, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    // ---- 内容 ----

    @Test
    fun `标题缺失时回退到首个提问`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("PyCharm插件调用Claude Code") })
        assertTrue(texts.any { it.contains("你好") }, "s3 没有 summary，该退回 firstPrompt")
    }

    @Test
    fun `当前会话有勾`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertEquals(1, texts.count { it.startsWith("✓") }, "只该有一条被标记为当前")
    }

    @Test
    fun `摘要与首个提问都为空时显示占位而非空白行`() {
        val blank = listOf(SessionInfo("s9", null, null, now))
        val texts = textsIn(buildSessionList(blank, null, SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("无标题") }, "空白行看起来像渲染坏了")
    }

    @Test
    fun `空列表给一行说明`() {
        val texts = textsIn(buildSessionList(emptyList(), null, SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("没有") }, "实际：$texts")
    }

    // ---- 交互 ----

    @Test
    fun `空闲时每行都可点，点第一行回传它`() {
        val picked = mutableListOf<String>()
        val root = buildSessionList(sessions, "s1", SwitchBlock.None) { picked += it.sessionId }

        val rows = clickableRows(root)
        assertEquals(3, rows.size, "三行都该可点")
        click(rows[0])
        assertEquals(listOf("s1"), picked)
    }

    @Test
    fun `忙时行根本不挂点击响应`() {
        // 断言的是"没有监听器"而不是"点了没反应"：后者在前者为真时恒成立，
        // 是个永远绿的假测试
        val root = buildSessionList(sessions, "s1", SwitchBlock.TurnRunning) {}
        assertTrue(clickableRows(root).isEmpty(), "忙时不能让它切过去 —— 正在跑的回合会被腰斩")
    }

    @Test
    fun `忙时显示拦住的说明`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.PermissionPending) {})
        assertTrue(texts.any { it.contains("权限") }, "实际：$texts")
    }

    @Test
    fun `空闲时不显示说明行`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertFalse(texts.any { it.contains("先按") })
    }

    // ---- 相对时间 ----

    @Test
    fun `相对时间的分档`() {
        assertEquals("刚刚", relativeTime(now, now - 5_000))
        assertEquals("3 分钟前", relativeTime(now, now - 3 * 60_000))
        assertEquals("2 小时前", relativeTime(now, now - 2 * 3_600_000))
        assertEquals("昨天", relativeTime(now, now - 26 * 3_600_000))
        assertEquals("5 天前", relativeTime(now, now - 5 * 86_400_000))
    }

    @Test
    fun `未来时间不显示成负数`() {
        // 时钟回拨或时区问题都可能造出未来时间戳，不该显示"-3 分钟前"
        assertEquals("刚刚", relativeTime(now, now + 60_000))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.SessionListTest" 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：编译失败，报 `unresolved reference: buildSessionList` / `relativeTime`。

- [ ] **Step 3: 实现 `SessionLabel.kt`**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionLabel.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel

/** 标题后面那个表示"可以展开"的小三角。 */
private const val CARET = " ▾"

/**
 * 顶部的会话标签。可点，点开弹会话列表。
 *
 * 形状照 [ModeLabel]：同样的可点 + 光标 + 展开三角。三处弹出层
 * （任务详情、权限模式、会话列表）共用同一套交互，用户学一次就行。
 *
 * 它只负责**显示**。切换什么时候真的发生，由 [ClaudePanel] 决定 ——
 * 那边等 sidecar 的 ready 回执，不等的话标签会显示一个没生效的会话。
 */
internal class SessionLabel(private val onOpen: () -> Unit) : JLabel() {

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }
            }
        )
        setTitle(null, enabled = true)
    }

    /**
     * @param text 会话标题。null = 新会话（还没有标题）
     * @param enabled false 时变灰并**拒绝点击** —— 忙时用
     */
    fun setTitle(text: String?, enabled: Boolean) {
        // 新会话用斜体占位：留空会让人以为标签还没加载出来
        val label = text?.takeIf { it.isNotBlank() } ?: "新会话"
        this.text = label + CARET
        font = UIUtil.getLabelFont().deriveFont(if (text == null) Font.ITALIC else Font.PLAIN)
        foreground = if (enabled) UIUtil.getInactiveTextColor() else UIUtil.getLabelDisabledForeground()
        cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }
}
```

**注意**：上面的 `enabled` 只改外观，**不改点击行为** —— 忙时仍然可点（点开看到置灰的列表 + 说明行，这是 spec §5.1 定的"点了才说"）。真正的拦截在 `buildSessionList` 里。

- [ ] **Step 4: 实现 `SessionList.kt`**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionList.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 当前会话那一行的勾。未选中的行用空格占同样的位，否则文字会左右跳。 */
private const val MARK = "✓"

/**
 * 会话列表。
 *
 * 排布是单行紧凑（设计稿 A）：标题在左可伸缩，时间在右不参与收缩 ——
 * 时间被挤掉的话排序就看不出来了。
 *
 * [block] 非 None 时**整列不可点**，并在顶部显示一句拦住的原因。
 * 拦住而不是静默忽略：点不动的东西容易被当成 bug（spec §5.1）。
 */
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
    onPick: (SessionInfo) -> Unit = {},
): JComponent {
    val root = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 4)
    }

    switchBlockNotice(block)?.let { root.add(noticeRow(it)) }

    if (sessions.isEmpty()) {
        root.add(noteRow("没有找到历史会话"))
        return root
    }

    val now = System.currentTimeMillis()
    sessions.forEach { s ->
        root.add(
            sessionRow(
                session = s,
                selected = s.sessionId == currentSessionId,
                clickable = block == SwitchBlock.None,
                nowMs = now,
                onPick = onPick,
            )
        )
    }
    return root
}

private fun sessionRow(
    session: SessionInfo,
    selected: Boolean,
    clickable: Boolean,
    nowMs: Long,
    onPick: (SessionInfo) -> Unit,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，deriveFont 会 NPE
    // （RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
        if (clickable) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onPick(session)
                }
            )
        }
    }

    // 标题：summary 优先，退回 firstPrompt，都没有给占位。
    // 空白行看起来像渲染坏了，不如直说
    val title = session.summary?.takeIf { it.isNotBlank() }
        ?: session.firstPrompt?.takeIf { it.isNotBlank() }
        ?: "（无标题）"

    // 未选中用空格而非 isVisible=false：不可见的组件仍然在组件树里，
    // 既让"只该有一条被标记"测不了，也让可访问性工具读到幻影文字。
    // 空格占位是 modeRow 已经在用的做法（ComposerMode.kt:115）
    val mark = JLabel(if (selected) MARK else " ").apply { font = base }
    val markSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 固定宽度：勾出现或消失时标题不左右跳
        preferredSize = JBUI.size(13, base.size)
        add(mark, BorderLayout.WEST)
    }

    val titleLabel = JLabel(title).apply {
        font = if (selected) base.deriveFont(Font.BOLD) else base
        // 唯一可伸缩的元素
    }

    val timeLabel = JLabel(relativeTime(nowMs, session.lastModified)).apply {
        font = base
        foreground = UIUtil.getInactiveTextColor()
    }

    row.add(markSlot, BorderLayout.WEST)
    row.add(titleLabel, BorderLayout.CENTER)
    row.add(timeLabel, BorderLayout.EAST)

    if (!clickable) row.isEnabled = false
    return row
}

private fun noticeRow(text: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 6, 6, 6)
    // 定宽 HTML：说明要换行显示，不定宽的话它在弹出层里会被拉成一长条
    add(JLabel("<html><body style='width:230px'>$text</body></html>").apply {
        font = UIUtil.getLabelFont()
    }, BorderLayout.CENTER)
}

private fun noteRow(text: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(8, 6)
    add(JLabel(text).apply {
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getInactiveTextColor()
    }, BorderLayout.CENTER)
}

/**
 * 相对时间。分档到"天"为止 —— 再细就没意义了，列表是按时间排序的。
 *
 * 未来时间戳（时钟回拨、时区错乱）一律当"刚刚"，不显示负数。
 */
internal fun relativeTime(nowMs: Long, thenMs: Long): String {
    val delta = nowMs - thenMs
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000

    return when {
        delta < 60_000 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days == 1L -> "昨天"
        else -> "$days 天前"
    }
}
```

**已知未覆盖**：`JLabel` 默认**不截断**，标题过长时它会把右侧的时间挤出可视区。`BorderLayout` 会把 CENTER 压到剩余宽度，所以时间本身不会被裁掉，但弹层可能被撑得比工具窗口还宽。实测若出现这种情况，按 Step 5 的说明改成自绘截断。

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon --tests "com.ccoder.ui.SessionListTest" 2>&1 | grep -E "FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`。

若 `忙时行根本不挂点击响应` 失败，说明 `clickable=false` 时仍然装了 MouseListener —— 检查 `if (clickable)` 是否真的包住了 `addMouseListener`。

**本任务测不到的部分**（交给 Task 11 冒烟）：单行布局在 420px 宽度下的实际效果，尤其标题为 `PyCharm插件调用Claude Code` 时右侧时间是否被挤掉。`BorderLayout` 会给 CENTER 让位给 EAST，理论上时间不会丢，但**理论上不等于测过**。若实测被挤掉，给 `titleLabel` 设 `minimumSize = JBUI.size(0, base.size)`，并在标题过长时改用 `JLabel` 自绘截断（`RunStripView` 里有现成的做法）。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/SessionLabel.kt src/main/kotlin/com/ccoder/ui/SessionList.kt src/test/kotlin/com/ccoder/ui/SessionListTest.kt && git commit -F - <<'EOF'
feat(ui): 会话标签与列表组件

形状照 ModeLabel / buildModeList —— 那套已经解决了可点、点开弹列表、
选中打勾、未选中也占同样缩进位这几件事。

排布是设计稿 A（单行紧凑）：标题可伸缩、时间不参与收缩。
忙时整列不可点并在顶部显示拦住的原因——点不动的东西容易被当成 bug，
所以选了"点了才说"而不是"一开始就变灰"。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 9: `ClaudePanel` 接线 —— 顶部标签与列表弹出

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`

**Interfaces:**
- Consumes: `SessionLabel`、`buildSessionList`、`switchBlock`（Task 7、8）；`Protocol.encodeListSessions`、`SidecarMessage.SessionList`、`RequestOutcome`（Task 2、4）
- Produces（供 Task 10 使用的私有成员）：
  - `private val sessionLabel: SessionLabel`
  - `private var currentSessionId: String?`
  - `private var resumeTargetId: String?`
  - `private fun showTogglePopup(anchor: JComponent, content: JComponent, onClosed: () -> Unit): JBPopup`
  - `private fun toggleSessionChooser()`

**本任务没有单测** —— `ClaudePanel` 依赖平台类，起不了单测（见 Task 7 的说明）。验证方式是**编译 + 全量测试仍绿 + Task 11 手工冒烟**。这是本项目对 `ClaudePanel` 的一贯处理方式。

- [ ] **Step 1: 加会话标签到顶部那一行**

`ClaudePanel.kt:165-168` 的 `top` 改为：

```kotlin
        // 顶部：左边是连接状态，右边是会话标签（可点，点开列历史会话）
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
            add(sessionLabel, BorderLayout.EAST)
        }
```

并在字段区（`statusLabel` 附近，约 `ClaudePanel.kt:122`）加：

```kotlin
    /** 顶部右侧的会话标签。可点，点开列历史会话。 */
    private val sessionLabel = SessionLabel { toggleSessionChooser() }

    /**
     * 当前会话 id。
     *
     * 两个来源：resume 时构造即知；全新会话从 `system`/`init` 事件取。
     * **不读 `ready.sessionId`** —— 那个回显的是请求参数，全新会话时是 null
     * （spec §10）。
     */
    private var currentSessionId: String? = null

    /** 非空表示下一次 [startSession] 要恢复这个会话。发送后即清空。 */
    private var resumeTargetId: String? = null

    /** 打开着的会话列表浮层。用它实现"再点一次收起"。 */
    private var sessionPopup: JBPopup? = null
```

- [ ] **Step 2: 抽出 `showTogglePopup`**

三处弹出（任务详情、权限模式、会话列表）的创建参数完全一致，现在出现了第三份拷贝。在 `ClaudePanel` 里新增：

```kotlin
    /**
     * 开一个"再点一次收起"的浮层。
     *
     * 抽出来的时机是第三份拷贝出现时 —— 三处的创建参数完全一致，
     * 散着写迟早会改漏一处。位置计算复用 [showAboveOrBelow]。
     */
    private fun showTogglePopup(
        anchor: JComponent,
        content: JComponent,
        onClosed: () -> Unit,
    ): JBPopup {
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        popup.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = onClosed()
            }
        )
        showAboveOrBelow(popup, anchor)
        return popup
    }
```

把 `toggleRunDetail()`（`ClaudePanel.kt:280-305`）与 `toggleModeChooser()`（`ClaudePanel.kt:328-352`）改为使用它：

```kotlin
    private fun toggleRunDetail() {
        runDetailPopup?.let { open ->
            open.cancel()
            return
        }
        runDetailPopup = showTogglePopup(
            anchor = runStripView,
            content = buildRunDetail(runStatus),
        ) {
            runDetailPopup = null
            runStripView.setOpen(false)
        }
        runStripView.setOpen(true)
    }

    private fun toggleModeChooser() {
        modePopup?.let { open ->
            open.cancel()
            return
        }
        modePopup = showTogglePopup(
            anchor = modeLabel,
            content = buildModeList(currentMode) { pickPermissionMode(it) },
        ) {
            modePopup = null
        }
    }
```

- [ ] **Step 3: 实现 `toggleSessionChooser`**

```kotlin
    /**
     * 点会话标签 → 列出历史会话 → 弹层。
     *
     * **先请求、收到后才弹**，而不是先弹一个"载入中"。实测 listSessions 是
     * 纯本地读取，140ms 量级，用户察觉不到；换来的是不必处理"弹出后再换内容"
     * 那套尺寸重算。
     */
    private fun toggleSessionChooser() {
        sessionPopup?.let { open ->
            open.cancel()
            return
        }

        val c = client
        if (c == null) {
            // 不静默吞掉 —— 点了没反应比明说更让人困惑
            pushOp(toOp(RenderItem.SystemNote("会话还没建立，列不出历史会话")))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            pushOp(toOp(RenderItem.ErrorItem("项目没有 basePath，无法定位会话目录。")))
            return
        }

        val reqId = nextId()
        c.request(reqId, Protocol.encodeListSessions(reqId, dir, SESSION_LIST_LIMIT, 0)) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.SessionList
                        if (msg == null) {
                            pushOp(toOp(RenderItem.ErrorItem("会话列表返回了意外的消息。")))
                        } else {
                            showSessionPopup(msg.sessions)
                        }
                    }

                    is RequestOutcome.Failed ->
                        pushOp(toOp(RenderItem.ErrorItem("列出会话失败：${outcome.reason}")))
                }
            }
        }
    }

    private fun showSessionPopup(sessions: List<SessionInfo>) {
        val block = switchBlock(busy, permissionQueue.totalPending)
        val content = buildSessionList(sessions, currentSessionId, block) { picked ->
            sessionPopup?.cancel()
            sessionPopup = null
            switchToSession(picked)
        }
        sessionPopup = showTogglePopup(sessionLabel, content) { sessionPopup = null }
    }
```

`switchToSession` 属于 Task 10。**本任务先放一个空实现**，让代码可编译：

```kotlin
    /** 见 Task 10。 */
    private fun switchToSession(target: SessionInfo) {
        LOG.info("CCoder 请求切换会话：${target.sessionId}（Task 10 实现）")
    }
```

- [ ] **Step 4: 让会话标签跟着状态走**

在 `onMessage` 的 `Ready` 分支里，`ready = true` 之后加：

```kotlin
                    // 新会话还没有标题，标签显示斜体占位；
                    // 恢复的会话标题要等列表回来才知道，先显示 id 前 8 位
                    sessionLabel.setTitle(resumeTargetId?.take(8), enabled = true)
```

在 `Failure` 分支的 `if (msg.fatal)` 块里加：

```kotlin
                        sessionLabel.setTitle(currentSessionId?.take(8), enabled = true)
```

在 `setBusy` 里追加一行（忙时标签变灰但仍可点 —— spec §5.1 的"点了才说"）：

```kotlin
    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        sessionLabel.setTitle(currentSessionId?.take(8), enabled = !value)
        refreshMainButton()
    }
```

在 `startSession()` 的 `runStatus.reset()` 附近加：

```kotlin
        sessionLabel.setTitle(resumeTargetId?.take(8), enabled = true)
```

- [ ] **Step 5: 加常量**

在 `private companion object` 里加：

```kotlin
        /** 一屏够看了。不做翻页 —— 实测本机 19 条会话。 */
        const val SESSION_LIST_LIMIT = 50
```

并确认 import 区有：`com.ccoder.sidecar.RequestOutcome`、`com.ccoder.sidecar.SessionInfo`。

- [ ] **Step 6: 编译并跑全量测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`，无 `error:`。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt && git commit -F - <<'EOF'
feat(ui): 顶部会话标签与历史列表弹出

先请求、收到后才弹，而不是先弹一个"载入中"：实测 listSessions 是纯本地
读取（140ms），用户察觉不到，换来的是不必处理弹出后换内容的尺寸重算。

顺带把 JBPopup 的创建块抽成 showTogglePopup——任务详情、权限模式、
会话列表三处的参数完全一致，这是第三份拷贝出现的时候。

ClaudePanel 依赖平台类、起不了单测，验证方式是编译 + 全量测试仍绿 +
手工冒烟。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 10: `ClaudePanel` 接线 —— 切换与历史回放

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`

**Interfaces:**
- Consumes: Task 9 的全部私有成员；`Protocol.encodeLoadHistory`、`SidecarMessage.History`（Task 2）；`MessageRenderer.renderPrompt`（Task 5）；`StartParams.resumeSessionId`（Task 2）
- Produces: 无（终端任务）

**本任务没有单测**，理由同 Task 9。

- [ ] **Step 1: 把 resume 目标带进 start 参数**

在 `startSession()` 里，`Protocol.encodeStart(...)` 那一处（`ClaudePanel.kt:441-446`）改为：

```kotlin
                c.sendLine(
                    Protocol.encodeStart(
                        nextId(),
                        ClaudeSettings.getInstance(project).toStartParams(Path.of(base))
                            .copy(resumeSessionId = resumeTargetId),
                    )
                )
```

- [ ] **Step 2: 记录新会话的 id**

在 `onMessage` 的 `Event` 分支里，`init` 那处（`ClaudePanel.kt:536-538`）改为：

```kotlin
                    if (msg.event.str("subtype") == "init") {
                        msg.event.str("model")?.let { modelLabel.text = it }

                        // 真正的会话 id 只在这里。**不读 ready.sessionId** ——
                        // 那个回显的是请求参数，全新会话时是 null（spec §10）
                        msg.event.str("session_id")?.let { sid ->
                            currentSessionId = sid
                            sessionLabel.setTitle(sid.take(8), enabled = !busy)
                        }
                    }
```

- [ ] **Step 3: Ready 之后走回放**

`onMessage` 的 `Ready` 分支改为：

```kotlin
                is SidecarMessage.Ready -> {
                    ready = true
                    statusLabel.text = "已连接"
                    disconnected = false
                    refreshMainButton()

                    val resuming = resumeTargetId
                    if (resuming != null) {
                        // 恢复路径：id 构造即知，同时把它记成当前会话
                        currentSessionId = resuming
                        beginReplay(resuming)
                    } else {
                        pushOp(toOp(RenderItem.SystemNote("会话已就绪")))
                        sessionLabel.setTitle(null, enabled = true)

                        // 补发窗口就绪前暂存的首条消息
                        pendingFirstMessage?.let { text ->
                            pendingFirstMessage = null
                            client?.sendLine(Protocol.encodeSend(nextId(), text))
                            setBusy(true)
                        }
                    }
                }
```

- [ ] **Step 4: 实现 `switchToSession` 与 `beginReplay`**

替换 Task 9 里放的占位实现：

```kotlin
    /**
     * 切换到另一个历史会话。
     *
     * 步骤见 spec §5.2。**不复用 [restartSession]** —— 那个刻意保留转写历史
     * （见它的注释），而切换要的正是清空，语义相反。
     */
    private fun switchToSession(target: SessionInfo) {
        // 双保险：列表已经把忙时的点击拦住了，但那之后到真正执行之间
        // 状态可能变（比如又来了一个权限询问）
        val block = switchBlock(busy, permissionQueue.totalPending)
        if (block != SwitchBlock.None) {
            pushOp(toOp(RenderItem.SystemNote(switchBlockNotice(block) ?: return)))
            return
        }

        LOG.info("CCoder 切换会话：${target.sessionId}")
        stopSession()
        // 标题从列表里就知道，不必等 loadHistory
        val title = target.summary?.takeIf { it.isNotBlank() }
            ?: target.firstPrompt?.takeIf { it.isNotBlank() }
        sessionLabel.setTitle(title, enabled = true)
        resumeTargetId = target.sessionId
        startSession()
    }

    /**
     * 把历史灌进转写区。
     *
     * 历史条目与流式事件**同构**，所以整条渲染管线（含 toOp 的映射）
     * 原样复用，React 侧零改动（spec §6.1）。
     */
    private fun beginReplay(sessionId: String) {
        val c = client
        if (c == null) {
            failReplay("会话通道已关闭")
            return
        }
        val dir = project.basePath
        if (dir == null) {
            failReplay("项目没有 basePath")
            return
        }

        // 清空转写区。Reset 是既有操作，Kotlin 编码与 React 消费都已实现
        // 并有测试（codec.test.ts「reset 清空全部」）
        pushOp(TranscriptOp.Reset)
        statusLabel.text = "正在载入历史…"
        // 回放期间不接受输入：否则历史与实时消息会交错（spec §10 的风险项）
        setBusy(true)

        val reqId = nextId()
        c.request(reqId, Protocol.encodeLoadHistory(reqId, dir, sessionId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.History
                        if (msg == null) {
                            failReplay("历史接口返回了意外的消息")
                        } else {
                            replayItems(msg.items)
                        }
                    }

                    is RequestOutcome.Failed -> failReplay(outcome.reason)
                }
            }
        }
    }

    /**
     * 逐条灌入历史。
     *
     * 先试 [MessageRenderer.renderPrompt]（提问），再走 [MessageRenderer.render]
     * （其余）。顺序不能反：`render` 对 `type:"user"` 一律返回空，所以两条路
     * 不会重复产出。
     */
    private fun replayItems(items: List<JsonObject>) {
        var rendered = 0
        for (item in items) {
            MessageRenderer.renderPrompt(item)?.let {
                pushOp(toOp(RenderItem.UserText(it)))
                rendered++
            }
            val rest = MessageRenderer.render(SidecarMessage.Event(item))
            rest.forEach { pushOp(toOp(it)) }
            rendered += rest.size
        }

        resumeTargetId = null
        setBusy(false)
        statusLabel.text = "已连接"
        pushOp(toOp(RenderItem.SystemNote("已恢复会话 · ${items.size} 条历史，其中 $rendered 条可显示")))
    }

    /**
     * 回放失败。
     *
     * **不回退到新会话** —— 那会让用户以为历史加载好了（spec §7.5 反对静默
     * 丢失）。转写区保持在 Reset 之后的空白状态，并明确说明失败原因。
     */
    private fun failReplay(reason: String) {
        resumeTargetId = null
        setBusy(false)
        statusLabel.text = "恢复失败"
        sessionLabel.setTitle(null, enabled = true)
        pushOp(toOp(RenderItem.ErrorItem("恢复会话失败：$reason")))
    }
```

- [ ] **Step 5: 清理 `stopSession` 里的会话状态**

在 `stopSession()`（`ClaudePanel.kt:476-499`）的 `ready = false` 之前加：

```kotlin
        // 浮层挂在旧会话的列表上，会话没了它就该消失
        sessionPopup?.cancel()
        sessionPopup = null
```

**注意**：**不要**在 `stopSession()` 里清 `currentSessionId` —— `restartSession()` 会调它，而重开后还是同一个会话的延续（只是 fatal 重连）。

- [ ] **Step 6: 编译并跑全量测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --no-daemon 2>&1 | grep -E "error:|FAILED|BUILD"
```

预期：`BUILD SUCCESSFUL`，无 `error:`。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt && git commit -F - <<'EOF'
feat(ui): 会话切换与历史回放

回放复用既有渲染管线：历史条目与流式事件同构，所以 toOp 的映射一行
不用改，React 侧零改动。提问走新加的 renderPrompt，其余走 render ——
`render` 对 type:"user" 一律返回空，两条路不会重复产出。

当前会话 id 只从 system/init 事件取，不读 ready.sessionId（那个回显的是
请求参数，全新会话时是 null）。

回放失败时不回退到新会话——那会让用户以为历史加载好了。转写区保持在
Reset 后的空白状态并说明原因。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 11: 手工冒烟

**Files:**
- 不修改代码。若发现问题，回到对应任务修。

**为什么需要**：`ClaudePanel` 不可单测，回放要真的过 JCEF 桥，列表要真的读 `~/.claude/projects/`。

- [ ] **Step 1: 启动沙箱 IDE**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew runIde --no-daemon
```

- [ ] **Step 2: 冒烟 1 —— 列表能列出来**

打开 CCoder 工具窗口，点右上角的会话标签。

预期：弹出列表，按时间倒序，第一行是最近用过的会话，标题与时间都在。**时间必须完整可见**，不被长标题挤掉。

若列表为空或报错，先看 `runIde` 的控制台里有没有 `LIST_SESSIONS_FAILED`。

- [ ] **Step 3: 冒烟 2 —— 切换最大的那个会话**

在列表里找 `PyCharm插件调用Claude Code`（25.3MB，804 条）。

预期：
- 状态栏显示"正在载入历史…"，输入区不可用
- 转写区先清空，然后历史逐步填满
- 结束后显示"已恢复会话 · 804 条历史，其中 N 条可显示"
- 整个回放**应当在一两秒内完成**，不卡界面

**这条最要紧**：804 条 / 2.6MB 是实测的最大量级。若明显卡顿，回到 Task 6 调小 `DEFAULT_MAX_BATCH`。

- [ ] **Step 4: 冒烟 3 —— 恢复后能接着聊**

回放完成后输入一句话发送。

预期：能正常收到回复，且上下文是接着那个历史会话的（可以问"我刚才问了你什么"来验证）。

- [ ] **Step 5: 冒烟 4 —— 忙时被拦住**

起一个会长跑的回合（如"跑一下全部单测"），在回合进行中点会话标签。

预期：列表弹出、**整列置灰**、顶部有一行说明提到"先按「停止」"；点任意一行**没有反应**，会话不被切换。

- [ ] **Step 6: 冒烟 5 —— 恢复一个不存在的会话**

关掉 IDE，手工改一次 `~/.claude/projects/` 里的目录（或临时把某个 sessionId 在代码里改错），让 `resume` 失败。

预期：明确显示"恢复会话失败：…"，**不是**静默变成一个空白的新会话。

- [ ] **Step 7: 冒烟 6 —— 关掉重开**

关掉 IDE，重新 `runIde`。

预期：**是全新会话**（本版不自动恢复，spec §1.3），转写区空白，会话标签显示斜体的"新会话"；但列表里能找到刚才那个会话并恢复。

- [ ] **Step 8: 把结论写回 spec**

在 `docs/superpowers/specs/2026-09-12-session-switch-design.md` 的 §10 里勾掉「待实现时确认」，并记录冒烟发现的问题（若有）。

- [ ] **Step 9: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add docs/superpowers/specs/2026-09-12-session-switch-design.md && git commit -F - <<'EOF'
docs(spec): 补上手工冒烟结论

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 完成标准

全部 11 个任务完成后，下面每一条都应成立：

- [ ] `./gradlew test` 全绿；`cd sidecar && npm test` 全绿
- [ ] 工具窗口顶部右侧有一个会话标签，显示当前会话标题，新会话显示斜体"新会话"
- [ ] 点标签弹出列表，按时间倒序，当前会话有勾
- [ ] 选中一个历史会话后，转写区清空并填入历史，随后可以接着聊
- [ ] 回合进行中点列表，整列置灰且有一句说明
- [ ] 恢复失败时明确报错，不静默变成新会话
- [ ] 关掉 IDE 重开后是全新会话，但能从列表恢复旧会话
- [ ] React 侧代码零改动（`web/src/` 下没有 diff）
