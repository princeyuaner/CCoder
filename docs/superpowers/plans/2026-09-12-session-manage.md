# CCoder 会话管理（新建与删除）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给会话列表加上「删除」与「新建会话」两个动作 —— 列表行悬停出 ✕、行内二次确认；顶部状态栏最右加一个「＋」图标按钮。

**Architecture:** 沿用 `session-switch` 那份的全部约定（id 回显 + 待决表、纯函数抽判定、列表挂顶部状态栏）。本计划**不引入新组件层**，只在既有三个文件上增量：`Protocol.kt` 加一对编解码、`sidecar/index.js` 加一个分发、`SessionList.kt` 的行加两个交互、`ClaudePanel.kt` 接线。删除是唯一不可逆的操作，所以「点 ✕ 不能冒泡成切换会话」是本次唯一一个"写错了会误删"的点，有独立用例。

**Tech Stack:** Kotlin（IntelliJ Platform 插件，Swing）、Node ESM（sidecar）、Gradle + JUnit 5、`node --test`。

**Spec:** `docs/superpowers/specs/2026-09-12-session-manage-design.md`

**设计稿:** `docs/design/session-manage.html`

---

## ⚠️ 前置依赖

**本计划消费 `session-switch` 落地的产物。必须先执行完 `docs/superpowers/plans/2026-09-12-session-switch.md`。**

被消费的东西（名字必须逐字一致，本计划的代码直接调用它们）：

| 来自 | 形状 |
|---|---|
| `session-switch` Task 7 | `internal enum class SwitchBlock { None, TurnRunning, PermissionPending }` |
| 同上 | `internal fun switchBlock(busy: Boolean, pendingPermissions: Int): SwitchBlock` |
| 同上 | `internal fun switchBlockNotice(block: SwitchBlock): String?` |
| `session-switch` Task 8 | `internal fun buildSessionList(sessions: List<SessionInfo>, currentSessionId: String?, block: SwitchBlock, onPick: (SessionInfo) -> Unit = {}): JComponent` |
| 同上 | `internal class SessionLabel(private val onOpen: () -> Unit) : JLabel()`，方法 `setTitle(text: String?, enabled: Boolean)` |
| 同上 | `data class SessionInfo(val sessionId: String, val summary: String?, val firstPrompt: String?, val lastModified: Long)` |
| 同上 | `Protocol.responseIdOf(msg: SidecarMessage): String?` |
| 同上 | `SidecarClient.request(id, json, callback: (RequestOutcome) -> Unit)`，`RequestOutcome.Answered / Failed` |
| 同上 | `createDispatcher({ sessionFactory, out, sessionApi })`，`sessionApi` 形状 `{ listSessions, getSessionMessages }` |

## Global Constraints

- **开工前工作区必须干净**。当前 `v0.2.0-dev` 上有大量未提交的输入区改动（`PermissionCard.kt` / `ClaudePanel.kt` / `Composer*.kt` 等）。**先提交或另起分支** —— 本计划每个 Task 末尾都有一次提交，混着不相关的改动提交会污染历史。这一条 `session-switch` spec 的 §10 也写了。
- 所有文件路径用**完整绝对 Windows 路径**（`C:\Users\CY\Desktop\CCoder\...`）。
- **删除不可逆**：`deleteSession` 删的是 `{sessionId}.jsonl` 与 `{sessionId}/` 子代理目录，没有回收站（`sdk.d.ts:566-577`）。
- 注释、UI 文案、测试名一律中文，与既有代码一致。
- 不引入新依赖。

---

## 文件结构

```
修改
├── src/main/kotlin/com/ccoder/sidecar/Protocol.kt      + encodeDeleteSession / + sessionDeleted 解析 / responseIdOf 加分支
├── src/main/kotlin/com/ccoder/ui/SessionSwitchState.kt + sessionTitle / newSessionEnabled / newSessionTooltip / deleteConfirmPrompt
├── src/main/kotlin/com/ccoder/ui/SessionList.kt        + 悬停 ✕、行内确认态、onDelete 回调
├── src/main/kotlin/com/ccoder/ui/ClaudePanel.kt        + 右上角「＋」、删除流程、删当前会话后回新会话
├── sidecar/index.js                                    + deleteSession 分发
└── src/test/kotlin/com/ccoder/
    ├── sidecar/ProtocolTest.kt                         + 3 个用例
    └── ui/
        ├── SessionSwitchStateTest.kt                   + 6 个用例
        └── SessionListTest.kt                          + 6 个用例
新建
└── src/main/kotlin/com/ccoder/ui/SessionNewButton.kt   「＋」按钮（照 RoundSendButton 的形状）
    src/test/kotlin/com/ccoder/ui/SessionNewButtonTest.kt
sidecar/test/index.test.js                              + 3 个用例
```

---

### Task 1: 协议 —— 删除请求与回执

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\Protocol.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt`

**Interfaces:**
- Consumes: `SidecarMessage.SessionList` / `History`（来自 `session-switch` Task 1）
- Produces:
  - `SidecarMessage.SessionDeleted(val requestId: String, val sessionId: String)`
  - `Protocol.encodeDeleteSession(id: String, sessionId: String): String`
  - `Protocol.responseIdOf` 新增一个分支（`SessionDeleted` → `requestId`）

- [x] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt` 的 `class ProtocolTest` 内部（最后一个 `}` 之前）：

```kotlin
    // ---- 删除会话 ----

    @Test
    fun `encodeDeleteSession 带 id 与 sessionId`() {
        val line = Protocol.encodeDeleteSession("r7", "d9617553-1a2b-3c4d-5e6f-7890abcdef12")
        val obj = JsonParser.parseString(line.trim()).asJsonObject

        assertEquals("r7", obj.get("id").asString)
        assertEquals("deleteSession", obj.get("method").asString)
        assertEquals(
            "d9617553-1a2b-3c4d-5e6f-7890abcdef12",
            obj.getAsJsonObject("params").get("sessionId").asString,
        )
    }

    @Test
    fun `sessionDeleted 解析出 id 与 sessionId`() {
        val line = """{"type":"sessionDeleted","id":"r7","sessionId":"abc-123"}"""

        val msg = Protocol.parse(line)

        assertTrue(msg is SidecarMessage.SessionDeleted)
        assertEquals("r7", (msg as SidecarMessage.SessionDeleted).requestId)
        assertEquals("abc-123", msg.sessionId)
    }

    @Test
    fun `sessionDeleted 缺 sessionId 时丢弃`() {
        // 缺了它就不知道该把哪一行从列表里去掉 —— 留着只会让界面删错行
        assertNull(Protocol.parse("""{"type":"sessionDeleted","id":"r7"}"""))
    }

    @Test
    fun `responseIdOf 认得 sessionDeleted`() {
        // 不认的话这条回执就配不上对，删除会一直挂到超时
        assertEquals(
            "r7",
            Protocol.responseIdOf(SidecarMessage.SessionDeleted("r7", "abc-123")),
        )
    }
```

（`JsonParser` / `assertNull` / `assertTrue` / `assertEquals` 在该文件里已经 import 过；若缺 `assertNull` 补上 `import org.junit.jupiter.api.Assertions.assertNull`。）

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.sidecar.ProtocolTest" --console=plain 2>&1 | grep -E "error:|Unresolved|FAILED|BUILD" | head -20
```

预期：编译失败，报 `Unresolved reference: SessionDeleted` / `encodeDeleteSession`。

- [x] **Step 3: 实现**

**(a)** 在 `Protocol.kt` 的 `sealed interface SidecarMessage` 内部，`SessionList` / `History` 之后插入：

```kotlin
    /**
     * 删除会话的回执。
     *
     * `sessionDeleted` 缺 `sessionId` 时整条丢弃（见 [Protocol.parse]）——
     * 不知道删掉的是哪一个，就不知道该把哪一行从列表里去掉。
     */
    data class SessionDeleted(val requestId: String, val sessionId: String) : SidecarMessage
```

**(b)** 在 `object Protocol` 的 `parse` 里，`"history"` 分支之后插入：

```kotlin
            "sessionDeleted" -> {
                // 两个字段缺一不可：requestId 用来配对，sessionId 用来知道删了哪个
                val requestId = obj.str("id")
                val sessionId = obj.str("sessionId")
                if (requestId == null || sessionId == null) null
                else SidecarMessage.SessionDeleted(requestId, sessionId)
            }
```

**(c)** 在 `object Protocol` 里、`encodeStart` 之前插入：

```kotlin
    fun encodeDeleteSession(id: String, sessionId: String): String =
        line(id, "deleteSession", JsonObject().apply { addProperty("sessionId", sessionId) })
```

**(d)** 给 `responseIdOf` 加一个分支（这是 `session-switch` Task 1 建的函数）：

```kotlin
    fun responseIdOf(msg: SidecarMessage): String? = when (msg) {
        is SidecarMessage.SessionList -> msg.requestId
        is SidecarMessage.History -> msg.requestId
        is SidecarMessage.SessionDeleted -> msg.requestId   // ← 新增
        else -> null
    }
```

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.sidecar.ProtocolTest" --console=plain 2>&1 | tail -5
```

预期：`BUILD SUCCESSFUL`。核对新增用例数：`build/test-results/test/TEST-com.ccoder.sidecar.ProtocolTest.xml` 里的 `tests=` 比改动前多 4。

- [x] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/Protocol.kt src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt && git commit -F - <<'EOF'
feat(sidecar): 协议加删除会话的请求与回执

deleteSession 与 sessionDeleted 一对。回执带 requestId 与 sessionId：
前者用来和待决表配对，后者用来知道该把哪一行从列表里去掉 —— 缺一不可，
所以两个字段缺任何一个都整条丢弃。

responseIdOf 同步加上分支，否则这条回执配不上对，删除会挂到超时。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 2: sidecar —— deleteSession 分发

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\sidecar\index.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js`

**Interfaces:**
- Consumes: `createDispatcher({ sessionFactory, out, sessionApi })`（来自 `session-switch` Task 3）
- Produces: `sessionApi` 形状扩为 `{ listSessions, getSessionMessages, deleteSession }`；分发方法名 `deleteSession`

**关键约束：没有活会话时也必须能应答。** 删除是列表上的动作，不该要求先起会话 —— 与 `listSessions` 同一条理由。

- [x] **Step 1: 写失败的测试**

在 `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js` 里，`session-switch` 建的 `fakeSessionApi()` 辅助函数（`listSessions` / `getSessionMessages` 那个）上加一项，并在文件末尾追加三个用例：

```js
// fakeSessionApi 的返回值里加：
//   deleteSession: async (opts) => { calls.push(['deleteSession', opts]); ... }
// 具体照该文件里 listSessions 的既有写法补一行，形状保持一致。
```

```js
test('deleteSession 不需要活会话也能应答', () => {
  // 删除是列表上的动作，不该要求先起会话 —— 同 listSessions
  const fa = fakeSessionApi();
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'deleteSession', params: { sessionId: 'sess-9' } });

  assert.equal(fa.calls.length, 1);
  assert.deepEqual(fa.calls[0][1], { sessionId: 'sess-9' });
});

test('删除成功回 sessionDeleted，且带 id 回显', async () => {
  const fa = fakeSessionApi();
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r7', method: 'deleteSession', params: { sessionId: 'sess-9' } });
  await new Promise((r) => setImmediate(r));   // 等异步分发落地

  const ack = out.find((m) => m.type === 'sessionDeleted');
  assert.ok(ack, '没有回 sessionDeleted');
  assert.equal(ack.id, 'r7');
  assert.equal(ack.sessionId, 'sess-9');
});

test('删除失败回 error，且不回 sessionDeleted', async () => {
  // 会话已经被终端删掉时 SDK 会抛错。必须如实报出去 ——
  // 假装删成功了，界面上那一行就会消失，而那是在撒谎
  const fa = fakeSessionApi({ deleteFails: true });
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r8', method: 'deleteSession', params: { sessionId: 'gone' } });
  await new Promise((r) => setImmediate(r));

  assert.ok(out.some((m) => m.type === 'error' && m.code === 'DELETE_FAILED'));
  assert.ok(!out.some((m) => m.type === 'sessionDeleted'), '失败了不该回成功回执');
});
```

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test test/index.test.js 2>&1 | tail -20
```

预期：三个新用例失败 —— 分发里没有 `deleteSession` 分支，会走到 `UNKNOWN_METHOD`。

- [x] **Step 3: 实现**

在 `sidecar/index.js` 的 `handle` 的 `switch` 里，`loadHistory` 分支之后插入：

```js
      case 'deleteSession': {
        // 与 listSessions 同一条：不需要活会话。删除是列表上的动作，
        // 要求先起会话等于让用户在删东西之前先建立连接 —— 没道理
        const sessionId = params.sessionId;
        if (typeof sessionId !== 'string' || sessionId === '') {
          fail('DELETE_FAILED', '删除会话缺少 sessionId', false);
          return session;
        }
        const call = sessionApi?.deleteSession;
        if (typeof call !== 'function') {
          fail('DELETE_FAILED', '当前 sidecar 不支持删除会话', false);
          return session;
        }
        Promise.resolve(call.call(sessionApi, { sessionId }))
          .then(() => out({ type: 'sessionDeleted', id: msg.id, sessionId }))
          .catch((err) => fail('DELETE_FAILED', String(err?.message ?? err), false));
        return session;
      }
```

同时把 `createDispatcher` 的签名注释里的 `sessionApi` 形状补上 `deleteSession`：

```js
 * @param {object}   [deps.sessionApi]  会话级 API 注入点 `{ listSessions, getSessionMessages, deleteSession }`
```

并在 `main()` 里把 SDK 的 `deleteSession` 接进 `sessionApi`（照 `session-switch` 里 `listSessions` 的接线位置，同一个对象里加一行）：

```js
        deleteSession: (opts) => sdk.deleteSession(opts.sessionId, { dir: opts.dir }),
```

（`sdk` 即 `import * as sdk from '@anthropic-ai/claude-agent-sdk'`；若 `session-switch` 用的是具名 import，照它的写法加 `deleteSession` 即可。）

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test 2>&1 | tail -8
```

预期：全部通过，`# fail 0`。

- [x] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/index.js sidecar/test/index.test.js && git commit -F - <<'EOF'
feat(sidecar): 分发 deleteSession

与 listSessions 同一条：不需要活会话。删除是列表上的动作，要求先起会话
等于让用户在删东西之前先建立连接。

失败一律回 DELETE_FAILED 而不是静默 —— 假装删成功了，界面上那一行就会
消失，而那是在撒谎（见设计稿 §4.4）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 3: `SessionSwitchState` 扩充 —— 新建与删除的纯判定

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionSwitchState.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt`

**Interfaces:**
- Consumes: `SwitchBlock` / `switchBlock` / `switchBlockNotice`（同文件，`session-switch` Task 7）；`SessionInfo`（`com.ccoder.sidecar`）
- Produces:
  - `internal fun sessionTitle(session: SessionInfo): String`
  - `internal fun newSessionEnabled(block: SwitchBlock): Boolean`
  - `internal fun newSessionTooltip(block: SwitchBlock): String`
  - `internal fun deleteConfirmPrompt(session: SessionInfo, isCurrent: Boolean): String`

**为什么 `sessionTitle` 放这里**：它现在内联在 `SessionList.kt` 的 `sessionRow` 里。删除确认语要用同一个标题 —— 两处各写一遍，改了一处漏一处，就会出现"确认语说的名字和那一行显示的不是同一个"。本轮顺手抽出来，`sessionRow` 改为调用它。

- [x] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt` 的 `class SessionSwitchStateTest` 内部：

```kotlin
    // ---- 新建会话 ----

    @Test
    fun `空闲时才能新建`() {
        assertTrue(newSessionEnabled(SwitchBlock.None))
    }

    @Test
    fun `忙时不能新建`() {
        // 与切换会话拦的是同一件事：新建同样要 stopSession()，
        // 会把正在跑的回合腰斩
        assertFalse(newSessionEnabled(SwitchBlock.TurnRunning))
        assertFalse(newSessionEnabled(SwitchBlock.PermissionPending))
    }

    @Test
    fun `能点时提示说的是它做什么，不能点时说的是先做什么`() {
        assertEquals("新建会话", newSessionTooltip(SwitchBlock.None))

        val blocked = newSessionTooltip(SwitchBlock.TurnRunning)
        assertTrue(blocked.contains("停止"), "实际：$blocked")
    }

    // ---- 标题与删除确认语 ----

    private fun info(summary: String? = null, firstPrompt: String? = null) =
        SessionInfo("s1", summary, firstPrompt, 0L)

    @Test
    fun `标题三级降级`() {
        assertEquals("这是摘要", sessionTitle(info(summary = "这是摘要", firstPrompt = "首问")))
        assertEquals("首问", sessionTitle(info(summary = "  ", firstPrompt = "首问")))
        assertEquals("（无标题）", sessionTitle(info(summary = "", firstPrompt = null)))
    }

    @Test
    fun `删普通会话时确认语点出是哪一个`() {
        val prompt = deleteConfirmPrompt(info(summary = "这是什么项目"), isCurrent = false)
        assertTrue(prompt.contains("这是什么项目"), "实际：$prompt")
    }

    @Test
    fun `删当前会话时确认语说的是后果`() {
        // 用户已经知道自己点了哪一行；他需要知道的是"删掉之后会发生什么" ——
        // 转写区会清空、回到新会话
        val prompt = deleteConfirmPrompt(info(summary = "这是什么项目"), isCurrent = true)
        assertTrue(prompt.contains("当前"), "实际：$prompt")
        assertTrue(prompt.contains("清空"), "实际：$prompt")
    }
```

补 import：`org.junit.jupiter.api.Assertions.assertFalse`、`com.ccoder.sidecar.SessionInfo`。

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionSwitchStateTest" --console=plain 2>&1 | grep -E "error:|Unresolved|FAILED" | head -10
```

预期：编译失败，报 `Unresolved reference: newSessionEnabled` / `sessionTitle` / `deleteConfirmPrompt`。

- [x] **Step 3: 实现**

追加到 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionSwitchState.kt` 末尾：

```kotlin
/**
 * 列表行的标题。summary 优先，退回 firstPrompt，都没有给占位。
 *
 * 空白行看起来像渲染坏了，不如直说。列表行与删除确认语共用这一个 ——
 * 两处各写一遍，改一处漏一处，确认语里说的名字就会和那一行显示的不是同一个。
 */
internal fun sessionTitle(session: SessionInfo): String =
    session.summary?.takeIf { it.isNotBlank() }
        ?: session.firstPrompt?.takeIf { it.isNotBlank() }
        ?: "（无标题）"

/**
 * 「＋」能不能点。
 *
 * 与切换会话拦的是同一件事：新建同样要 stopSession()，会把正在跑的回合腰斩，
 * 挂着的权限询问也会一并作废。所以直接复用 [switchBlock] 的判定。
 */
internal fun newSessionEnabled(block: SwitchBlock): Boolean = block == SwitchBlock.None

/**
 * 「＋」的提示语。
 *
 * 能点时说明它做什么，不能点时说明**先做什么** —— 光说"不能新建"没用。
 * 不能点那种情况直接复用 [switchBlockNotice]，两句提示不必各写一份。
 */
internal fun newSessionTooltip(block: SwitchBlock): String =
    switchBlockNotice(block) ?: "新建会话"

/**
 * 删除的确认语。
 *
 * 删当前会话时**不写"要不要删"，写"删了会怎样"** —— 用户已经知道自己点了
 * 哪一行，他需要知道的是后果：转写区会清空、回到新会话。
 *
 * 注意这里不负责截断：行宽由布局决定，长标题在组件里省略。
 */
internal fun deleteConfirmPrompt(session: SessionInfo, isCurrent: Boolean): String =
    if (isCurrent) {
        "这是当前对话。删除后转写区会清空，回到新会话。"
    } else {
        "删除「${sessionTitle(session)}」？"
    }
```

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionSwitchStateTest" --console=plain 2>&1 | tail -5
```

预期：`BUILD SUCCESSFUL`，该文件用例数比改动前多 6。

- [x] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/SessionSwitchState.kt src/test/kotlin/com/ccoder/ui/SessionSwitchStateTest.kt && git commit -F - <<'EOF'
feat(ui): 新建与删除的纯判定

三个判定照 MainButtonState 的做法抽成纯函数：ClaudePanel 依赖平台类起不了
单测，所以判定与画分开。

顺手把列表行的标题抽成 sessionTitle：删除确认语要用同一个标题，两处各写
一遍迟早漂移，确认语里说的名字就会和那一行显示的不是同一个。

删当前会话的确认语不写"要不要删"，写"删了会怎样" —— 用户已经知道自己点了
哪一行。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 4: 列表行 —— 悬停出 ✕、行内二次确认

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionList.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionListTest.kt`

**Interfaces:**
- Consumes: `sessionTitle` / `deleteConfirmPrompt`（Task 3）、`SwitchBlock`（`session-switch` Task 7）
- Produces:
  - `buildSessionList` 的签名扩为 `buildSessionList(sessions, currentSessionId, block, onPick = {}, onDelete: (SessionInfo) -> Unit = {}, currentSessionIdOf: (SessionInfo) -> Boolean = ...)`

> **状态：已完成（2026-09-12）** —— 全量 392 / 0 失败。
>
> **执行记录 —— 挖出一个静默的生产 bug，是计划自己写出来的：**
>
> **① 尾随 lambda 绑到了错误的参数。** 计划把 `onDelete` 加在 `onPick` **后面**，
> 并声称"保持既有调用点不破"。**恰恰相反**：Kotlin 的尾随 lambda 绑的是**最后一个参数**，
> 于是所有既有的 `buildSessionList(s, id, block) { ... }` **静默地**从"选中回调"
> 变成了"删除回调"——
>
> - 生产路径 `ClaudePanel.showSessionPopup` 里那个 → **点会话行什么都不会发生**，且不报错
> - `SessionListTest.空闲时每行都可点` 同理，所以它红了
>
> 修法不是去改调用点，而是**调换参数顺序**（`onDelete` 在前、`onPick` 保持最后），
> 既有的尾随 lambda 调用点一个字不用动，含义自动恢复。注释里写清楚了为什么是这个顺序。
>
> 诊断过程记一笔：`clickableRows` 报"3 行都挂着监听器"、行的类型/启用状态全对，
> 但回调就是不触发 —— 说明问题不在监听器而在**传进去的那个 lambda 是哪一个**。
>
> **② 子组件的悬停转发必须收进 `if (clickable)`。** 计划写的是无条件挂，但
> 既有用例 `忙时行根本不挂点击响应` 断言的正是"忙时没有任何点击响应" —— 红了。
> 收进 `if (clickable)` 之后两件事同时成立。
>
> **③ 测试里 `clickableRows` 的定义收紧了。** 行内部现在也有交互子件（悬停用的 ✕），
> 原来"递归找所有带监听器的容器"会把它们也数进来。改成只看列表的**直接子项**。
>
> **④ 计划里的 `allowDelete` 参数被去掉**：它和 `clickable` 恒等（都是 `block == None`），
> 两个恒等的参数是漂移的温床。
>
> **⑤ 测试里的 `labelsIn` 改成了这个文件既有的 `textsIn`** —— 同一个东西两个名字没必要。
>
> **给后续 Task 的教训**：给已有函数**追加参数**时，先看它有没有被**尾随 lambda** 调用。

**签名扩法**（保持既有调用点不破）：

```kotlin
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
    onPick: (SessionInfo) -> Unit = {},
    onDelete: (SessionInfo) -> Unit = {},   // ← 新增：用户**确认**删除后调用
): JComponent
```

`isCurrent` 由 `s.sessionId == currentSessionId` 算，不需要新参数。

**三处必须做对的地方**（设计稿 §4.2）：

1. **✕ 的点击不能冒泡成 `onPick`**。整行可点，✕ 在行内 —— 写错了就是"点删除先切过去"。
2. **同一时刻只允许一行处于确认态**。用一个 `ConfirmSlot` 持有"当前确认中的那一行如何收回"。
3. **焦点落在「取消」**。沿用 `PermissionCard.kt:26-28` 那条规则。

- [x] **Step 1: 写失败的测试**

追加到 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionListTest.kt`（文件由 `session-switch` Task 8 创建）：

```kotlin
    // ---- 删除 ----

    private val twoSessions = listOf(
        SessionInfo("s1", "还可以做什么功能", null, 1_000L),
        SessionInfo("s2", "这是什么项目", null, 2_000L),
    )

    /** 找一棵组件树里所有 JButton。 */
    private fun buttonsIn(root: Container): List<JButton> {
        val out = mutableListOf<JButton>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JButton) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun labelsIn(root: Container): List<String> {
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

    /** 一行的 ✕。列表里每行一个，按顺序取。 */
    private fun deleteButtonOf(list: JComponent, index: Int) =
        buttonsIn(list).filter { it.text == DELETE_MARK }[index]

    private fun hover(component: Component, entered: Boolean) {
        component.dispatchEvent(
            MouseEvent(
                component,
                if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, 5, 5, 0, false,
            )
        )
    }

    @Test
    fun `✕ 平时藏着，悬停到这一行才出现`() {
        // 列表最干净、误点率最低（设计稿 §二 A）。hover 的是**行**，
        // 不是 ✕ 自己 —— 否则鼠标一移过去它就消失了
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)
        val row = list.components.filterIsInstance<JComponent>()[0]
        val x = deleteButtonOf(list, 0)

        assertFalse(x.isVisible, "没悬停时 ✕ 不该露出来")

        hover(row, entered = true)
        assertTrue(x.isVisible, "悬停后 ✕ 没出现")

        hover(row, entered = false)
        assertFalse(x.isVisible, "移开后 ✕ 没收回")
    }

    @Test
    fun `悬停不会让时间标签左右跳`() {
        // ✕ 藏在固定宽度的槽里。若直接把它从布局里拿掉拿进，
        // 时间标签会左右跳一下 —— 那是能看见的抖动
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)
        val row = list.components.filterIsInstance<JComponent>()[0]
        val before = row.preferredSize.width

        hover(row, entered = true)

        assertEquals(before, row.preferredSize.width, "悬停后行宽变了")
    }

    @Test
    fun `点 ✕ 进入确认态，而且不触发切换`() {
        // 这是本次唯一一个"写错了会误删"的点：整行可点、✕ 在行内，
        // 事件冒泡上去就会先切过去，然后你可能正在删一个刚被激活的会话
        var picked: SessionInfo? = null
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onPick = { picked = it },
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()

        assertNull(picked, "点 ✕ 竟然触发了切换会话")
        assertNull(deleted, "确认之前不该真的删")
        assertTrue(
            labelsIn(list).any { it.contains("删除") },
            "没有进入确认态：${labelsIn(list)}",
        )
    }

    @Test
    fun `确认后才回调 onDelete`() {
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()
        // 确认行上的"删除"按钮：确认态那两个按钮之一，文字是"删除"
        buttonsIn(list).first { it.text == "删除" }.doClick()

        assertEquals("s1", deleted?.sessionId)
    }

    @Test
    fun `同一时刻只有一行处于确认态`() {
        // 点了 A 行的 ✕ 又去点 B 行的 ✕，A 行必须收回原样 ——
        // 否则界面上同时挂着两个待确认的删除
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)

        deleteButtonOf(list, 0).doClick()
        // A 行进了确认态，它自己的 ✕ 已经不在树里了 —— 现在只剩 B 行那一个
        deleteButtonOf(list, 0).doClick()

        val confirmRows = labelsIn(list).count { it.contains("删除「") }
        assertEquals(1, confirmRows, "同时存在多个确认态：${labelsIn(list)}")
    }

    @Test
    fun `取消后回到原样，且没有回调`() {
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()
        buttonsIn(list).first { it.text == "取消" }.doClick()

        assertNull(deleted)
        assertTrue(
            labelsIn(list).any { it.contains("还可以做什么功能") },
            "取消后标题没回来：${labelsIn(list)}",
        )
    }

    @Test
    fun `删当前会话时确认语说的是后果`() {
        val list = buildSessionList(twoSessions, currentSessionId = "s1", block = SwitchBlock.None)

        deleteButtonOf(list, 0).doClick()

        assertTrue(
            labelsIn(list).any { it.contains("清空") },
            "删当前会话没说明后果：${labelsIn(list)}",
        )
    }
```

补 import：`javax.swing.JButton`、`org.junit.jupiter.api.Assertions.assertNull` / `assertFalse`、`com.ccoder.sidecar.SessionInfo`、`java.awt.Component`、`java.awt.event.MouseEvent`。

**注意**：`DELETE_MARK` 是 Task 4 Step 3 要定义的常量（`internal const val DELETE_MARK = "✕"`），与 `MARK`（`ComposerMode.kt:18`）同一个做法：实现与测试共用一份，免得两边各写一个字符然后漂移。

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionListTest" --console=plain 2>&1 | grep -E "error:|Unresolved|FAILED" | head -10
```

预期：编译失败，报 `Unresolved reference: DELETE_MARK` 等。

- [x] **Step 3: 实现**

**(a)** 在 `SessionList.kt` 顶部（`buildSessionList` 之前）加常量与确认态协调器：

```kotlin
/** 行尾删除按钮的字符。实现与测试共用，免得两边各写一个字符然后漂移。 */
internal const val DELETE_MARK = "✕"

/**
 * 同一时刻只允许一行处于确认态。
 *
 * 每行进入确认态前先调 [swap]，它会收回上一行 —— 否则界面上会同时挂着
 * 两个待确认的删除，而删除是不可逆的。
 */
private class ConfirmSlot {
    private var restore: (() -> Unit)? = null

    fun swap(restorePrevious: () -> Unit) {
        restore?.invoke()
        restore = restorePrevious
    }
}
```

**(b)** `buildSessionList` 改成传 `onDelete` 与一个共享的 slot：

```kotlin
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
    onPick: (SessionInfo) -> Unit = {},
    onDelete: (SessionInfo) -> Unit = {},
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
    val confirmSlot = ConfirmSlot()
    sessions.forEach { s ->
        root.add(
            sessionRow(
                session = s,
                selected = s.sessionId == currentSessionId,
                clickable = block == SwitchBlock.None,
                nowMs = now,
                onPick = onPick,
                onDelete = onDelete,
                allowDelete = block == SwitchBlock.None,
                confirmSlot = confirmSlot,
            )
        )
    }
    return root
}
```

**(c)** `sessionRow` 改成可切换「正常 / 确认」两种内容：

```kotlin
private fun sessionRow(
    session: SessionInfo,
    selected: Boolean,
    clickable: Boolean,
    nowMs: Long,
    onPick: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
    allowDelete: Boolean,
    confirmSlot: ConfirmSlot,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，deriveFont 会 NPE
    // （RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
    }

    val title = sessionTitle(session)

    val mark = JLabel(if (selected) MARK else " ").apply { font = base }
    val markSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
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

    // ✕ 藏在固定宽度的槽里：直接拿进拿出布局会让时间标签左右跳一下。
    // 监听器在下面函数定义之后再挂（局部函数不支持前向引用）
    val deleteButton = JButton(DELETE_MARK).apply {
        font = base
        isVisible = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        toolTipText = "删除这个会话"
        margin = JBUI.emptyInsets()
        foreground = UIUtil.getInactiveTextColor()
    }
    val deleteSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        preferredSize = JBUI.size(16, base.size)
        add(deleteButton, BorderLayout.WEST)
    }

    val tail = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        add(timeLabel)
        add(deleteSlot)
    }

    fun showNormal() {
        row.removeAll()
        row.add(markSlot, BorderLayout.WEST)
        row.add(titleLabel, BorderLayout.CENTER)
        row.add(tail, BorderLayout.EAST)
        deleteButton.isVisible = false
        row.revalidate()
        row.repaint()
    }

    fun enterConfirm() {
        confirmSlot.swap { showNormal() }

        val prompt = JLabel(deleteConfirmPrompt(session, isCurrent = selected)).apply { font = base }
        val cancel = JButton("取消").apply {
            font = base
            isFocusable = false
            addActionListener { showNormal() }
        }
        val confirm = JButton("删除").apply {
            font = base
            isFocusable = false
            addActionListener { onDelete(session) }
        }

        row.removeAll()
        row.add(prompt, BorderLayout.CENTER)
        row.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(cancel)
                add(confirm)
            },
            BorderLayout.EAST,
        )
        row.revalidate()
        row.repaint()

        // 焦点落在「取消」：删除不可逆，默认焦点绝不能停在「删除」上
        // （PermissionCard.kt:26-28 同一条规则）
        SwingUtilities.invokeLater { cancel.requestFocusInWindow() }
    }

    // 挂在函数定义之后 —— Kotlin 的局部函数不支持前向引用，
    // 写进上面那个 apply 里会编不过
    deleteButton.addActionListener { enterConfirm() }

    showNormal()

    if (clickable) {
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // ✕（以及确认行上的按钮）不冒泡成"切换会话" ——
                    // 不拦的话点删除会先切过去，然后你可能正在删一个刚被激活的会话
                    val src = e.component
                    if (src is JButton) return
                    onPick(session)
                }

                override fun mouseEntered(e: MouseEvent) {
                    if (allowDelete) deleteButton.isVisible = true
                }

                override fun mouseExited(e: MouseEvent) {
                    deleteButton.isVisible = false
                }
            }
        )
    }

    if (!clickable) row.isEnabled = false
    return row
}
```

**注意 `showNormal()` 的调用时机**：它必须在 `row.add(..., BorderLayout.EAST)` 之前定义好 `tail`。上面把 `showNormal` 定义在 `tail` 之后，顺序是对的。

**(d)** 在 `buildSessionList` 里，行悬停时还要把 ✕ 带出来 —— 上面 `mouseEntered` 已经做了。**但 `mouseEntered` 只在鼠标进入 `row` 本身时触发**；鼠标移到 `timeLabel` 或 `deleteSlot` 上时 `row` 会收到 `mouseExited`，✕ 就消失了，鼠标一动就闪。修法是让子孙组件也把进入/离开转发给 row：

```kotlin
    // 子孙组件上的进入/离开同样要算作"在这一行上" ——
    // 否则鼠标移到时间标签上时 row 收到 mouseExited，✕ 一闪就没了
    listOf(markSlot, titleLabel, tail, timeLabel, deleteSlot).forEach { child ->
        child.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (allowDelete) deleteButton.isVisible = true
                }
                override fun mouseExited(e: MouseEvent) {
                    // 只有真正离开整行才收回：轮询鼠标位置落在不在 row 里
                    SwingUtilities.invokeLater {
                        val p = row.mousePosition
                        val inside = p != null && p.x in 0 until row.width && p.y in 0 until row.height
                        if (!inside) deleteButton.isVisible = false
                    }
                }
            }
        )
    }
```

**测试对应的是 `row` 自身的 `mouseEntered`/`mouseExited`**（`hover(row, entered = true)`），所以这段子孙转发是给真实鼠标用的，不额外写用例 —— 它属于观感，靠手工冒烟（§6.3 第 5 条）确认。

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionListTest" --console=plain 2>&1 | tail -5
```

预期：`BUILD SUCCESSFUL`，该文件用例数比改动前多 7。

**若 `点 ✕ 进入确认态，而且不触发切换` 失败**：说明 `e.component is JButton` 这道守卫没生效（Swing 在 `doClick()` 路径下 `e.component` 是那个 JButton）。改用 `SwingUtilities.isDescendingFrom(e.component, deleteSlot)` 判断即可 —— **不要让该用例变成断言"能切换"**，那条路径就是设计稿 §4.2(a) 要拦的。

- [x] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/SessionList.kt src/test/kotlin/com/ccoder/ui/SessionListTest.kt && git commit -F - <<'EOF'
feat(ui): 会话列表行加删除入口与行内确认

悬停出 ✕，点了原地变问句，焦点落在取消。三处按设计稿 §4.2 做：

1. ✕ 的点击不冒泡成切换会话 —— 整行可点、✕ 在行内，不拦的话点删除
   会先切过去，然后你可能正在删一个刚被激活的会话
2. 同一时刻只有一行处于确认态（ConfirmSlot 收回上一行）
3. 焦点落在取消，沿用 PermissionCard 那条"不可逆动作不能被误触"的规则

✕ 藏在固定宽度的槽里，悬停时行宽不变，时间标签不会左右跳。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 5: 「＋」新建按钮

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionNewButton.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionNewButtonTest.kt`

**Interfaces:**
- Consumes: `newSessionEnabled` / `newSessionTooltip`（Task 3）、`SwitchBlock`（`session-switch` Task 7）
- Produces: `internal class SessionNewButton(private val onClick: () -> Unit) : JButton`，方法 `setBlock(block: SwitchBlock)`

- [x] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionNewButtonTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 状态栏最右的「＋」。
 *
 * 它和会话标签的区别是：标签点了有东西可看（弹出列表），而它是个单一动作
 * 按钮 —— 点了没反应更像坏了。所以忙时是**置灰**而不是"点了才说"
 * （设计稿 §3.2）。
 */
class SessionNewButtonTest {

    @Test
    fun `空闲时可点`() {
        val b = SessionNewButton {}
        b.setBlock(SwitchBlock.None)
        assertTrue(b.isEnabled)
    }

    @Test
    fun `忙时置灰`() {
        val b = SessionNewButton {}

        b.setBlock(SwitchBlock.TurnRunning)
        assertFalse(b.isEnabled, "回合进行中不该能新建")

        b.setBlock(SwitchBlock.PermissionPending)
        assertFalse(b.isEnabled, "有权限挂着时不该能新建")
    }

    @Test
    fun `不能点时说清先做什么`() {
        val b = SessionNewButton {}

        b.setBlock(SwitchBlock.None)
        assertEquals("新建会话", b.toolTipText)

        b.setBlock(SwitchBlock.TurnRunning)
        assertNotNull(b.toolTipText)
        assertTrue(b.toolTipText.contains("停止"), "实际：${b.toolTipText}")
    }

    @Test
    fun `点击把动作报出去`() {
        var clicks = 0
        val b = SessionNewButton { clicks++ }

        b.doClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `置灰时点不动`() {
        var clicks = 0
        val b = SessionNewButton { clicks++ }
        b.setBlock(SwitchBlock.TurnRunning)

        b.doClick()

        assertEquals(0, clicks, "置灰了还能点")
    }
}
```

- [x] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionNewButtonTest" --console=plain 2>&1 | grep -E "error:|Unresolved|FAILED" | head -10
```

预期：编译失败，`Unresolved reference: SessionNewButton`。

- [x] **Step 3: 实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionNewButton.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import javax.swing.JButton

/**
 * 状态栏最右的「＋」。
 *
 * ## 为什么是图标不是文字
 *
 * 这一行本来就有状态文字和会话名，420px 下面板真的挤。图标最省地方，
 * 语义靠 tooltip 补（设计稿 §一 A）。
 *
 * ## 为什么忙时是置灰，不是"点了才说"
 *
 * 会话标签走的是"点了才说"（弹出后才给说明），因为它有内容可看。
 * 而「＋」是一个单一动作按钮 —— 点了没反应更像坏了。所以它直接置灰，
 * 并把"先做什么"写进 tooltip。
 *
 * 只负责显示与点击；现在是不是忙、能不能点，由 [newSessionEnabled] 判定。
 */
internal class SessionNewButton(private val onClick: () -> Unit) : JButton("＋") {

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(15f)
        addActionListener { onClick() }
        setBlock(SwitchBlock.None)
    }

    /**
     * 按忙闲刷新。**判定与画分开** —— ClaudePanel 起不了单测，
     * 所以 [newSessionEnabled] 是纯函数，这里只把结果画出来。
     */
    fun setBlock(block: SwitchBlock) {
        isEnabled = newSessionEnabled(block)
        toolTipText = newSessionTooltip(block)
        foreground = if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
    }
}
```

- [x] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests "com.ccoder.ui.SessionNewButtonTest" --console=plain 2>&1 | tail -5
```

预期：`BUILD SUCCESSFUL`，5 个用例全绿。

- [x] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/SessionNewButton.kt src/test/kotlin/com/ccoder/ui/SessionNewButtonTest.kt && git commit -F - <<'EOF'
feat(ui): 状态栏加「＋」新建会话按钮

图标而非文字：这一行已经有状态文字和会话名，420px 下面板真的挤。

忙时置灰而不是"点了才说" —— 会话标签走"点了才说"是因为它弹出后有内容
可看，而「＋」是单一动作按钮，点了没反应更像坏了。

判定与画分开：ClaudePanel 起不了单测，所以 newSessionEnabled 是纯函数。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 6: `ClaudePanel` 接线

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`

**Interfaces:**
- Consumes: 前五个 Task 的全部产物 + `session-switch` 的 `showTogglePopup` / `SessionLabel` / 待决表
- Produces: 无（终端节点，界面行为）

**为什么没有单测**：`ClaudePanel` 依赖 `ToolWindowManager` / `ApplicationManager`，`build.gradle.kts:34` 明确不引平台测试框架。所以这里只做接线，判定一律走前五个 Task 的纯函数，靠 Step 2 的手工冒烟验证。

> **状态：已完成（2026-09-12）** —— 全量 400 / 0 失败，sidecar 89 / 0 失败。
>
> **执行记录 —— 离屏渲染又逮到一个真布局 bug：**
>
> 计划里的 `top` 是 `[WEST=已连接] + [EAST=FlowLayout(标签, ＋)]`。看着合理，
> 但 `BorderLayout` 的 EAST 按**首选宽度**占位 —— 长会话标题要多少给多少，
> **直接把「已连接」压过去**。渲染出来两者叠在一起。
>
> 而 spec §2.3 明确写过：「超过宽度就省略号截断 —— 状态栏这一行不能因为一个长标题
> 把「已连接」挤掉」。**这条要求被违反了，单测看不出来**（它只管属性，不管位置）。
>
> 修法：右边改成嵌套的 `BorderLayout` —— 标签待在 `CENTER` 里只拿剩余宽度
> （超了 `JLabel` 自己打省略号），「已连接」和「＋」都是定宽谁也推不走谁；
> `SessionLabel` 自己右对齐，所以仍然贴着「＋」。
>
> 三种标题长度都渲染看过：短、24 字（全列表最长）、66 字（整段首问当摘要）——都正常。
> 探针：`TopRowRenderProbe`，产物 `build/top-row-probe*.png`。
>
> 另两处小偏离：
> - 计划里引用的 `sessions` 字段 / `refreshSessionList()` 在 Task 9 的实现里不存在
>   （列表是作为**参数**传进 `showSessionPopup` 的）。补了 `sessionListCache` 字段
>   与 `refreshSessionList()`。
> - 把「换一个空会话」抽成 `startNewSession()`：删当前会话与点「＋」本来就是同一件事
>   （设计稿 §4.3 也是这么写的），两处各写一遍迟早不一样。

- [x] **Step 1: 接线**

**(a)** 字段区（`SessionLabel` 附近）加：

```kotlin
    /** 最右的「＋」。与会话标签同处一行 —— 标签在它左边（设计稿 §一 A）。 */
    private val newSessionButton = SessionNewButton { onNewSession() }

    /** 删除请求发出后暂存的 id，等回执时用它决定从列表里摘哪一行。 */
    private var deletingSessionId: String? = null
```

**(b)** 会话列表弹层的锚点行里，把 `newSessionButton` 放在 `SessionLabel` **之后**（靠最右）：

```kotlin
    // 靠右的顺序：会话标签在左，「＋」独占最右角
    anchor.add(sessionLabel, BorderLayout.CENTER)
    anchor.add(newSessionButton, BorderLayout.EAST)
```

**(c)** 忙闲变化时同步「＋」。`setBusy()` 里加一行：

```kotlin
    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        refreshMainButton()
        newSessionButton.setBlock(switchBlock(busy, permissionQueue.totalPending))
    }
```

`updateStatusBar()` 里也要加同一行 —— 权限队列变化同样影响忙闲：

```kotlin
    private fun updateStatusBar() {
        runCatching { PendingPermissionCount.getInstance(project).set(permissionQueue.totalPending) }
        newSessionButton.setBlock(switchBlock(busy, permissionQueue.totalPending))
    }
```

**(d)** 新建会话：

```kotlin
    /**
     * 新建会话。
     *
     * **不需要确认** —— 旧会话不会丢，它照样在列表里，随时能恢复。
     * 加确认反而是撒谎，暗示这个动作危险（设计稿 §边界 03）。
     */
    private fun onNewSession() {
        val block = switchBlock(busy, permissionQueue.totalPending)
        if (block != SwitchBlock.None) return   // 按钮已置灰，这里只是兜底

        stopSession()
        currentSessionId = null                 // 清掉，否则列表里会留一个错误的勾
        sessionLabel.setTitle(null, enabled = true)
        pushOp(toOp(TranscriptOp.Reset))        // 转写区清空
        startSession()                          // 不带 resumeSessionId
    }
```

**(e)** 删除：`buildSessionList` 的调用点补上 `onDelete`：

```kotlin
            createComponentPopupBuilder(
                buildSessionList(sessions, currentSessionId, block, onPick = ::switchToSession) { s ->
                    requestDeleteSession(s)
                },
                null,
            )
```

```kotlin
    /**
     * 用户已确认删除。发请求，等回执 —— **回执之前什么界面都不动**。
     *
     * 先摘行再等回执的话，删除失败时那一行已经不见了，用户会以为删掉了。
     * 这是本次唯一一个不可逆的操作，宁可慢一拍。
     */
    private fun requestDeleteSession(session: SessionInfo) {
        val id = nextId()
        deletingSessionId = session.sessionId
        client?.request(
            id,
            Protocol.encodeDeleteSession(id, session.sessionId),
        ) { outcome ->
            ApplicationManager.getApplication().invokeLater { onDeleteOutcome(session.sessionId, outcome) }
        }
    }

    private fun onDeleteOutcome(sessionId: String, outcome: RequestOutcome) {
        deletingSessionId = null
        when (outcome) {
            is RequestOutcome.Failed -> {
                // 行**不放回**（它本来就在），只报错。失败时把行摘掉才是撒谎
                pushOp(toOp(RenderItem.ErrorItem("删除会话失败：${outcome.reason}")))
            }

            is RequestOutcome.Answered -> {
                val wasCurrent = sessionId == currentSessionId
                sessions = sessions.filterNot { it.sessionId == sessionId }
                if (wasCurrent) {
                    // 删的是当前会话：停会话、清转写区、回新会话（设计稿 §4.3）
                    stopSession()
                    currentSessionId = null
                    sessionLabel.setTitle(null, enabled = true)
                    pushOp(toOp(TranscriptOp.Reset))
                    startSession()
                }
                refreshSessionList()   // 重画弹层内容
            }
        }
    }
```

`RequestOutcome` 从 `com.ccoder.sidecar` import。`sessions` / `currentSessionId` / `refreshSessionList()` 由 `session-switch` 那份引入；若那边用的是别的名字，**照那边的名字改**，别新造一套。

- [x] **Step 2: 手工冒烟**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew buildPlugin --console=plain 2>&1 | tail -5
```

装进 PyCharm（Settings → Plugins → ⚙ → Install Plugin from Disk…），**重启 IDE**，然后逐条走：

1. **删一个不是当前的会话** → 行消失；确认 `C:\Users\CY\.claude\projects\C--Users-CY-Desktop-CCoder\<sessionId>.jsonl` 确实没了
2. **删当前会话** → 转写区清空、状态栏会话名变「新会话」、列表里那个勾消失
3. **删一个已经被终端删掉的会话** → 明确报错，那一行**没有**消失
4. **忙时**（发一条长消息，回合进行中）→ 列表整片置灰、右上角「＋」置灰且点不动
5. **悬停到一行行尾** → ✕ 出现；鼠标移到时间标签上 → ✕ **不闪**；点 ✕ → **确认没有先切到那个会话**
6. **点 A 行 ✕，再点 B 行 ✕** → A 行收回原样，只剩一个确认态
7. **确认态下按 Tab** → 焦点先落在「取消」上

任何一条不对，回到对应 Task 的测试补一个用例再修 —— 不要直接改代码。

- [x] **Step 3: 全量回归**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --console=plain 2>&1 | tail -4 && cd sidecar && node --test 2>&1 | tail -6
```

预期：Kotlin 侧 `BUILD SUCCESSFUL`（用 `build/test-results/test/*.xml` 核对 `failures=0 errors=0`；总数应比开工前多 **22**：4 protocol + 6 state + 7 list + 5 button）。Node 侧 `# fail 0`。

- [x] **Step 4: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt && git commit -F - <<'EOF'
feat(ui): 接线新建与删除

新建不需要确认 —— 旧会话不会丢，它照样在列表里。加确认反而是撒谎。

删除等回执才动界面：先摘行再等的话，删除失败时那一行已经不见了，
用户会以为删掉了。这是本次唯一一个不可逆的操作，宁可慢一拍。

删当前会话走的是与新建同一条路：停会话、清转写区、起新会话。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 计划自审

**1. Spec 覆盖**

| Spec 章节 | 落在哪个 Task |
|---|---|
| §1 四条已定选择 | Task 3（判定）、Task 4（悬停 ✕ + 行内确认）、Task 5（＋占最右）、Task 6（删当前会话允许） |
| §2 删除不可逆 / 连子代理一起删 / 找不到会抛错 | Task 2（失败回 DELETE_FAILED）、Task 4（确认语）、Task 6（等回执才动界面 + 冒烟 3） |
| §3.1 新建语义、不需确认 | Task 6 (d) |
| §3.2 忙时置灰 | Task 3（`newSessionEnabled`）、Task 5 |
| §3.3 清当前会话 id | Task 6 (d)(e) |
| §4.1 步骤 | Task 4 + Task 6 |
| §4.2 (a) 不冒泡 / (b) 单确认态 / (c) 焦点在取消 | Task 4（三条各有独立用例） |
| §4.3 删当前会话 | Task 3（`deleteConfirmPrompt`）、Task 4（用例）、Task 6 (e) |
| §4.4 失败路径（行恢复原样） | Task 2、Task 6 (e) |
| §5 协议 | Task 1、Task 2 |
| §6.1 Node 侧三用例 | Task 2 |
| §6.2 Kotlin 侧 | Task 1/3/4/5 |
| §6.3 手工冒烟五条 | Task 6 Step 2（扩成七条） |
| §7 未决（不重拉 listSessions） | Task 6 (e) 用本地过滤，未调 `listSessions` |
| §8 模块划分 | 文件结构一节 |

**2. 占位符扫描**：无 TODO / TBD；每个代码步骤都有完整代码。Task 6 有三处形如"照那边的名字改"的说明 —— 那是因为 `session-switch` 的最终命名要到它执行完才确定，不是占位符，是明确的前置契约（已在开头列出）。

**3. 类型一致性**：`SessionDeleted(requestId, sessionId)` 在 Task 1 定义、Task 2 生产、Task 4/6 消费；`DELETE_MARK` 在 Task 4 定义并被同 Task 的测试引用；`sessionTitle(session)` / `deleteConfirmPrompt(session, isCurrent)` / `newSessionEnabled(block)` / `newSessionTooltip(block)` 在 Task 3 定义，Task 4/5 消费，参数名一致；`buildSessionList` 新增的 `onDelete` 参数在 Task 4 定义、Task 6 传入，位置一致（都是第 5 个参数）。
