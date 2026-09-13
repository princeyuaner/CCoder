# 设置入口与多模型配置

日期：2026-09-13
设计稿：`docs/design/model-settings.html`（A/B/C 三选一）
选定：**方案 A · 模态对话框 · 左页签三栏**

---

## 1. 要解决的问题

现在**只能用一个模型**，而且换它得做四件事：离开工具窗口 → 打开 PyCharm Settings →
找到 CCoder 那一页 → 改 `model` 字段 → 关掉。

`ClaudeSettings` 里的 `model` 是一个字符串（`ClaudeSettings.kt:98`），它是：

- **项目级的** —— `project.getService(ClaudeSettings::class.java)`，换个项目就是另一份
- **只会填模型名** —— 端点写死官方，`toStartParams` 只往 `StartParams` 里塞一个 `model`

于是三件事做不到：

1. **多个第三方端点来回换**。中转站、自建网关、DeepSeek 的 Anthropic 兼容口 ——
   它们各有各的 Base URL 和密钥，现在一个都放不下。
2. **随手切换**。切模型是高频动作（"这个任务用便宜的快模型"），
   而现在的路径是低频设置的路径。
3. **密钥无处安放**。唯一的自由通道 `envOverrides` 是明文写进项目 XML 的
   （`ClaudeSettings.kt:87`），按键值对存进 `PersistentStateComponent`。

## 2. 目标

- 右上角一个齿轮，点开是带页签的设置对话框（本版只做「模型」一页）
- 模型页管一组**自带端点与密钥**的配置，可增删改
- 输入框左下角的模型标签**点一下就能切**，不用离开工具窗口
- 密钥不落盘明文
- **列表为空时，行为与今天完全一致**

最后一条是硬要求：这是个向后兼容的改动，不是一次迁移。

## 3. 数据模型

```kotlin
internal data class ModelProfile(
    val id: String,          // 稳定标识。选中状态引用它，不引用下标
    val name: String,        // 显示名，「中转 Opus」
    val baseUrl: String,     // 空串 = 官方端点
    val modelId: String,     // claude-opus-5 / deepseek-flash[1m]
    val authKind: AuthKind,
)

internal enum class AuthKind { API_KEY, AUTH_TOKEN }
```

**密钥不在这个结构里。** 结构会被序列化进 XML，密钥不进 XML。

`id` 用 `UUID` 生成，一旦创建不再改变 —— 名称可改、端点可改，改名不该让密钥失联。

### 3.1 为什么 `authKind` 要显式存

两个变量语义不同，猜错就是 401：

| 变量 | 请求头 | 谁在用 |
|---|---|---|
| `ANTHROPIC_API_KEY` | `x-api-key` | 官方 Anthropic |
| `ANTHROPIC_AUTH_TOKEN` | `Authorization: Bearer` | 多数第三方网关 |

新建配置时**按 `baseUrl` 是否为空推断一个默认值**（空 → `API_KEY`，非空 → `AUTH_TOKEN`），
但存下来、可改。不做"运行时自动猜" —— 猜错的症状是 401，
而 401 看起来和"密钥填错了"一模一样，用户会去反复检查密钥。

## 4. 存储：全局一份，密钥进 PasswordSafe

**配置列表是 Application 级的**，新建一个 `ModelProfiles : PersistentStateComponent`，
挂在 application 上而不是 project 上。

理由（已定）：同一套中转站通常到处都在用，换项目不该重配。
代价是做不到"这个项目只给用便宜模型" —— 记在 §12。

**密钥存 `PasswordSafe`**，用 `CredentialAttributes(generateServiceName("CCoder", profile.id))`。
增删改配置时同步维护；删除配置时一并清掉密钥。

读不到密钥时**报错，不静默降级**：静默降级会让用户以为在用第三方模型，
实际打到了官方端点上 —— 那就成了一条会花错钱的静默失败。

## 5. 启动：profile → 环境变量

通路是现成的，**协议一行都不用改**：

- `session.js:64` 已经在走 `env: buildChildEnv(process.env, envOverrides)`
- `env.js` 的黑名单里**只有 `ANTHROPIC_MODEL`**，不碰 `BASE_URL` / `API_KEY` / `AUTH_TOKEN`

映射：

| ModelProfile 字段 | 环境变量 | 条件 |
|---|---|---|
| `baseUrl` | `ANTHROPIC_BASE_URL` | 非空才设 —— 空值会覆盖掉默认端点 |
| 密钥 + `authKind` | `ANTHROPIC_API_KEY` 或 `ANTHROPIC_AUTH_TOKEN` | 二选一，按 `authKind` |
| `modelId` | 走 `options.model`（`session.js:88`） | 不是环境变量 |

`modelId` 刻意不走环境变量：`ANTHROPIC_MODEL` 在黑名单里
（宿主隔离的一部分，见 `env.js` 的注释），而 `options.model` 这条路本来就通。

## 6. 优先级：profile 赢

| 来源 | 谁赢 |
|---|---|
| 选中的 profile 产生的 env | **赢** |
| `envOverrides`（用户手填的键值对） | 输 |
| `ClaudeSettings.model` | 只在没选中任何 profile 时生效 |

profile 赢是因为它是**显式选择**：用户从列表里挑了一条，就是说了"现在用这个"。
`envOverrides` 是"总是追加的背景设置"，背景不该盖过当次选择。

UI 上要提示冲突：如果 `envOverrides` 里存在 `ANTHROPIC_BASE_URL` 或两个认证变量之一，
在模型页**列表上方**挂一条常驻警告条，点名是哪几个键、说明它们会被选中的模型配置覆盖。

放在列表上方而不是表单里：冲突和"当前编的是哪一条"无关，它是整页的事。

## 7. UI：设置对话框（方案 A）

`DialogWrapper`，860×540，左侧竖排页签。

```
┌─ 设置 ──────────────────────────────────────────────── ✕ ─┐
│ ┌────────┬──────────────┬──────────────────────────────┐ │
│ │ 模型    │ ● DeepSeek   │ DeepSeek                     │ │
│ │ 通用    │   官方 Sonnet │ ⚠ 切换模型会重开会话…          │ │
│ │ 权限    │   中转 Opus   │ 名称      [DeepSeek        ] │ │
│ │ 关于    │              │ Base URL  [https://api.d…  ] │ │
│ │        │ ＋ 添加模型    │ 认证方式  [Bearer ▾        ] │ │
│ │        │              │ API Key   [sk-••••••••  👁 ] │ │
│ │        │              │ 模型 ID   [deepseek-flash… ] │ │
│ └────────┴──────────────┴──────────────────────────────┘ │
│                       改动即时保存   [取消] [确定]         │
└──────────────────────────────────────────────────────────┘
```

- **本版只做「模型」一页**，其余三页签先不放（用户明确说了"我现在想做一个页签"）
- 列表行的选中圆点即"当前使用的模型"
- **字段顺序**：名称 → Base URL → 认证方式 → API Key → 模型 ID。
  「认证方式」必须在密钥**之前** —— 它决定密钥填的是哪一种，先填密钥再选方式是反着的
- 「删除」放在表单**底部左**，与「取消/确定」分开 —— 它和"保存这次编辑"不是一类动作
- 密钥字段默认打码，👁 切换明文；失焦即恢复打码

## 8. UI：左下角标签

现在的 `buildModelLabel()`（`ComposerToolbar.kt:158`）是个不可点的 `JLabel`。
改成照 `ModeLabel`（`ComposerMode.kt:39`）做 —— 同一行里两个标签，不该一个能点一个不能：

- 文字 + `▾`
- 悬停给一层底色（`ModeLabel` 现在也没有，一并补上）
- 点击 → 弹列表：当前项打勾、每项附 `modelId`、底部一条「⚙ 管理模型…」

**这个弹层只负责"切"，不负责"改"。** 改是设置那一页的事 ——
把编辑放进这个弹层会让"点一下切换"变成"点一下进表单"，高频动作被低频动作拖累。

「管理模型…」打开设置对话框并定位到模型页。

## 9. 切换语义

**切换 = 重开会话。** 模型是会话启动参数（`toStartParams` → `start` → `options.model`），
没有热切换这条路。所以：

- **会话空闲** → 直接切：`stopSession()` + `startSession()`，复用 `restartSession()`
  （`ClaudePanel.kt:886`）的路径，转写历史留着
- **会话进行中**（`busy`） → 先弹确认框：「切换会重开会话，这段对话的上下文不保留」，
  按钮是「切换并重开」/「取消」

这条提示**不能省**。少了它，用户会以为切完还能接着聊，等发现上下文没了已经晚了。

## 10. 新增与改动

**新增**

| 文件 | 内容 |
|---|---|
| `settings/ModelProfile.kt` | 数据类 + `AuthKind` + `modelProfileEnv()` 纯函数 |
| `settings/ModelProfiles.kt` | Application 级 `PersistentStateComponent`，含密钥读写 |
| `settings/ModelProfilesDialog.kt` | 设置对话框（`DialogWrapper`） |
| `ui/ModelLabel.kt` | 可点的模型标签，照 `ModeLabel` |
| `ui/ModelChooser.kt` | 切换弹层 |

**改动**

| 文件 | 改什么 |
|---|---|
| `ui/ComposerToolbar.kt` | `buildModelLabel()` → 返回 `ModelLabel`；补悬停 |
| `ui/ClaudePanel.kt` | 顶行（`:307` 那个 `top`）的 EAST 槽加齿轮，排在「＋」左边；接标签点击；切换走确认框 + `restartSession` |
| `settings/ClaudeSettings.kt` | `toStartParams` 合并选中 profile 的 env；`model` 降级为回退值 |
| `sidecar/env.js` | **不动**（黑名单本来就不拦这三个变量） |
| `sidecar/session.js` | **不动**（`env` 通路现成） |

## 11. 测试

**纯函数（可单测）**

- `modelProfileEnv(profile, secret)`：空 `baseUrl` 不产 `ANTHROPIC_BASE_URL`；
  `authKind` 两值各自产出正确的变量名；密钥为空时抛错而不是产出空值
- 优先级合并：profile 的 env 覆盖 `envOverrides`；无选中 profile 时回落原行为
- 默认 `authKind` 推断：空 `baseUrl` → `API_KEY`，非空 → `AUTH_TOKEN`

**持久化**

- `ModelProfiles` 往返序列化（含 id 稳定）
- 密钥不出现于序列化结果 —— 这条要**对着产出的 XML 断言**，不是对着字段

**UI**

- `ModelLabel`：文字随选中项变、悬停/点击接线
- 切换弹层：当前项打勾、点「管理模型…」的接线
- 设置对话框：列表与表单联动、删除时清密钥

**手工冒烟**（装包后人眼确认，与 status-cards 同一档）

1. 新建两条第三方配置 → 标签点开能看到两条
2. 选第二条 → 转写区出现"切换并重开"确认框 → 确认后新会话生效
3. **断网或用错密钥 → 报错要说明是哪个端点、哪种认证方式**（不是干巴巴的 401）
4. 重启 IDE → 选中项还在，密钥还在（PasswordSafe 持久化）
5. 删掉一条 → 密钥一并清掉（用 `PasswordSafe` 复查）

## 12. 代价

- **配置全局共享**：做不到"这个项目只给用便宜模型"。真要那样得加一层项目级覆盖，
  本版不做（§13）。
- **要自己写对话框**：`DialogWrapper` + 三栏布局 + 密钥字段，是这个改动里最大的一块。
- **切换会丢上下文**：这是模型作为启动参数的固有代价，不是实现选择。
  除非 SDK 将来提供运行时换模型的口子。

## 13. 明确不做的

- **其余三个页签**（通用/权限/关于）—— 本版只做模型页，页签位置留好
- **项目级覆盖**（全局列表 + 项目选默认）—— 见 §12
- **`ANTHROPIC_CUSTOM_HEADERS`** —— SDK 支持，但先不开；有需要再加一个"自定义头"字段
- **模型的连通性测试按钮**（填完点一下验证密钥对不对）—— 有价值，但会引入网络调用与超时，
  单独一轮再做。本版靠冒烟第 3 条的错误信息质量兜底
- **从 `envOverrides` 自动迁移** —— 不做静默搬迁。用户在 `envOverrides` 里手写过
  `ANTHROPIC_BASE_URL` 的话，靠 §6 的冲突警告提示，由用户自己决定
- **不碰 `SidecarMessage.Exit` 那段死代码** —— 与本设计无关
