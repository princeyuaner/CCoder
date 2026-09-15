# 回合结束那一行：改成"本次 token + 耗时"

日期：2026-09-15　分支：`v0.2.15-dev`　`pluginVersion=0.2.15`

## 1. 要解决的问题

用户原话：「我要修改每次会话结束后的输出，我希望能展示本次 token 的消耗情况和耗时，
现在显示的是 `success · $4.5460 · 33818ms`」。

三个毛病，一个比一个轻：

1. **token 一个字都没显示** —— 而"这一轮烧了多少"正是最想知道的
2. **`33818ms` 精度过细**：毫秒位对阅读没有信息量，人读的是"半分钟"
3. **`$4.5460` 是会骗人的**：它是 CLI 的 `total_cost_usd`，语义是**累计值**
   （每次 result 给的是"到目前为止的总和"，`/clear` 还会把它清零）。
   摆在某一回合下面，谁都会读成"本次花了 4.5 美元"

## 2. 口径（这条是本次的关键，不是文案）

`SDKResultSuccess` 上同时有三个"用量"字段，语义完全不同：

| 字段 | 含义 | 能不能当"本次"用 |
|---|---|---|
| `usage` | **按回合**给，**只含主循环**（不含子代理、压缩那几次调用） | ✅ 这就是"本次" |
| `modelUsage` | 覆盖全部调用（主循环 + 子代理 + 压缩 + 内部调用），但是**累计值** | ❌ 要"本次"得自己差分 |
| `total_cost_usd` | 同上，**累计值**，`/clear` 会重置 | ❌ 同上 |

所以 token 取 `usage`（代价：子代理那部分的 token 不在里面，**这条要在设计稿里说清楚，
不能让"输入 12.4k"被读成全量**）；花费**不显示**。

## 3. 那一行写成什么（用户 2026-09-15 选定）

```
成功 · 输入 12.4k · 缓存 8.1k · 输出 1.2k · 33.8s
```

- **`success` → `成功`**；认不出的 subtype（`error_max_turns` 等）**原样留着** —— 编一个
  中文名比留着英文更糟
- **数字最多三位有效数字**：`12432 → 12.4k`、`842 → 842`、`124456 → 124k`。
  一行里可能排着四个这样的数字，而阅读者要的是量级不是个位
- **耗时**：不足一分钟给一位小数（`33.8s`），超过换成 `1m18s`。
  与**进行中的秒表**（`formatElapsed`，整秒）**故意不同**：那个每秒跳一次，给小数会看得眼花
- **缓存为 0 时整段不显示**（一行里多一个「缓存 0」是噪音）；字段缺失时同理
- **不显示花费**（见 §2）。数据仍在转写项里带着 —— 留给将来的成本面板

## 4. 落点

| 文件 | 改动 |
|---|---|
| `ui/MessageRenderer.kt` | `renderResult` 从 `usage` 取三个数；`RenderItem.Result` 加三个可空字段 |
| `sidecar/TranscriptOp.kt` / `ui/TranscriptOpCodec.kt` | 线上带这三个字段（null 时整体省略） |
| `ui/ClaudePanel.kt` | 透传 |
| `web/src/types.ts` / `codec.ts` | 收下（沿用"类型不符就丢该字段"的老规矩） |
| `web/src/components/ResultLine.tsx` | 排版与格式化：`formatTokens` / `formatResultDuration` / `resultLineText` |
| `web/src/components/Transcript.tsx` | 传新字段；不再传 `costUsd` |

## 5. 明确不做

- **不做本轮花费的差分**：也能做（留上一次的累计值相减），但 `/clear` 会重置累计值、
  差分会出现负数，得再写一套"重置检测"。用户这次只要 token 与耗时
- **不显示缓存"写"**（`cache_creation_input_tokens`）：那一行放四个数字太挤。
  数据没往线上带 —— 要的时候加两行即可
- **不做成本面板**：那是另一件事（`total_cost_usd` 的数据还在，随时能接）

## 6. 测试

- Kotlin：`MessageRendererTest`（带 `usage` / 不带 `usage` 两条）、
  `TranscriptOpCodecTest`（字段上线；null 时省略；花费仍在线）
- web：新的 `ResultLine.test.tsx`（数字阶梯、耗时两档、缓存 0 省略、subtype 原样）、
  `Transcript.test.tsx` 两条改为新契约（其中一条**钉死"花费即使有也不显示"**）

## 7. 渲染探针

**没加。** 这一行的形状没变（还是那个 `.result-line`），只是字变了 —— 探针存在的理由是
"看图才发现的观感问题"，这里没有新形状。字体的分量/颜色与原来一致。

## 附：执行记录

- 全部落地，三侧测试绿（Kotlin 全量、web 233 条、sidecar 未受影响）
- 与最初设想的偏离：用户先看了四个预览，选了"不显示花费"那版 —— 于是 `costUsd`
  从 `ResultLine` 的 props 里去掉了，但**保留在数据路径上**（将来的成本面板要用）。
  留一个没人用的字段是有意的，理由写在 `types.ts` 的注释里
- 附带发现：`web` 目录下 `npm test` 在本机这套 shell 里同样起不来（cmd 找不到 node），
  绕法与打包那条一致：`npm --script-shell="C:/Program Files/Git/bin/bash.exe" test`
