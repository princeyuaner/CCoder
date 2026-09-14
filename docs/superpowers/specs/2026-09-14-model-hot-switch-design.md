# 会话中途换模型（setModel）+ 一条配置挂多个模型

日期：2026-09-14
前置：`2026-09-13-model-profiles-design.md`（模型配置本身）

---

## 1. 要解决的问题

上一版把模型配置做成了"一条配置一个模型"，并且明确记下了代价（前置稿 §12 末句）：

> **切换会丢上下文**：这是模型作为启动参数的固有代价，不是实现选择。
> **除非 SDK 将来提供运行时换模型的口子。**

口子有了。同时，实际用起来暴露了第二件事：**同一个网关下换模型也要重开一次会话** ——
用户要的是"不改变 url 就换模型"，而"一条配置一个模型"逼着他把同一个网关拆成好几条配置，
每条都重填一遍端点和密钥。

所以这一版做两件事：

1. 一条配置可以挂**一族模型**（同一个端点/密钥，几个模型名）
2. 端点没变时换模型是**热切换**：`set_model` 控制请求，会话、上下文、转写全都留着

## 2. 前提：先证明 `set_model` 真的生效

这是整个特性唯一"可能静默不工作"的地方，所以动手之前先实测（与 effort 那次
验证 `applyFlagSettings` 是同一条规矩）。

**探针**（一次性脚本，跑完即删）：起会话（`--model deepseek-v4-flash[1m]`）→ 发一轮 →
`await query.setModel('deepseek-v4-pro[1m]')` → 再发一轮，打印每轮 result 的 `modelUsage` 键。

```
第 1 轮  modelUsage键 = ["deepseek-v4-flash[1m]"]
setModel('deepseek-v4-pro[1m]')  → 控制请求成功返回
第 2 轮  modelUsage键 = ["deepseek-v4-flash[1m]","deepseek-v4-pro[1m]"]
```

`deepseek-v4-pro[1m]` **只在切换之后出现**，第二轮确实由新模型服务。结论：

- `setModel` 在装的这版 CLI（SDK 0.3.268）上真的生效，不是只有类型声明
- `[1m]` 后缀照常被 CLI 在发请求前剥掉，走 `set_model` 也剥
- 顺带两条现场事实：**`system/init` 会重发**（第二轮那帧报的就是新模型）；
  `modelUsage` 跨轮累计，所以"键里出现了 B"才是信号，不是"键只有 B"

## 3. 数据模型

```kotlin
data class ModelProfile(
    var id: String = …,
    var name: String = "",
    var baseUrl: String = "",
    var modelIds: MutableList<String> = mutableListOf(),   // 新增：这一族模型
    var modelId: String = "",                              // 语义变成"当前用的那个"
    var authKind: String = …,
)
```

**`modelId` 的名字与类型都没动**，只改了语义。理由是老 XML 里它是属性：
删掉这个字段，用户已经配好的模型名会在升级时静默消失（`loadState` 拿到的是反序列化后的
对象，看不到原始 XML，没得救）。

不变量：**`modelId` 要么空、要么落在 `modelIds` 里**。由 `normalizeModelProfile` 维护，
只有两个入口调用它 —— `ModelProfiles.loadState` 与 `upsert`。

**空列表不会把 `modelId` 变成唯一候选。** 反过来做（空列表时把 `modelId` 补进列表）
看着更"宽容"，实际会让"删掉最后一个模型"当场把它复活。代价是**老 XML 的迁移必须在
`loadState` 里显式做**：`modelIds` 空而 `modelId` 非空时补成单项列表。这一步漏了，
用户升级完会发现模型没了。

`routingModelEnv` 一行没改：它读的 `profile.modelId` 现在正好是"这条会话启动时用的那个"。

## 4. 什么能热切、什么只能重开

**判据只有一条：端点与凭证那批环境变量变不变。**

`options.env` 在子进程 spawn 时就烤死了，中途改不了。所以：

| 情形 | 走哪条 |
|---|---|
| 同一条配置内换模型 | 热切（env 必然没变） |
| 两条**填了同样端点与密钥**的配置之间 | 热切（env 逐字相同） |
| 换了端点 / 密钥 / 认证方式 | 重开 |
| 目标那条**没配模型** | 重开 —— `setModel` 表达不了"不要模型"这个状态 |
| 没有会话在跑 | 都不是：改的只是"下次启动用哪个" |

第二行是有意放宽的：把一个网关拆成几条来管是合理的用法，那种切换没理由丢上下文。
判据在 `canHotSwitch()`（`ModelProfile.kt`），是纯函数，可单测。

`modelProfileEnv` 在"第三方端点没填密钥"时会抛。`canHotSwitch` **捕获后返回 false**，
即"证明不了它一样就重开"—— 重开那条路本来就会把这条错误原样报出来（`startSession`
的 catch）。**两个都抛也不能算相等**，所以不能写成
`runCatching{}.getOrNull() == runCatching{}.getOrNull()`。

## 5. 启动照旧带 `--model` —— 与 effort 那条刻意相反

effort 那版**不传启动参数**，靠 `Ready` 之后补一条 `applyFlagSettings`。理由是
`Options.effort` 会变成 CLI 的 `--effort`，而 `applyFlagSettings` 改的是 flag 层 ——
两个优先级来源，用户选「默认」只清得掉 flag 层、清不掉启动时那个。

**模型这边不适用**：模型永远是个具体名字，没有"清除"这一档，`--model` 与 `set_model`
改的是同一个来源（"这个会话用哪个模型"）。所以两条路都留着：

- 启动带 `--model`：保证**第一轮**就是对的
- 中途 `set_model`：保证换模型**不丢上下文**

`Ready` 里**什么都不做** —— 不像 `applyEffortToSession()` 那样补一刀。这条有用例钉住
（`起会话时不主动发 setModel`），免得后人"顺手对齐"。

## 6. 线路

| 侧 | 加了什么 |
|---|---|
| `Protocol.kt` | `SidecarMessage.ModelChanged(model)`（**广播**，没有 requestId，不进 `responseIdOf`）、parse 分支、`encodeSetModel` |
| `session.js` | `setModel(model)`：缺方法就抛，**不抄 `setPermissionMode` 的可选链**（那行在 `query === null` 时静默成功，是仓库里已记录的假回执来源） |
| `index.js` | `case 'setModel'`：判键在不在 + 非空字符串 + 成对回执（失败时成功回执必须缺席） |

**`ModelChanged` 不带 id，也不登记 `responseIdOf`。** 它有两条理由：
sidecar 失败时发的是**不带 id** 的 error，登记了 id 只会让待决请求干等到 10 秒超时；
而认领它的 `pendingModelPick` 还能拿回执里的名字与发出去的那个**对一遍**，
比 id 配对多一层校验。

**刻意不做模型名白名单**（与 `EFFORT_LEVELS` 相反）：档位是闭集，而且 CLI 对认不出的
档位**可能静默忽略**，所以那边必须本地挡；模型名是用户在自己网关上定义的、sidecar
无从知道，认不出的名字会在**下一轮请求时响亮地失败**，不是静默 —— 本地挡只会把能用的
名字限死成一份猜的清单。但**空串要挡**：它表达不了"不要模型"。

## 7. UI

**弹层按配置分组**：

```
 中转 Opus · api.relay.example.com
   ✓ claude-opus-4-6
     claude-opus-4-6[1m]
 公司中转 · api.anthropic-relay.in…   会重开会话
     claude-opus-4-6-20250929
 官方 · 官方端点                      会重开会话
     claude-sonnet-4-5
 ⚙ 管理模型…
```

- 分组而不是平铺：一次点击的后果分两类，而这两类正好按组分开。组头右侧那句
  〔会重开会话〕**把后果写在点之前**
- 那句标记与真正发生的事走**同一个函数**（`pickEffect`，`ui/ModelSwitchState.kt`）——
  各判各的会漂移，而漂移的后果是弹层说"秒切"、实际把上下文丢了
- 当前项判的是**两级**（哪条配置 + 它的哪个模型），只判配置会让一条配置的多个模型一起打勾

**标签显示当前模型 ID**（不是配置名）：同一套 URL 下的两个模型正是靠它区分，
换完还写着配置名的话，标签上看不出切了没有。这条配置没指定模型时退回配置名。

**设置对话框**：`模型 ID` 变成 ＋/－ 列表，每行一个可编辑的模型，在用的那行挂〔使用中〕。
左栏那个新建按钮从「＋ 添加模型」**改名为「＋ 添加配置」** —— 它建的是配置
（端点 + 密钥 + 一族模型），而表单里也有个添加按钮，两个同名但干的事不同。

列表**定高可滚**：表单挂在 `BorderLayout.NORTH` 上、自己不会滚，模型一多会把整张表单
顶出 540px 的对话框。

## 8. 代价 / 已知限制

- **后台活儿仍按启动时的模型跑**。起标题 / 压缩上下文 / 子代理走那六个
  `ANTHROPIC_DEFAULT_*` 变量，而 env 在 spawn 时冻死，热切换动不了它们。
  **主对话切了、子代理没切**。env 这一层没有运行时口子，不是实现取舍。
  省钱的目的会在这条路上悄悄落空 —— 这一点值得用户知道。
- **跨端点切换仍然丢上下文**，只能重开。
- **换到网关不认识的模型名时，`set_model` 会成功**（CLI 不校验名字），错误要到
  **下一轮请求**才出现。标签没有撒谎（它说的是"这一轮用哪个"，那确实是请求名），
  但要接受这个延迟。
- **网关后面的路由是它自己的事**。很多中转站按 key 路由、根本不看 model 字段 ——
  那种情况下换模型在网关侧可能没有效果。我们这一侧不可检测（`modelUsage` 是 CLI
  按自己的名字算的），与今天 `--model` 的行为一致，不是回归。
- **在设置里改掉在用的那条配置时**，标签会立刻变成新值，而正在跑的会话还是旧模型。
  这是"设置 = 待生效的选择"的固有后果，改版前就有。

## 9. 明确不做的

- **不把 `system/init` 的 `model` 当读回**。探针已经证明它会重发、而且报的是新模型，
  但它是**下一轮**才到 —— 拿它更新标签会让标签晚一整轮。回执是即时的，
  且探针已证明"控制请求成功 ⇒ 切换真的生效"，所以回执足够诚实。
- **不做运行时改路由变量**（`ANTHROPIC_DEFAULT_*`）—— env 层没有这个口子。
- **不碰 `setMaxThinkingTokens`** —— 它被 `effort` 取代了，接了就是两条路互相打架。
