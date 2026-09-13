# 输入框补全（@ 引用与斜杠命令）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 CCoder 输入框里加一层补全：打 `@` 弹项目文件、打 `/` 弹命令，选中后插入而不自动发送。

**Architecture:** 纯逻辑（`Completion.kt` 算触发/过滤/高亮/插入、`CommandCandidates.kt` 拼三份命令来源、`FileCandidates.kt` 匹配路径）与组件（`CompletionPopup.kt` 非聚焦 `JBPopup`）分开，与现有 `StatusCards.kt` / `StatusCardView.kt`、`SessionSwitchState.kt` / `ClaudePanel` 的拆法一致。理由同 `ComposerRulesTest` 里写的：ClaudePanel 依赖 Project，起不了单测。**输入框仍是 `JBTextArea`，一行不改** —— 键盘走 `ClaudePanel.kt:223` 那条现成的 `KeyListener` 路，弹层是**非聚焦**的（用户打字时焦点不能跑）。

**Tech Stack:** Kotlin 2.1 / Swing / IntelliJ Platform 2025.3 / JUnit 5 / Gson / Node 24（sidecar, `node:test`）

**Spec:** `docs/superpowers/specs/2026-09-13-composer-completion-design.md`

## Global Constraints

- 所有文件操作使用完整绝对 Windows 路径（`C:\Users\CY\Desktop\CCoder\...`）。
- 测试命令：Gradle 侧 `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '<全限定类名>'`；全量 `./gradlew test`。sidecar 侧 `cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test`（**不是** `node --test test/`，那条在 Node 24 上已失效）。
- **不改 `isSendKey`。** 它有自己的测试（`ComposerRulesTest`），改它会牵动换行/发送的既有规则。弹层开着时的 Enter 接管**只能在 `ClaudePanel` 的调用点之前判**。
- **现有 composer 那几套测试一条不改地继续通过**（`ComposerInputTest` / `ComposerModeTest` / `ComposerRulesTest` / `ComposerStripTest` / `ComposerToolbarTest` / `ComposerRenderProbe`）。这是"不动输入框"那个选择该付的账，也是它有没有守住的唯一证据。
- **`/` 永不自动发送。** 采纳只写进输入框。
- **配不上的命令不显示。** 宁缺勿错（设计稿 §4.1 规则 2）。
- 文字与颜色一律走平台 API（`UIUtil.getInactiveTextColor()`、`lineColor()`、`focusColor()`），不写死 hex。
- 尾随 lambda 陷阱：`Function0` / `Function1` 类型的参数**永远放在参数列表最后**。
- 每次提交前跑该任务的测试，必须全绿。

---

## 文件结构

| 文件 | 责任 |
|---|---|
| `sidecar/session.js` | **改**。暴露 `supportedCommands()` / `skills()`，各带 try/catch 兜底 |
| `sidecar/index.js` | **改**。加 `listCommands` 分发 |
| `src/main/kotlin/com/ccoder/sidecar/Protocol.kt` | **改**。`CommandInfo`、`SidecarMessage.Commands`、`encodeListCommands`、`parseCommands` |
| `src/main/kotlin/com/ccoder/ui/Completion.kt` | **新建**。纯逻辑：`CompletionItem`、`Trigger`、`CompletionQuery`、`completionQuery`、`filterCandidates`、`nextHighlight`、`completionKey`、`applyCompletion` |
| `src/main/kotlin/com/ccoder/ui/CommandCandidates.kt` | **新建**。`commandCandidates`（拼 A/B/C 三份来源）、`normalizeCommandName`、`describeCommand`、`oneLine` |
| `src/main/kotlin/com/ccoder/ui/FileCandidates.kt` | **新建**。`collectProjectFiles`（IDE 索引）、`fileCandidates`（匹配） |
| `src/main/kotlin/com/ccoder/ui/CompletionPopup.kt` | **新建**。`buildCompletionList`、`completionPopupY`、`CompletionPopup` |
| `src/main/kotlin/com/ccoder/ui/MessageRenderer.kt` | **改**。`NO_CONTENT_PLACEHOLDER`、`isEmptyCommandOutput` |
| `src/main/kotlin/com/ccoder/ui/SessionSwitchState.kt` | **改**。`isSessionSwitch` |
| `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt` | **改**。接线：命令列表状态、KeyListener、DocumentListener、init 比对 |

---

### Task 1: sidecar 交出命令列表

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\sidecar\session.js`（在 `setPermissionMode` 之后、`stop` 之前插入）
- Modify: `C:\Users\CY\Desktop\CCoder\sidecar\index.js`（在 `setPermissionMode` 那个 `case` 之后插入）
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js`

**Interfaces:**
- Consumes: `session.js` 里那个闭包变量 `query`（`assertAsyncIterable` 的产物）
- Produces: 协议消息 `{ type: 'commands', id, commands: SlashCommand[], skills: SlashCommand[] }`

**背景（实测，设计稿 §2 事实 1 与 §4.1）：** `supportedCommands()` 与 `reloadSkills()` 都是 SDK `Query` 上的 control 请求，取不到时**返回空数组而不是抛** —— 命令补全挂着不该把聊天带崩。

- [ ] **Step 1: 写失败的测试**

在 `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js` 末尾追加：

```js
test('listCommands 把命令与技能一并上报', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  // 给假 session 补上这两个方法
  const s = d.getSession();
  s.supportedCommands = async () => [{ name: 'compact', description: '压缩', argumentHint: '', aliases: [] }];
  s.skills = async () => [{ name: 'brainstorming', description: '想清楚', argumentHint: '', aliases: [] }];

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  const msg = out.find((m) => m.type === 'commands');
  assert.ok(msg, '应有一条 commands 消息');
  assert.equal(msg.id, '9');
  assert.equal(msg.commands.length, 1);
  assert.equal(msg.commands[0].name, 'compact');
  assert.equal(msg.skills[0].name, 'brainstorming');
});

test('未 start 就 listCommands 时回一条错误，不抛', async () => {
  const out = [];
  const d = createDispatcher({ sessionFactory: fakeSessionFactory().factory, out: (m) => out.push(m) });

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  assert.equal(out.at(-1).type, 'error');
  assert.equal(out.at(-1).code, 'NO_SESSION');
});

test('命令列表取不到时回空数组，不是错误', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  const s = d.getSession();
  s.supportedCommands = async () => { throw new Error('control 请求失败'); };
  s.skills = async () => { throw new Error('control 请求失败'); };

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  const msg = out.find((m) => m.type === 'commands');
  assert.ok(msg, '失败也要有一条 commands，不是 error');
  assert.deepEqual(msg.commands, []);
  assert.deepEqual(msg.skills, []);
});
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test`

Expected: 前两条失败在 `msg` 为 `undefined`（`listCommands` 还没分发）；第三条同理。若报 `UNKNOWN_METHOD` 那正是预期的失败形态。

- [ ] **Step 3: session.js 暴露两个方法**

在 `C:\Users\CY\Desktop\CCoder\sidecar\session.js` 的 `return { ... }` 里，`setPermissionMode` 之后插入：

```js
    /**
     * 会话可用的命令列表（带描述）。
     *
     * 尽力而为：取不到给空数组，**不抛**。命令补全挂着不该把聊天带崩 ——
     * 调用方（listCommands）拿空数组就当成"没有候选"。
     */
    async supportedCommands() {
      try {
        return (await query?.supportedCommands?.()) ?? [];
      } catch {
        return [];
      }
    },

    /**
     * 命令列表里的**技能子集**，只用来分组。
     *
     * `reloadSkills` 名字里带 reload，但它同时就把刷新后的列表返回了，
     * 不需要先调再查。同样尽力而为。
     */
    async skills() {
      try {
        return (await query?.reloadSkills?.())?.skills ?? [];
      } catch {
        return [];
      }
    },
```

- [ ] **Step 4: index.js 加分发**

在 `C:\Users\CY\Desktop\CCoder\sidecar\index.js` 的 `case 'setPermissionMode': { ... }` 之后、`case 'stop':` 之前插入：

```js
      case 'listCommands': {
        // 与 listSessions 同一条：没有活会话就没有命令可报，
        // 但这是"问早了"而不是"出错了"——不必致命
        if (!session) {
          fail('NO_SESSION', '会话尚未建立', false);
          return session;
        }
        Promise.all([session.supportedCommands(), session.skills()])
          .then(([commands, skills]) => out({
            type: 'commands',
            id: msg.id,
            commands,
            skills,
          }))
          .catch((err) => fail('LIST_COMMANDS_FAILED', String(err?.message ?? err), false));
        return session;
      }
```

- [ ] **Step 5: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test`

Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add sidecar/session.js sidecar/index.js sidecar/test/index.test.js
git commit -m "feat(sidecar): listCommands 交出命令与技能列表"
```

---

### Task 2: 协议认识命令列表

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\Protocol.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `{ type: 'commands', id, commands, skills }`
- Produces:
  - `data class CommandInfo(name: String, description: String?, argumentHint: String?, aliases: List<String>)`
  - `SidecarMessage.Commands(requestId: String, commands: List<CommandInfo>, skills: List<CommandInfo>)`
  - `Protocol.encodeListCommands(id: String): String`
  - `Protocol.responseIdOf` 认识 `Commands`

- [ ] **Step 1: 写失败的测试**

在 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt` 末尾追加：

```kotlin
    @Test
    fun `commands 消息解析出命令与技能两组`() {
        val line = """
            {"type":"commands","id":"7","commands":[
              {"name":"compact","description":"压缩上下文","argumentHint":"","aliases":["compress"]},
              {"name":"Debug Issue","description":"查问题","argumentHint":"","aliases":[]}
            ],"skills":[{"name":"Debug Issue","description":"查问题","argumentHint":"","aliases":[]}]}
        """.trimIndent()

        val msg = Protocol.parse(line) as SidecarMessage.Commands

        assertEquals("7", msg.requestId)
        assertEquals(2, msg.commands.size)
        assertEquals("compact", msg.commands[0].name)
        assertEquals(listOf("compress"), msg.commands[0].aliases)
        assertEquals(1, msg.skills.size)
        assertEquals(listOf("Debug Issue"), msg.skills.map { it.name })
    }

    @Test
    fun `commands 缺 id 时整条丢弃 —— 留着会变成永远等不到结果的占位`() {
        val line = """{"type":"commands","commands":[],"skills":[]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `commands 里缺 name 的条目跳过，不废掉整个列表`() {
        val line = """
            {"type":"commands","id":"7","commands":[
              {"description":"没有名字"},
              {"name":"compact","description":"压缩","argumentHint":"","aliases":[]}
            ],"skills":[]}
        """.trimIndent()

        val msg = Protocol.parse(line) as SidecarMessage.Commands
        assertEquals(1, msg.commands.size)
        assertEquals("compact", msg.commands[0].name)
    }

    @Test
    fun `commands 是响应消息，带关联 id`() {
        val line = """{"type":"commands","id":"7","commands":[],"skills":[]}"""
        val msg = Protocol.parse(line)!!
        assertEquals("7", Protocol.responseIdOf(msg))
    }

    @Test
    fun `encodeListCommands 不带参数`() {
        val json = Protocol.encodeListCommands("req-1")
        assertTrue(json.contains(""""method":"listCommands""""))
        assertTrue(json.endsWith("\n"), "NDJSON 必须以换行结尾")
    }
```

（若该文件顶部没有 `assertNull` / `assertTrue` 的 import，按现有 import 段补齐。）

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.sidecar.ProtocolTest'`

Expected: 编译失败 —— `SidecarMessage.Commands`、`CommandInfo`、`encodeListCommands` 都还不存在。

- [ ] **Step 3: 加数据类**

在 `Protocol.kt` 的 `SessionInfo` 之后、`StartParams` 之前插入：

```kotlin
/**
 * 一条可补全的命令。
 *
 * 字段裁自 SDK 的 `SlashCommand`（sdk.d.ts:8453）。**`name` 是显示名，
 * 不一定是可发送的字符串** —— 实测 `/Debug Issue`（空格大写）对应的可发送名
 * 是 `debug-issue`。可发送名来自 init 事件，见设计稿 §4.1。
 */
data class CommandInfo(
    val name: String,
    val description: String?,
    val argumentHint: String?,
    val aliases: List<String>,
)
```

在 `SidecarMessage` 里、`SessionDeleted` 之后插入：

```kotlin
    /**
     * `listCommands` 的应答。
     *
     * [commands] 是**显示信息**（名字、描述、参数提示、别名）；
     * [skills] 是其中的**技能子集**，只用来分组，不是另一份候选。
     */
    data class Commands(
        val requestId: String,
        val commands: List<CommandInfo>,
        val skills: List<CommandInfo>,
    ) : SidecarMessage
```

- [ ] **Step 4: 加解析与编码**

在 `Protocol.parse` 的 `"sessionDeleted" -> { ... }` 之后插入：

```kotlin
            // 缺 id 就无从配对，整条丢弃 —— 同 sessions
            "commands" -> obj.str("id")?.let { rid ->
                SidecarMessage.Commands(
                    rid,
                    parseCommands(obj.arr("commands")),
                    parseCommands(obj.arr("skills")),
                )
            }
```

在 `parseSessionList` 之后插入：

```kotlin
    /**
     * 逐条解析命令。
     *
     * 缺 `name` 的条目**跳过而非废掉整个列表** —— 与 [parseSessionList] 同一条
     * 理由：一条坏数据不该让另外 44 个命令都补全不出来。
     */
    private fun parseCommands(arr: JsonArray?): List<CommandInfo> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o.str("name") ?: return@mapNotNull null
            CommandInfo(
                name = name,
                description = o.str("description"),
                argumentHint = o.str("argumentHint"),
                aliases = o.arr("aliases")
                    ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.map { it.asString }
                    ?: emptyList(),
            )
        }
    }
```

在 `encodeDeleteSession` 之后插入：

```kotlin
    fun encodeListCommands(id: String): String = encodeSimple(id, "listCommands")
```

在 `responseIdOf` 的 `when` 里加一行：

```kotlin
        is SidecarMessage.Commands -> msg.requestId
```

- [ ] **Step 5: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.sidecar.ProtocolTest'`

Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/sidecar/Protocol.kt src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt
git commit -m "feat(protocol): listCommands 的请求与应答"
```

---

### Task 3: 补全的纯逻辑

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\Completion.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionTest.kt`

**Interfaces:**
- Consumes: 无（纯逻辑，不 import 任何 Swing / 平台类）
- Produces:
  - `data class CompletionItem(display: String, insert: String, description: String? = null, aliases: List<String> = emptyList(), group: String? = null)`
  - `enum class Trigger(val char: Char) { Command('/'), File('@') }`
  - `data class CompletionQuery(trigger: Trigger, query: String, start: Int)`
  - `enum class CompletionKey { Up, Down, Accept, Dismiss, Ignore }`
  - `fun completionQuery(text: String, caret: Int): CompletionQuery?`
  - `fun filterCandidates(items: List<CompletionItem>, query: String): List<CompletionItem>`
  - `fun nextHighlight(current: Int, delta: Int, size: Int): Int`
  - `fun completionKey(keyCode: Int): CompletionKey`
  - `fun applyCompletion(text: String, caret: Int, q: CompletionQuery, item: CompletionItem): Pair<String, Int>`

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

/**
 * 补全的纯逻辑：触发、过滤、高亮、按键意图、插入。
 *
 * 抽出来的理由同 [ComposerRulesTest]：ClaudePanel 依赖 Project 起不了单测，
 * 而这五处正是最容易写错的地方。
 */
class CompletionTest {

    private fun q(text: String) = completionQuery(text, text.length)

    // ---- 触发 ----

    @Test
    fun `斜杠只在消息开头触发`() {
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), q("/comp"))
    }

    @Test
    fun `路径与日期里的斜杠不触发 —— 任何位置都弹会一直打断打字`() {
        assertNull(q("C:/Users"), "盘符路径")
        assertNull(q("1/2"), "分数")
        assertNull(q("and/or"), "词组")
        assertNull(q("看看 /comp"), "句子中间的斜杠不算消息开头")
    }

    @Test
    fun `at 只在词边界触发`() {
        assertEquals(CompletionQuery(Trigger.File, "Composer", 6), q("hello @Composer"))
        assertEquals(CompletionQuery(Trigger.File, "x", 0), q("@x"))
    }

    @Test
    fun `邮箱里的 at 不触发`() {
        assertNull(q("foo@bar.com"))
    }

    @Test
    fun `打了空格就收 —— 那之后是参数或正文，不是候选的一部分`() {
        assertNull(q("/compact 自定义说明"))
        assertNull(q("@Composer.kt 看一下这个文件"))
        assertNull(q("@Composer.kt"))
    }

    @Test
    fun `命令的参数里还能再触发文件补全`() {
        assertEquals(CompletionQuery(Trigger.File, "Comp", 9), q("/compact @Comp"))
    }

    @Test
    fun `光标不在末尾时只看光标之前`() {
        val text = "/comp 后面还有字"
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), completionQuery(text, 5))
    }

    @Test
    fun `光标越界不抛`() {
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), completionQuery("/comp", 99))
        assertNull(completionQuery("", -5))
    }

    // ---- 过滤 ----

    private val compact = CompletionItem("compact", "compact", "压缩上下文")
    private val usage = CompletionItem("usage", "usage", "花费", aliases = listOf("cost", "stats"))
    private val debug = CompletionItem("Debug Issue", "debug-issue", "查问题")

    @Test
    fun `空前缀给全部`() {
        assertEquals(3, filterCandidates(listOf(compact, usage, debug), "").size)
    }

    @Test
    fun `前缀匹配大小写不敏感`() {
        assertEquals(listOf(debug), filterCandidates(listOf(compact, usage, debug), "deb"))
        assertEquals(listOf(debug), filterCandidates(listOf(compact, usage, debug), "DEBUG"))
    }

    @Test
    fun `别名也参与匹配 —— 用户在终端里敲惯的是 cost`() {
        assertEquals(listOf(usage), filterCandidates(listOf(compact, usage, debug), "cost"))
        assertEquals(listOf(usage), filterCandidates(listOf(compact, usage, debug), "sta"))
    }

    @Test
    fun `匹配的是前缀不是子串`() {
        assertEquals(emptyList<CompletionItem>(), filterCandidates(listOf(compact, usage, debug), "pact"))
    }

    // ---- 高亮 ----

    @Test
    fun `高亮到头就停，不循环`() {
        assertEquals(0, nextHighlight(0, -1, 5), "第一项再往上还是第一项")
        assertEquals(4, nextHighlight(4, 1, 5), "最后一项再往下还是最后一项")
        assertEquals(2, nextHighlight(1, 1, 5))
    }

    @Test
    fun `没有候选时高亮恒为 0`() {
        assertEquals(0, nextHighlight(3, 1, 0))
    }

    // ---- 按键意图 ----

    @Test
    fun `弹层开着时上下键移动、回车与 Tab 采纳、Esc 关闭`() {
        assertEquals(CompletionKey.Up, completionKey(KeyEvent.VK_UP))
        assertEquals(CompletionKey.Down, completionKey(KeyEvent.VK_DOWN))
        assertEquals(CompletionKey.Accept, completionKey(KeyEvent.VK_ENTER))
        assertEquals(CompletionKey.Accept, completionKey(KeyEvent.VK_TAB))
        assertEquals(CompletionKey.Dismiss, completionKey(KeyEvent.VK_ESCAPE))
    }

    @Test
    fun `其余按键一律不接管`() {
        assertEquals(CompletionKey.Ignore, completionKey(KeyEvent.VK_A))
        assertEquals(CompletionKey.Ignore, completionKey(KeyEvent.VK_SHIFT))
    }

    // ---- 插入 ----

    @Test
    fun `文件插入 at 加路径加一个尾随空格`() {
        val (text, caret) = applyCompletion(
            "@Comp", 5, completionQuery("@Comp", 5)!!,
            CompletionItem("src/Composer.kt", "src/Composer.kt"),
        )
        assertEquals("@src/Composer.kt ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `命令插入斜杠加可发送名，不加空格也不自动发送`() {
        val (text, caret) = applyCompletion(
            "/deb", 4, completionQuery("/deb", 4)!!,
            CompletionItem("Debug Issue", "debug-issue"),
        )
        assertEquals("/debug-issue", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `光标之后的文字原样保留 —— 在中间采纳不该吃掉后半句`() {
        val text = "看看 @Comp 这个文件"
        // 光标停在 @Comp 之后
        val (out, caret) = applyCompletion(
            text, 9, completionQuery(text, 9)!!,
            CompletionItem("src/Composer.kt", "src/Composer.kt"),
        )
        assertEquals("看看 @src/Composer.kt  这个文件", out)
        assertEquals("看看 @src/Composer.kt ".length, caret)
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CompletionTest'`

Expected: 编译失败 —— `CompletionItem` 等都不存在。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\Completion.kt`：

```kotlin
package com.ccoder.ui

import java.awt.event.KeyEvent

/** 显示在补全弹层里的一项。 */
internal data class CompletionItem(
    /** 显示的名字。命令可能是人话标题（`Debug Issue`），文件是相对路径。 */
    val display: String,
    /** 真正写进输入框的文本，**不含触发字符**。 */
    val insert: String,
    /** 副标题。命令是描述，文件为空。 */
    val description: String? = null,
    /** 只在过滤时参与匹配的别名，不单独成行。 */
    val aliases: List<String> = emptyList(),
    /** 分组标题；null = 不分组。 */
    val group: String? = null,
)

/** 触发补全的字符。 */
internal enum class Trigger(val char: Char) {
    Command('/'),
    File('@'),
}

/**
 * 光标前这段文本触发了什么。
 *
 * [start] 是触发字符在整个文本里的下标 —— 采纳时要从这里开始替换，
 * 而不是在光标处插入：用户已经敲了 `/comp`，那五个字符是**查询词**。
 */
internal data class CompletionQuery(
    val trigger: Trigger,
    val query: String,
    val start: Int,
)

/** 弹层开着时，某个键要做什么。 */
internal enum class CompletionKey { Up, Down, Accept, Dismiss, Ignore }

/**
 * 光标处该不该弹补全。不弹返回 null。
 *
 * 两条触发规则（设计稿 §3.1）：
 *  - `/` **只在消息开头**。`C:/Users`、`1/2`、`and/or` 里都有斜杠，
 *    任何位置都弹的话正常打字会被一直打断。
 *  - `@` **只在词边界**。`foo@bar.com` 的 `@` 前面是字母，不算。
 *
 * 还有一条共同的：**打了空格就收**。`/compact 自定义说明` 的参数、
 * `@path` 后面接的话，都不再是候选的一部分。
 */
internal fun completionQuery(text: String, caret: Int): CompletionQuery? {
    val at = caret.coerceIn(0, text.length)
    val before = text.substring(0, at)

    // 命令优先：`/` 必须占住整段前缀，所以它和 `@` 不可能同时成立
    if (before.startsWith("/") && before.none { it.isWhitespace() }) {
        return CompletionQuery(Trigger.Command, before.drop(1), 0)
    }

    val atIndex = before.lastIndexOf('@')
    if (atIndex >= 0) {
        val boundary = atIndex == 0 || before[atIndex - 1].isWhitespace()
        val body = before.substring(atIndex + 1)
        if (boundary && body.none { it.isWhitespace() }) {
            return CompletionQuery(Trigger.File, body, atIndex)
        }
    }

    return null
}

/**
 * 前缀过滤。大小写不敏感，匹配显示名、插入文本或别名。
 *
 * 别名也参与匹配：`/usage` 的别名是 `cost`。不匹配别名的话，用户在终端里
 * 敲惯的 `cost` 在插件里一条候选都没有 —— 而那条命令其实完全可用。
 */
internal fun filterCandidates(items: List<CompletionItem>, query: String): List<CompletionItem> {
    if (query.isEmpty()) return items
    val q = query.lowercase()
    return items.filter { item ->
        item.display.lowercase().startsWith(q) ||
            item.insert.lowercase().startsWith(q) ||
            item.aliases.any { it.lowercase().startsWith(q) }
    }
}

/**
 * 高亮移动。**到头就停，不循环。**
 *
 * 循环的话"最后一项再往下"会跳回第一项，而用户按向下键的预期是"没有了"。
 * IDE 的补全也是不循环的。
 */
internal fun nextHighlight(current: Int, delta: Int, size: Int): Int {
    if (size <= 0) return 0
    return (current + delta).coerceIn(0, size - 1)
}

/**
 * 弹层开着时，这个键要做什么。
 *
 * **只在弹层真的开着时调用。** 关着的时候 Enter 该不该发送是
 * [isSendKey] 的事，那个函数一行都不改（Global Constraints）。
 */
internal fun completionKey(keyCode: Int): CompletionKey = when (keyCode) {
    KeyEvent.VK_UP -> CompletionKey.Up
    KeyEvent.VK_DOWN -> CompletionKey.Down
    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> CompletionKey.Accept
    KeyEvent.VK_ESCAPE -> CompletionKey.Dismiss
    else -> CompletionKey.Ignore
}

/**
 * 采纳一项，返回新的文本与光标位置。
 *
 * 替换的是**从触发字符到光标**那一段。光标之后的文字原样保留：
 * `看看 @Comp 这个文件` 里在 `@Comp` 处采纳，后面那半句不该被吃掉。
 *
 * 文件多写一个尾随空格（`@路径 ` 之后接着打字），命令不写 ——
 * 命令后面要么是参数要么就该直接回车，多一个空格是噪音。
 */
internal fun applyCompletion(
    text: String,
    caret: Int,
    q: CompletionQuery,
    item: CompletionItem,
): Pair<String, Int> {
    val at = caret.coerceIn(0, text.length)
    val head = text.substring(0, q.start)
    val tail = text.substring(at)
    val written = q.trigger.char + item.insert + if (q.trigger == Trigger.File) " " else ""
    return (head + written + tail) to (head.length + written.length)
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CompletionTest'`

Expected: 全绿。若 `光标之后的文字原样保留` 那条挂了，检查 `applyCompletion` 用的是 `q.start` 而不是 `at` 当 head 的切点。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/Completion.kt src/test/kotlin/com/ccoder/ui/CompletionTest.kt
git commit -m "feat(ui): 补全的纯逻辑 —— 触发、过滤、高亮、插入"
```

---

### Task 4: 三份来源拼成命令候选

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\CommandCandidates.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CommandCandidatesTest.kt`

**Interfaces:**
- Consumes: `CommandInfo`（Task 2）、`CompletionItem`（Task 3）
- Produces:
  - `const val GROUP_BUILTIN = "内置"` / `const val GROUP_SKILL = "技能"`
  - `fun normalizeCommandName(name: String): String`
  - `fun describeCommand(cmd: CommandInfo): String`
  - `fun oneLine(text: String, max: Int = 110): String`
  - `fun commandCandidates(commands: List<CommandInfo>, skills: List<CommandInfo>, sendable: Set<String>): List<CompletionItem>`

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CommandCandidatesTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 三份来源拼成候选（设计稿 §4.1）。
 *
 * A = supportedCommands()（显示信息）、B = init 的可发送名、C = reloadSkills()（分组）。
 */
class CommandCandidatesTest {

    private fun cmd(
        name: String,
        description: String? = null,
        hint: String? = null,
        aliases: List<String> = emptyList(),
    ) = CommandInfo(name, description, hint, aliases)

    @Test
    fun `显示名与可发送名不同时，显示 A 插入 B`() {
        val out = commandCandidates(
            commands = listOf(cmd("Debug Issue", "查问题")),
            skills = emptyList(),
            sendable = setOf("debug-issue"),
        )

        assertEquals(1, out.size)
        assertEquals("Debug Issue", out[0].display)
        assertEquals("debug-issue", out[0].insert, "发出去必须是 kebab，否则会被当普通文本")
    }

    @Test
    fun `配不上可发送名的命令直接不出现`() {
        val out = commandCandidates(
            commands = listOf(cmd("Debug Issue"), cmd("compact")),
            skills = emptyList(),
            sendable = setOf("compact"),
        )

        assertEquals(listOf("compact"), out.map { it.display })
    }

    @Test
    fun `名字归一化：小写、空白折成连字符`() {
        assertEquals("debug-issue", normalizeCommandName("Debug Issue"))
        assertEquals("debug-issue", normalizeCommandName("  DEBUG   issue "))
        assertEquals("code-review:code-review", normalizeCommandName("code-review:code-review"))
    }

    @Test
    fun `在技能列表里的进技能组，其余进内置组`() {
        val out = commandCandidates(
            commands = listOf(cmd("compact"), cmd("brainstorming")),
            skills = listOf(cmd("brainstorming")),
            sendable = setOf("compact", "brainstorming"),
        )

        assertEquals(GROUP_BUILTIN, out.first { it.display == "compact" }.group)
        assertEquals(GROUP_SKILL, out.first { it.display == "brainstorming" }.group)
    }

    @Test
    fun `技能列表取不到时全部退化成内置组，而不是丢候选`() {
        val out = commandCandidates(
            commands = listOf(cmd("brainstorming")),
            skills = emptyList(),
            sendable = setOf("brainstorming"),
        )

        assertEquals(1, out.size)
        assertEquals(GROUP_BUILTIN, out[0].group)
    }

    @Test
    fun `描述压成单行并截断 —— 真实的技能描述是整段的`() {
        val long = "第一行\n第二行\n" + "字".repeat(200)
        val flat = oneLine(long)

        assertTrue(flat.none { it == '\n' }, "换行会让一行变五行，整个列表失去形状")
        assertTrue(flat.endsWith("…"), "截断了要看得出来")
        assertTrue(flat.length <= 110)
    }

    @Test
    fun `短描述原样保留，不加省略号`() {
        assertEquals("压缩上下文", oneLine("压缩上下文"))
    }

    @Test
    fun `副标题拼上参数提示与别名`() {
        val d = describeCommand(cmd("usage", "花费", "<无>", listOf("cost", "stats")))
        assertTrue(d.contains("花费"))
        assertTrue(d.contains("<无>"))
        assertTrue(d.contains("cost"), "别名要看得见 —— 用户在终端里敲惯的是 cost")
    }

    @Test
    fun `参数提示与别名都缺时只给描述，不留多余分隔符`() {
        assertEquals("查问题", describeCommand(cmd("x", "查问题", "", emptyList())))
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CommandCandidatesTest'`

Expected: 编译失败 —— `commandCandidates` 等不存在。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\CommandCandidates.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo

/** 分组标题。 */
internal const val GROUP_BUILTIN = "内置"
internal const val GROUP_SKILL = "技能"

/**
 * 命令名归一化：小写、空白折成连字符。
 *
 * 两份来源对同一批命令的写法不一样（设计稿 §2 事实 5）：`supportedCommands()`
 * 给的是 `Debug Issue`，可发送的名字是 `debug-issue`。不归一化就一条都配不上，
 * 而配不上按规则是**不显示** —— 整个技能组会凭空消失。
 */
internal fun normalizeCommandName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), "-")

/**
 * 把三份来源拼成候选。
 *
 * @param commands 显示信息（A）
 * @param skills   技能子集，只用来分组（C）
 * @param sendable 可发送的名字（B，来自 init 事件）
 *
 * 只在 A 里有、或配不上 B 的命令**直接不出现** —— 显示一个发出去会被当
 * 普通文本的"命令"，比不显示更糟（设计稿 §4.1 规则 2）。
 */
internal fun commandCandidates(
    commands: List<CommandInfo>,
    skills: List<CommandInfo>,
    sendable: Set<String>,
): List<CompletionItem> {
    val byNormalized = sendable.associateBy { normalizeCommandName(it) }
    val skillNames = skills.mapTo(mutableSetOf()) { normalizeCommandName(it.name) }

    return commands.mapNotNull { cmd ->
        val insert = byNormalized[normalizeCommandName(cmd.name)] ?: return@mapNotNull null
        CompletionItem(
            display = cmd.name,
            insert = insert,
            description = describeCommand(cmd),
            aliases = cmd.aliases,
            group = if (normalizeCommandName(insert) in skillNames) GROUP_SKILL else GROUP_BUILTIN,
        )
    }
}

/**
 * 副标题：描述、参数提示、别名拼成一行。
 *
 * 别名要出现在这里 —— 用户从终端带过来的习惯是敲 `/cost`，而列表里显示的
 * 是 `/usage`。不写出来他会以为这个插件没有那个命令。
 */
internal fun describeCommand(cmd: CommandInfo): String = buildList {
    cmd.description?.takeIf { it.isNotBlank() }?.let { add(oneLine(it)) }
    cmd.argumentHint?.takeIf { it.isNotBlank() }?.let { add("参数 $it") }
    if (cmd.aliases.isNotEmpty()) add("别名 " + cmd.aliases.joinToString("、"))
}.joinToString(" · ")

/**
 * 压成单行并截断。
 *
 * 真实数据里技能描述是**整段**的：实测 `/react-doctor` 的描述有五行、含换行。
 * 原样塞进弹层会让一行变五行，整个列表失去形状。
 */
internal fun oneLine(text: String, max: Int = 110): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CommandCandidatesTest'`

Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/CommandCandidates.kt src/test/kotlin/com/ccoder/ui/CommandCandidatesTest.kt
git commit -m "feat(ui): 三份命令来源拼成候选"
```

---

### Task 5: 文件候选

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\FileCandidates.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\FileCandidatesTest.kt`

**Interfaces:**
- Consumes: `CompletionItem`（Task 3）
- Produces:
  - `fun collectProjectFiles(project: Project): List<String>`（依赖 Project，不起单测）
  - `fun fileCandidates(paths: List<String>, query: String, limit: Int = 50): List<CompletionItem>`（纯逻辑）

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\FileCandidatesTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 文件候选。只测匹配那半段 —— `collectProjectFiles` 依赖 Project，
 * 和 ClaudePanel 一样起不了单测（同 [ComposerRulesTest] 的拆分理由）。
 */
class FileCandidatesTest {

    private val paths = listOf(
        "src/main/kotlin/com/ccoder/ui/Composer.kt",
        "src/main/kotlin/com/ccoder/ui/Completion.kt",
        "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
        "README.md",
    )

    @Test
    fun `空前缀不给候选 —— 裸 at 在正常行文里也会出现，不该闪一个列表`() {
        assertTrue(fileCandidates(paths, "").isEmpty())
    }

    @Test
    fun `按路径前缀匹配`() {
        val out = fileCandidates(paths, "src/test/")
        assertEquals(listOf("src/test/kotlin/com/ccoder/ui/CompletionTest.kt"), out.map { it.display })
    }

    @Test
    fun `按文件名前缀匹配 —— 想找某个文件时是这么敲的`() {
        val out = fileCandidates(paths, "Completion")
        assertEquals(
            listOf(
                "src/main/kotlin/com/ccoder/ui/Completion.kt",
                "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
            ),
            out.map { it.display },
        )
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(1, fileCandidates(paths, "readme").size)
    }

    @Test
    fun `插入文本与显示都是相对路径，不带触发字符`() {
        val item = fileCandidates(paths, "README").single()
        assertEquals("README.md", item.insert)
        assertEquals("README.md", item.display)
    }

    @Test
    fun `超出上限就截断`() {
        val many = (1..100).map { "src/file$it.kt" }
        assertEquals(50, fileCandidates(many, "src/").size)
        assertEquals(3, fileCandidates(many, "src/", limit = 3).size)
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.FileCandidatesTest'`

Expected: 编译失败 —— `fileCandidates` 不存在。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\FileCandidates.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * 项目里所有文件的相对路径（正斜杠）。
 *
 * 走平台的 `ProjectFileIndex` 而不是自己扫盘 —— 它自带项目范围与排除规则
 * （excluded roots 与 build 产物都不在里面），自己扫会把 `build/` 和
 * `sidecar/node_modules/` 一并扫出来，那是最没用的两万条候选。
 *
 * 抽成独立函数是为了让 [fileCandidates] 那半段能起单测：这个函数依赖
 * Project，测不了（同 [ComposerRulesTest] 的拆分理由）。
 */
internal fun collectProjectFiles(project: Project): List<String> {
    val base = project.basePath?.replace('\\', '/') ?: return emptyList()
    val prefix = "$base/"
    val out = mutableListOf<String>()
    ProjectFileIndex.getInstance(project).iterateContent { vf ->
        if (!vf.isDirectory && vf.path.startsWith(prefix)) {
            out += vf.path.removePrefix(prefix)
        }
        true
    }
    return out
}

/**
 * 文件候选：路径或文件名**前缀**匹配（大小写不敏感）。
 *
 * 两条都匹配是因为两种习惯都真实：想找某个目录下的东西时按路径敲，
 * 想找 `Composer.kt` 时按文件名敲。
 *
 * **空前缀给空列表。** 裸 `@` 在正常行文里也会出现（"@ 一下他"），
 * 那时候闪一个文件列表是纯噪音；而 `@` 后面什么都不写本来也不是一条
 * 有效的引用。
 */
internal fun fileCandidates(
    paths: List<String>,
    query: String,
    limit: Int = 50,
): List<CompletionItem> {
    if (query.isEmpty()) return emptyList()
    val q = query.lowercase()
    return paths
        .filter { p ->
            val lower = p.lowercase()
            lower.startsWith(q) || lower.substringAfterLast('/').startsWith(q)
        }
        .take(limit)
        .map { CompletionItem(display = it, insert = it) }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.FileCandidatesTest'`

Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/FileCandidates.kt src/test/kotlin/com/ccoder/ui/FileCandidatesTest.kt
git commit -m "feat(ui): 文件候选 —— 走 ProjectFileIndex，空前缀不弹"
```

---

### Task 6: 弹层组件

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\CompletionPopup.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionPopupTest.kt`

**Interfaces:**
- Consumes: `CompletionItem`（Task 3）、`popupHeightOf`（`ComposerStrip.kt`）
- Produces:
  - `const val COMPLETION_MAX_ROWS = 8` / `const val COMPLETION_WIDTH = 320`
  - `fun buildCompletionList(items: List<CompletionItem>, selected: Int): JComponent`
  - `fun completionRowText(item: CompletionItem): String`
  - `fun completionPopupY(caretTop: Int, caretBottom: Int, popupHeight: Int, screenTop: Int, screenBottom: Int, gap: Int): Int`
  - `class CompletionPopup { val isOpen: Boolean; fun show(anchor: JComponent, caret: Rectangle, items: List<CompletionItem>, selected: Int); fun hide() }`

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionPopupTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

/**
 * 弹层。测属性与位置计算 —— 好不好看交给 [CompletionRenderProbe]。
 */
class CompletionPopupTest {

    private val builtin = CompletionItem("compact", "compact", "压缩上下文", group = GROUP_BUILTIN)
    private val skill = CompletionItem("brainstorming", "brainstorming", "想清楚", group = GROUP_SKILL)

    private fun labels(c: Component): List<String> =
        buildList {
            if (c is JLabel) add(c.text)
            if (c is Container) c.components.forEach { addAll(labels(it)) }
        }

    @Test
    fun `弹层不可聚焦 —— 焦点跑掉的话接下来的字符就进了弹层`() {
        val list = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertFalse(list.isFocusable, "内容组件不可聚焦是这套配置的机制本身")
    }

    @Test
    fun `分组标题只在换组时出现一次`() {
        val two = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertEquals(1, labels(two).count { it == GROUP_BUILTIN })
        assertEquals(1, labels(two).count { it == GROUP_SKILL })
    }

    @Test
    fun `同组连续多项只画一个标题`() {
        val a = CompletionItem("a", "a", group = GROUP_BUILTIN)
        val b = CompletionItem("b", "b", group = GROUP_BUILTIN)
        val list = buildCompletionList(listOf(a, b), selected = 0)
        assertEquals(1, labels(list).count { it == GROUP_BUILTIN })
    }

    @Test
    fun `没有分组的候选不画标题`() {
        val list = buildCompletionList(listOf(CompletionItem("x", "x")), selected = 0)
        assertFalse(labels(list).contains(GROUP_BUILTIN))
        assertFalse(labels(list).contains(GROUP_SKILL))
    }

    @Test
    fun `长描述被截断，不会把弹层撑宽`() {
        val long = CompletionItem("x", "x", "字".repeat(500))
        val list = buildCompletionList(listOf(long), selected = 0)
        val text = labels(list).first { it.startsWith("字") }
        assertTrue(text.endsWith("…"))
        assertEquals(oneLine("字".repeat(500)), text)
    }

    @Test
    fun `行文本带描述`() {
        assertEquals("compact  ·  压缩上下文", completionRowText(builtin))
        assertEquals("x", completionRowText(CompletionItem("x", "x")))
    }

    @Test
    fun `高亮换的是另一个组件，不是改同一份内容`() {
        val first = buildCompletionList(listOf(builtin, skill), selected = 0)
        val second = buildCompletionList(listOf(builtin, skill), selected = 1)
        assertNotSame(first, second)
    }

    // ---- 位置 ----

    @Test
    fun `光标下方放得下就往下弹`() {
        assertEquals(120, completionPopupY(caretTop = 100, caretBottom = 116, popupHeight = 80, screenTop = 0, screenBottom = 900, gap = 4))
    }

    @Test
    fun `下方放不下就往上翻 —— 输入框本来就在窗口底部`() {
        // 光标底 880，屏底 900，弹层 80 ⇒ 下方只剩 20
        assertEquals(16, completionPopupY(caretTop = 864, caretBottom = 880, popupHeight = 80, screenTop = 0, screenBottom = 900, gap = 4))
    }

    @Test
    fun `上下都放不下时贴屏幕顶 —— 贴底会让第一项看不见`() {
        assertEquals(0, completionPopupY(caretTop = 5, caretBottom = 20, popupHeight = 900, screenTop = 0, screenBottom = 900, gap = 4))
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CompletionPopupTest'`

Expected: 编译失败 —— `buildCompletionList` 等不存在。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\CompletionPopup.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 弹层最多显示几行。再多的靠继续打字缩小前缀。 */
internal const val COMPLETION_MAX_ROWS = 8

/** 弹层宽度（未缩放 px）。 */
internal const val COMPLETION_WIDTH = 320

/**
 * 一行显示什么。测试与探针共用 —— 免得两边各拼一遍然后漂移。
 */
internal fun completionRowText(item: CompletionItem): String =
    item.description?.let { "${item.display}  ·  $it" } ?: item.display

/**
 * 弹层内容。
 *
 * **不可聚焦**：弹层出现时用户还在打字，焦点跑掉的话接下来的字符就进了
 * 弹层而不是输入框。[CompletionPopup] 的 `setFocusable(false)` 是同一件事的
 * 另一半，这里能断言的是内容组件这一半。
 *
 * 分组标题只在**换组时**插一条，不是每行都挂 —— 45 条命令每行前面顶一个
 * 「内置」，读起来全是重复。
 */
internal fun buildCompletionList(items: List<CompletionItem>, selected: Int): JComponent {
    val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
    }

    var lastGroup: String? = null
    items.forEachIndexed { index, item ->
        if (item.group != null && item.group != lastGroup) {
            column.add(groupHeader(item.group))
            lastGroup = item.group
        }
        column.add(row(item, index == selected))
    }

    val capped = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(column, BorderLayout.CENTER)
    }
    val visible = minOf(items.size, COMPLETION_MAX_ROWS)
    val rowHeight = JBUI.scale(20)
    capped.preferredSize = Dimension(
        JBUI.scale(COMPLETION_WIDTH),
        JBUI.scale(8) + visible * rowHeight + if (lastGroup != null) JBUI.scale(18) else 0,
    )
    return capped
}

private fun groupHeader(text: String): JComponent = JLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.empty(2, 8, 1, 8)
    alignmentX = java.awt.Component.LEFT_ALIGNMENT
}

private fun row(item: CompletionItem, selected: Boolean): JComponent = JLabel(completionRowText(item)).apply {
    isOpaque = selected
    if (selected) background = selectionColor()
    border = JBUI.Borders.empty(2, 12, 2, 8)
    alignmentX = java.awt.Component.LEFT_ALIGNMENT
    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
}

/**
 * 弹层该出现在哪个 y（屏幕坐标）。
 *
 * 与 [popupAnchorY] 同一套退回逻辑：**向下优先，放不下往上翻，都放不下贴屏顶**。
 * 输入框在工具窗口底部，所以"往上翻"是常态而不是边角情况。
 */
internal fun completionPopupY(
    caretTop: Int,
    caretBottom: Int,
    popupHeight: Int,
    screenTop: Int,
    screenBottom: Int,
    gap: Int,
): Int {
    val below = caretBottom + gap
    if (below + popupHeight <= screenBottom) return below

    val above = caretTop - gap - popupHeight
    if (above >= screenTop) return above

    return screenTop
}

/**
 * 非聚焦的补全弹层。
 *
 * 与 [showTogglePopup] 的三个浮层不是一回事：那三个是**点击驱动**、锚点是控件；
 * 这个是**键盘驱动**、锚点是光标。共用的只有定位计算。
 *
 * `setCancelOnClickOutside(false)`：开着的话，点输入框想挪光标会被弹层吃掉，
 * 而用户只是想改个错字。关层由 [ClaudePanel] 的文档/光标监听负责。
 *
 * `setCancelKeyEnabled(false)`：不让弹层注册全局 Esc —— 那个键要留给输入框，
 * 由 [completionKey] 判成 [CompletionKey.Dismiss]。
 */
internal class CompletionPopup {

    private var popup: JBPopup? = null

    val isOpen: Boolean get() = popup != null

    fun show(anchor: JComponent, caret: Rectangle, items: List<CompletionItem>, selected: Int) {
        hide()
        if (!anchor.isShowing) return

        val content = buildCompletionList(items, selected)
        val at = anchor.locationOnScreen
        val screen = anchor.graphicsConfiguration?.bounds ?: Rectangle(0, 0, 1920, 1080)
        val height = popupHeightOf(null, content.preferredSize)

        val y = completionPopupY(
            caretTop = at.y + caret.y,
            caretBottom = at.y + caret.y + caret.height,
            popupHeight = height,
            screenTop = screen.y,
            screenBottom = screen.y + screen.height,
            gap = JBUI.scale(4),
        )

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(false)
            .setCancelKeyEnabled(false)
            .createPopup()
            .also { it.show(Point(at.x + caret.x, y)) }
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }
}

/** 选中行的底色。取平台色，取不到时用一条中性蓝。 */
private fun selectionColor(): Color =
    UIUtil.getListSelectionBackground(true)
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CompletionPopupTest'`

Expected: 全绿。`JBScrollPane` 若未被用到就删掉那条 import（`buildCompletionList` 里只用了 `JPanel`）。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/CompletionPopup.kt src/test/kotlin/com/ccoder/ui/CompletionPopupTest.kt
git commit -m "feat(ui): 非聚焦的补全弹层与它的定位"
```

---

### Task 7: 接进输入框

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`

**Interfaces:**
- Consumes: Task 2–6 的全部产物
- Produces: 无新公开接口（面板内部接线）

**注意：** 这一任务不改任何现有函数体的既有逻辑，只在 `KeyListener` 的**开头**加一层短路、并新增几个私有方法。`isSendKey` 那一段一字不动。

- [ ] **Step 1: 加状态字段**

在 `ClaudePanel.kt` 的 `private var pendingFirstMessage: String? = null`（约 220 行）之后插入：

```kotlin
    // ---- 补全（设计稿 §3）----

    /** 命令显示信息与技能分组，会话就绪后拉一次。 */
    private var commandList: List<CommandInfo> = emptyList()
    private var skillList: List<CommandInfo> = emptyList()

    /** 可发送的命令名，来自最近一条 `init` 事件的 `slash_commands`。 */
    private var sendableNames: Set<String> = emptySet()

    private val completion = CompletionPopup()
    private var completionQuery: CompletionQuery? = null
    private var completionItems: List<CompletionItem> = emptyList()
    private var completionIndex = 0

    /**
     * 项目文件列表，**按弹层生命周期缓存**。
     *
     * 不缓存的话每次按键都要走一遍 ProjectFileIndex，那是几万条 VFS 访问。
     * 关层时清掉，所以新开的弹层总能看到新文件。
     */
    private var projectFiles: List<String>? = null

    /** 采纳时的程序化改写会触发文档监听，用它挡掉自引发的重算。 */
    private var suppressCompletion = false

    /** 这一回合是命令回合（发出去的消息以 `/` 开头）。 */
    private var lastSendWasCommand = false
```

- [ ] **Step 2: 加监听与按键短路**

把 `ClaudePanel.kt:223-236` 的 `init { input.addKeyListener(...) }` 整段替换为：

```kotlin
        // 补全：文本变了就重算候选。用文档监听而不是按键监听 ——
        // 粘贴、撤销、退格都会改文本，而它们不都是"按键"。
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshCompletion()
        })
        // 光标挪走（点了一下别处）时弹层要跟着收，否则它会停在一个
        // 已经没有查询词的位置上
        input.addCaretListener { refreshCompletion() }

        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // 补全开着时，上下键与 Enter/Tab/Esc 归补全。
                // **只在真开着时短路** —— 关着的时候 Enter 该不该发送
                // 仍然是 isSendKey 的事，那个函数一行都不改
                if (completion.isOpen) {
                    when (completionKey(e.keyCode)) {
                        CompletionKey.Up -> { e.consume(); moveCompletion(-1); return }
                        CompletionKey.Down -> { e.consume(); moveCompletion(1); return }
                        CompletionKey.Accept -> { e.consume(); acceptCompletion(); return }
                        CompletionKey.Dismiss -> { e.consume(); closeCompletion(); return }
                        CompletionKey.Ignore -> Unit
                    }
                }

                // 哪个键算发送由设置决定（聊天惯例 / 编辑器惯例，见 SendShortcut）
                val shortcut = ClaudeSettings.getInstance(project).sendShortcut

                // 回合进行中不发送：那时按钮是"停止"，发送键却另发一条会让
                // 两者语义打架（见 mainButtonState）
                if (busy) return
                if (isSendKey(e.keyCode, e.isShiftDown, e.isControlDown, shortcut)) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })
```

- [ ] **Step 3: 加补全的私有方法**

在 `sendCurrentInput()` 之前插入：

```kotlin
    // ---- 补全（设计稿 §3）----

    /**
     * 重算候选并更新弹层。文档变化与光标变化都走这里。
     *
     * **两类触发各自过滤，不共用 [filterCandidates]。** 文件那条要的是
     * "路径前缀**或文件名**前缀"（`Comp` 要能命中
     * `src/main/kotlin/com/ccoder/ui/Composer.kt`），而通用过滤走的是
     * 显示串整体前缀 —— 共用的话按文件名搜会一条都搜不到。
     */
    private fun refreshCompletion() {
        if (suppressCompletion) return
        val q = completionQuery(input.text, input.caretPosition)
        if (q == null) return closeCompletion()

        val filtered = when (q.trigger) {
            Trigger.Command ->
                filterCandidates(commandCandidates(commandList, skillList, sendableNames), q.query)

            Trigger.File -> fileCandidates(allProjectFiles(), q.query)
        }
        if (filtered.isEmpty()) return closeCompletion()

        completionQuery = q
        completionItems = filtered
        completionIndex = 0
        showCompletion()
    }

    /**
     * 全部项目文件，**按弹层生命周期缓存**，见 [projectFiles]。
     */
    private fun allProjectFiles(): List<String> =
        projectFiles ?: collectProjectFiles(project).also { projectFiles = it }

    private fun showCompletion() {
        val caret = caretRect()
        // modelToView2D 在没有布局时返回 null（面板还没显示），此时不弹
        if (caret == null) return closeCompletion()
        completion.show(input, caret, completionItems, completionIndex)
    }

    /**
     * 光标那一格的矩形（相对输入框）。
     *
     * `JBTextArea` 的 `modelToView2D` 只在组件已布局时有效，未布局时返回 null ——
     * 直接解引用会在面板还没显示时 NPE。
     */
    private fun caretRect(): Rectangle? =
        runCatching { input.modelToView2D(input.caretPosition)?.bounds }.getOrNull()

    private fun moveCompletion(delta: Int) {
        completionIndex = nextHighlight(completionIndex, delta, completionItems.size)
        showCompletion()
    }

    private fun acceptCompletion() {
        val q = completionQuery ?: return
        val item = completionItems.getOrNull(completionIndex) ?: return
        val (text, caret) = applyCompletion(input.text, input.caretPosition, q, item)

        // 程序化改写会触发文档监听；不挡住的话它会拿旧的光标位置重算一次，
        // 命令那种没有尾随空格的文本还会把弹层又弹回来
        suppressCompletion = true
        try {
            input.text = text
            input.caretPosition = caret
        } finally {
            suppressCompletion = false
        }
        closeCompletion()
    }

    private fun closeCompletion() {
        completion.hide()
        completionQuery = null
        completionItems = emptyList()
        completionIndex = 0
        // 关层即丢缓存：下次打开能看到这一轮新建的文件
        projectFiles = null
    }

    /** 会话就绪后拉一次命令列表。取不到就保持空 —— 补全靠不到它照常工作。 */
    private fun requestCommands() {
        val c = client ?: return
        val reqId = nextId()
        c.request(reqId, Protocol.encodeListCommands(reqId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message as? SidecarMessage.Commands
                    ?: return@invokeLater
                commandList = msg.commands
                skillList = msg.skills
            }
        }
    }
```

- [ ] **Step 4: 接上会话就绪与 init**

在 `onMessage` 的 `is SidecarMessage.Ready -> { ... }` 分支里，`refreshMainButton()` 之后插入：

```kotlin
                    requestCommands()
```

在 `if (msg.event.str("subtype") == "init") { ... }` 分支里，`msg.event.str("session_id")?.let { ... }` **之前**插入：

```kotlin
                        // 可发送的命令名。与显示名不是一回事（设计稿 §2 事实 5），
                        // 补全列表要靠它才知道选中后该写什么进输入框
                        msg.event.arr("slash_commands")
                            ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.map { it.asString }
                            ?.toSet()
                            ?.let { sendableNames = it }
```

在 `stopSession()` 里加一行，让列表随会话一起失效（设计稿 §4.1「列表随会话走」）：

```kotlin
        // 命令列表随会话走。留着它会让未连接时打 `/` 弹出一份过期的
        commandList = emptyList()
        skillList = emptyList()
        sendableNames = emptySet()
```

- [ ] **Step 5: 补 import**

`ClaudePanel.kt` 顶部按现有分组补齐：

```kotlin
import com.ccoder.sidecar.CommandInfo
import com.intellij.ui.DocumentAdapter
import java.awt.Rectangle
import javax.swing.event.DocumentEvent
```

- [ ] **Step 6: 编译并跑全量测试**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test`

Expected: 全绿，**包括现有 composer 那几套一条没改**。若 `ComposerRulesTest` 有任何一条挂了，说明 Step 2 动了 `isSendKey` 那一段 —— 回退重做。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt
git commit -m "feat(ui): 补连接进输入框 —— 打开、移动、采纳、关闭"
```

---

### Task 8: 命令回合不画空气泡

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\MessageRenderer.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\MessageRendererTest.kt`

**Interfaces:**
- Consumes: `RenderItem.AssistantText`
- Produces:
  - `const val NO_CONTENT_PLACEHOLDER = "(no content)"`
  - `fun isEmptyCommandOutput(item: RenderItem): Boolean`

**背景（实测，设计稿 §5.1）：** 命令输出走 assistant 文本块。`/cost`、`/context` 有内容要照常显示，但 `/clear` 的文本是字面量 `(no content)`、`/compact` 是空串 —— 渲染出来看着像 bug。

- [ ] **Step 1: 写失败的测试**

在 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\MessageRendererTest.kt` 末尾追加：

```kotlin
    @Test
    fun `命令回合里的空输出不画气泡`() {
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("   ")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("(no content)")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("  (no content)  ")))
    }

    @Test
    fun `命令回合里的真输出照常画 —— cost 与 context 的报告就靠这条`() {
        assertFalse(isEmptyCommandOutput(RenderItem.AssistantText("Total cost: $0.16")))
    }

    @Test
    fun `只挡 assistant 文本，别的渲染项一律不碰`() {
        assertFalse(isEmptyCommandOutput(RenderItem.Result("success", null, null)))
        assertFalse(isEmptyCommandOutput(RenderItem.SystemNote("")))
        assertFalse(isEmptyCommandOutput(RenderItem.UserText("")))
    }
```

（`assertEquals` / `assertFalse` / `assertTrue` 若未 import 按现有 import 段补齐；`RenderItem` 与该测试同包，不需要 import。）

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.MessageRendererTest'`

Expected: 编译失败 —— `isEmptyCommandOutput` 不存在。

- [ ] **Step 3: 加实现**

在 `MessageRenderer.kt` 的 `RenderItem` 定义之后插入：

```kotlin
/**
 * 命令没有可见输出时 SDK 给的占位串（实测 `/clear` 就是这个）。
 *
 * 渲染成气泡会看着像 bug —— 一个写着 `(no content)` 的对话框，
 * 用户没法判断是命令出问题了还是插件坏了。
 */
internal const val NO_CONTENT_PLACEHOLDER = "(no content)"

/**
 * 命令回合里该被丢掉的气泡。
 *
 * **只有命令回合才丢。** 模型自己回一句空话是另一回事，那是它的话；
 * 而命令的空输出是 SDK 的占位，不是内容。
 *
 * 判据是"发出去的消息以 `/` 开头"，由调用方记住 —— 不能用
 * `result.local_command`，实测那个字段恒为 null（设计稿 §2 事实 3）。
 */
internal fun isEmptyCommandOutput(item: RenderItem): Boolean =
    item is RenderItem.AssistantText &&
        (item.text.isBlank() || item.text.trim() == NO_CONTENT_PLACEHOLDER)
```

- [ ] **Step 4: 接进面板**

在 `ClaudePanel.kt` 的 `sendCurrentInput()` 里，`client?.sendLine(...)` 之后插入：

```kotlin
        // 记住这一回合是不是命令 —— 命令的空输出不该画气泡（设计稿 §5.1）
        lastSendWasCommand = text.startsWith("/")
```

在 `onMessage` 的 `is SidecarMessage.Event -> { }` 分支里，把 `items.forEach { pushOp(toOp(it)) }` 换成：

```kotlin
                    // 命令回合里的空输出丢掉；其余一律照常
                    items.filterNot { lastSendWasCommand && isEmptyCommandOutput(it) }
                        .forEach { pushOp(toOp(it)) }
```

并把紧随其后的 `if (items.any { it is RenderItem.Result }) setBusy(false)` 换成：

```kotlin
                    if (items.any { it is RenderItem.Result }) {
                        setBusy(false)
                        lastSendWasCommand = false
                    }
```

- [ ] **Step 5: 跑测试**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.MessageRendererTest' && ./gradlew test`

Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/MessageRenderer.kt src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/test/kotlin/com/ccoder/ui/MessageRendererTest.kt
git commit -m "feat(ui): 命令回合不画空输出气泡"
```

---

### Task 9: /clear 换会话

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\SessionSwitchState.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt`

**Interfaces:**
- Consumes: `TranscriptOp.Reset`（已存在，`beginReplay` 在用）、`refreshSessionList()`（已存在）
- Produces: `fun isSessionSwitch(current: String?, incoming: String?): Boolean`

**背景（实测，设计稿 §5.2）：** `/clear` 之后 `system/init` 带的 `session_id` 会换，进程不动。而 init **每回合都发一次**（事实 6），所以不能靠"收到 init"判定。

- [ ] **Step 1: 写失败的测试**

在 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\SessionSwitchStateTest.kt` 末尾追加：

```kotlin
    @Test
    fun `id 变了就是换了会话`() {
        assertTrue(isSessionSwitch("aaa", "bbb"))
    }

    @Test
    fun `id 没变不是换会话 —— init 每个回合都发一次`() {
        assertFalse(isSessionSwitch("aaa", "aaa"), "每回合都判一次换会话的话，转写区会被清空无数次")
    }

    @Test
    fun `第一次拿到 id 不算换会话`() {
        assertFalse(
            isSessionSwitch(null, "aaa"),
            "全新会话的第一个 init 就是这种情况：该做的是填上 id，不是清空转写区",
        )
    }

    @Test
    fun `任一边缺了就当作没换`() {
        assertFalse(isSessionSwitch("aaa", null))
        assertFalse(isSessionSwitch(null, null))
    }
```

（`assertFalse` / `assertTrue` 若未 import 按现有 import 段补齐。）

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.SessionSwitchStateTest'`

Expected: 编译失败 —— `isSessionSwitch` 不存在。

- [ ] **Step 3: 加纯函数**

在 `SessionSwitchState.kt` 的 `newSessionTooltip` 之后插入：

```kotlin
/**
 * `init` 带进来的 session id 是否意味着**换了会话**（`/clear` 走这条路）。
 *
 * 两条都必须判：
 *  - **不能靠"收到 init"** —— 实测 init 每个回合都发一次（设计稿 §2 事实 6），
 *    那样每回合都会清一次转写区。
 *  - **`current` 为 null 时不算** —— 全新会话的第一个 init 就是这种情况。
 *    那时该做的是"填上 id"，不是"清空转写区"：用户刚看到「会话已就绪」，
 *    紧接着转写区被清空会像是崩了。
 *
 * 真正的切换信号是同一个进程里 id **变了**（实测 `/clear`，设计稿 §10.4）。
 */
internal fun isSessionSwitch(current: String?, incoming: String?): Boolean =
    current != null && incoming != null && current != incoming
```

- [ ] **Step 4: 接进面板**

在 `ClaudePanel.kt` 的 init 分支里，把 `msg.event.str("session_id")?.let { currentSessionId = it }` 换成：

```kotlin
                        msg.event.str("session_id")?.let { sid ->
                            if (isSessionSwitch(currentSessionId, sid)) {
                                // /clear：CLI 换了会话，进程不动（设计稿 §5.2）。
                                // 清空转写区而不是插一条分隔线 —— 留着一段
                                // 已经不在上下文里的历史，正是 §7.5 反对的
                                // 那种"看着还在、其实没了"
                                pushOp(TranscriptOp.Reset)
                                currentSessionTitle = null
                                refreshSessionLabel(enabled = true)
                                refreshSessionList()
                                pushOp(toOp(RenderItem.SystemNote("上下文已清空，这是一条新会话")))
                            }
                            // 当前会话指针以 init 里的 id 为准，**不以会话列表为准**
                            // —— 刚 /clear 出来的新会话还没落盘，列表未必列得到它
                            currentSessionId = sid
                        }
```

- [ ] **Step 5: 跑测试**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test`

Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/main/kotlin/com/ccoder/ui/SessionSwitchState.kt src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/test/kotlin/com/ccoder/ui/SessionSwitchStateTest.kt
git commit -m "feat(ui): /clear 换会话 —— 比对 id，清空转写区"
```

---

### Task 10: 渲染探针

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionRenderProbe.kt`

**Interfaces:**
- Consumes: `buildCompletionList`、`CompletionItem`、`GROUP_BUILTIN`、`GROUP_SKILL`
- Produces: `build/completion-probe-*.png`

**没有断言，也不该有** —— 单测能钉住"分组标题只出一条""长描述被截断"，钉不住"这一列读起来累不累"。而后者正是弹层形态的全部理由。

- [ ] **Step 1: 写探针**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\CompletionRenderProbe.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 渲染探针：把补全弹层画成 PNG，好让人眼看一眼。
 *
 * 三个问题只能靠看：长描述截到 110 字够不够、分组标题的分量会不会太抢、
 * 一行里"名字 · 描述"的间隔读起来累不累。产物在 `build/completion-probe*.png`。
 */
class CompletionRenderProbe {

    @Test
    fun `命令组画成图片`() = render("build/completion-probe-commands.png", commands())

    @Test
    fun `文件组画成图片`() = render("build/completion-probe-files.png", files())

    private fun commands() = listOf(
        CompletionItem("compact", "compact", "参数 <可选的自定义摘要说明>", group = GROUP_BUILTIN),
        CompletionItem("clear", "clear", "开一条新会话，上下文清空", aliases = listOf("reset", "new"), group = GROUP_BUILTIN),
        CompletionItem("context", "context", "看当前的上下文占用", group = GROUP_BUILTIN),
        CompletionItem(
            "Debug Issue", "debug-issue",
            "Systematically debug issues using graph-powered code navigation (user)",
            group = GROUP_SKILL,
        ),
        CompletionItem(
            "brainstorming", "brainstorming",
            "You MUST use this before any creative work - creating features, building components, adding functionality, or modifying behavior. Explores user intent, requirements and design before implementation.",
            group = GROUP_SKILL,
        ),
    )

    private fun files() = listOf(
        "src/main/kotlin/com/ccoder/ui/Completion.kt",
        "src/main/kotlin/com/ccoder/ui/CompletionPopup.kt",
        "src/main/kotlin/com/ccoder/ui/CommandCandidates.kt",
        "src/main/kotlin/com/ccoder/ui/ClaudePanel.kt",
        "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
    ).map { CompletionItem(it, it) }

    private fun render(path: String, items: List<CompletionItem>) {
        SwingUtilities.invokeAndWait {
            val content = buildCompletionList(items, selected = 1)
            val bg = com.intellij.ui.JBColor.WHITE
            val outer = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                background = bg
                border = com.intellij.util.ui.JBUI.Borders.empty(8)
                add(content, java.awt.BorderLayout.CENTER)
            }

            val ps = content.preferredSize
            val w = ps.width + 16
            val h = ps.height + 16
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = bg
            g.fillRect(0, 0, w, h)
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
```

- [ ] **Step 2: 跑探针并看图**

Run: `cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.CompletionRenderProbe'`

然后打开 `C:\Users\CY\Desktop\CCoder\build\completion-probe-commands.png` 与 `completion-probe-files.png` **看一眼**。

要确认的三件事（看不顺眼就改 `oneLine` 的 `max` 或 `COMPLETION_WIDTH`，然后重跑）：

1. 长描述截断后还读得懂吗？`brainstorming` 那条是本例里最长的真实数据。
2. 分组标题（内置 / 技能）的分量是否够轻 —— 它不该跟候选抢注意力。
3. 选中行（第二行）的底色深浅是否合适。

- [ ] **Step 3: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder"
git add src/test/kotlin/com/ccoder/ui/CompletionRenderProbe.kt
git commit -m "test(ui): 补全弹层的渲染探针"
```

---

## 收尾：手工冒烟

跑 `./gradlew runIde`，在沙箱 PyCharm 里逐条验：

1. 打 `/` → 弹出命令列表；打 `/comp` → 只剩 `compact`；Enter 采纳 → 输入框成 `/compact`，**没有自动发送**
2. 再按 Enter → 命令执行，输出（Total cost 那类报告）出现在转写区
3. 打 `/clear` 并回车 → 转写区**清空**，出现「上下文已清空，这是一条新会话」；会话列表里旧会话仍在，能切回去
4. 打 `@` → **不弹**（空前缀）；打 `@Comp` → 弹文件列表；选中 → 插入 `@路径 ` 且光标在其后
5. 在句子里打 `@`（如「问一下 @」）→ 不弹
6. `C:/Users` 打进输入框 → 不弹命令列表
7. 弹层开着按 ↑↓ 移动、Esc 关闭且**已敲的字一个不少**；弹层开着按 Enter 是采纳，**不是发送**
8. 切断 sidecar（关掉再开）后打 `/` → 不弹任何东西

---

## 自审记录

**Spec 覆盖：** §3.1 触发 → Task 3；§3.2 按键接管 → Task 3（判定）+ Task 7（接线）；§3.3 插入 → Task 3；§4.1 三份来源 → Task 1/2/4；§4.2 文件 → Task 5；§4.1 刷新时机 → Task 7 Step 4；§5.1 渲染 → Task 8；§5.2 `/clear` → Task 9；§6 组件 → Task 3–6；§7 测试 → 每任务的测试 + Task 10 探针；§8 明确不做 → 未越界（无模糊匹配、无图标、无 `@` 文件夹、无自动发送、未碰 `compact_boundary`）。

**三处 spec 之外、需要你知悉的取舍：**

1. **`@` 空前缀不弹候选**（Task 5）。Spec §7 只写了"空前缀给全部"是针对通用 `filterCandidates` 的；文件那支单独短路。理由：裸 `@` 在正常行文里会出现，弹一个 50 条的文件列表是纯噪音。
2. **弹层关层即丢弃文件缓存**（Task 7）。不缓存的话每次按键都要重新遍历 ProjectFileIndex；一直缓存的话新建的文件永远不出现。按弹层生命周期缓存是这两者之间唯一不难受的点。
3. **渲染探针只出浅色两张，没做深浅各一张**（Task 10）。Spec §7 写的是"深浅两色各一张"，但项目现有的 `ComposerRenderProbe` 也不切主题 —— 离屏渲染拿不到 IDE 的深色 LaF，硬造一个只会画出一个谁也没见过的配色。深色下的观感放到收尾冒烟里看（在沙箱 PyCharm 切深色主题）。

**自审修掉的一处实现错误：** 初稿里文件候选写成"给全部文件、再交给通用 `filterCandidates` 过滤"，那样 `Comp` 永远匹配不上 `src/main/kotlin/com/ccoder/ui/Composer.kt`（通用过滤是显示串整体前缀），而按文件名搜恰恰是文件补全最常用的那半。已改成两类触发各自过滤。

**§9 待确认项的处理：** A 与 B 的名字配对规则在 Task 4 用归一化（小写 + 空白折连字符）实现并有测试；**真实数据能否全部配上要用 §10 的探针复核**（跑 `MSYS_NO_PATHCONV=1 node sidecar/tools/probe-slash.mjs`，把 `supportedCommands()` 与 init 的 `slash_commands` 并排打出来对）。配不上的按规则不显示，不是 bug。
