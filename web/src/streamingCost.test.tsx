/**
 * 流式输出时的每帧开销 —— 2026-09-14「输出窗口比较卡」的哨兵。
 *
 * 起因：`includePartialMessages` 让逐 token 增量以每 16ms 一批的频率到达，
 * 而转写区每收到一批就重渲染整段。当时 `Item` / `ToolCallBlock` / `ToolRunCard`
 * 都没有 memo，于是**每来一个字，屏幕上每一张卡片都要重跑一遍派生计算**
 * （每张卡 6 个函数各自 JSON.parse 一次参数、Write 还要把整份文件内容按行切出来），
 * 再重建整棵 JSX。实测 160 项时每帧 48.7ms —— 20fps 封顶，而且还没算浏览器布局。
 *
 * 修法是把三处 memo 起来（见各组件文件头），并让 props 保持引用稳定：
 * 工具结果**精确到那一条**传下去，而不是把整张 map 传给每个 Item ——
 * 传 map 的话任何一条结果到达都会让所有项一起失效，memo 等于没写。
 *
 * 这个文件守的就是后一条：**数量**断言不看机器快慢，谁把 map 传回去，这里立刻红。
 */
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Transcript } from './components/Transcript'
import { applyOps } from './codec'
import { toolStateOf } from './toolStatus'
import type { TranscriptItem, TranscriptState } from './types'

// 只替换要数的那个，其余照原样 —— 断言的是**调用次数**，不是行为
vi.mock('./toolStatus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./toolStatus')>()
  return { ...actual, toolStateOf: vi.fn(actual.toolStateOf) }
})

const ts = 1726050000000

/**
 * "这张卡重渲染了"的计数器。
 *
 * 特意数 [toolStateOf] 而不是 `toolTitle`：卡片里的派生计算被 useMemo 挡着，
 * 重渲染时**不一定**会重新调它们 —— 拿它计数会把"没重渲染"和"重渲染了但用了缓存"
 * 混成一个结果，断言就变成假的了（第一版就这么写歪的）。
 * 这个函数在组件体里无条件调用，一次渲染一次，没有缓存。
 */
function renderCount(): number {
  return vi.mocked(toolStateOf).mock.calls.length
}

/**
 * 一次真实会话的样子：改文件（带 60 行 diff）、跑命令（带 40 行输出）、
 * 正文、思考。**必须够重** —— 轻量场景量不出这个问题（内容太少，怎么渲染都是 1ms）。
 */
function scene(rounds: number): TranscriptState {
  const items: TranscriptItem[] = []
  for (let i = 0; i < rounds; i++) {
    items.push({
      kind: 'toolUse',
      id: `u${i}`,
      ts,
      toolUseId: `toolu_${i}`,
      name: 'Write',
      input: JSON.stringify({
        file_path: `/proj/src/File${i}.kt`,
        content: Array.from({ length: 60 }, (_, k) => `第 ${k} 行内容，写点像样的东西占位`).join('\n'),
      }),
    })
    items.push({
      kind: 'toolResult',
      id: `r${i}`,
      ts,
      toolUseId: `toolu_${i}`,
      text: Array.from({ length: 40 }, (_, k) => `ok ${k} - 输出行`).join('\n'),
      isError: false,
    })
    items.push({
      kind: 'assistant',
      id: `a${i}`,
      ts,
      text: `## 第 ${i} 步\n\n已完成编辑：\n\n- 改了 ${i} 处\n- 测试通过\n`,
    })
    items.push({ kind: 'thinking', id: `k${i}`, ts, text: '思考内容 '.repeat(30) })
  }
  return { items, live: {} }
}

/** 走 [frames] 帧流式增量，每帧一次 applyOps + 一次重渲染（与 App.tsx 同一条路）。 */
function stream(state: TranscriptState, frames: number): { state: TranscriptState; ms: number } {
  const { rerender } = render(<Transcript state={state} />)
  let next = state
  const started = performance.now()
  for (let i = 0; i < frames; i++) {
    next = applyOps(next, [{ op: 'appendDelta', target: 'assistant', text: '一' }])
    rerender(<Transcript state={next} />)
  }
  return { state: next, ms: performance.now() - started }
}

describe('流式开销', () => {
  it('正文在流的那些帧里，已有卡片一次都不重渲染', () => {
    const state = scene(30) // 30 张卡片 + 30 条正文 + 30 个思考块
    vi.mocked(toolStateOf).mockClear()

    const { rerender } = render(<Transcript state={state} />)
    const afterMount = renderCount()
    expect(afterMount).toBe(30) // 挂载时每张卡一次

    let next = state
    for (let i = 0; i < 60; i++) {
      next = applyOps(next, [{ op: 'appendDelta', target: 'assistant', text: '一' }])
      rerender(<Transcript state={next} />)
    }

    // 60 帧、每帧重渲染整段，而卡片一次都没重渲染。
    // 退回未 memo 的样子：这里是 30 + 30×60 = 1830
    expect(renderCount()).toBe(afterMount)
  })

  it('一条工具结果到达，只有那一张卡重渲染', () => {
    // 三张已完成的卡 + 一张还在跑。它们连着，因此会被并成一组（方案甲），
    // 组卡本身会因结果表变化而重渲染 —— 关键是组里那些卡片自己不该跟着重算
    const items: TranscriptItem[] = [0, 1, 2, 3].map((i) => ({
      kind: 'toolUse' as const,
      id: `u${i}`,
      ts,
      toolUseId: `toolu_${i}`,
      name: 'Bash',
      input: JSON.stringify({ command: `echo ${i}` }),
    }))
    for (const i of [0, 1, 2]) {
      items.push({
        kind: 'toolResult', id: `r${i}`, ts, toolUseId: `toolu_${i}`, text: `out ${i}`, isError: false,
      })
    }

    const { rerender } = render(<Transcript state={{ items, live: {} }} />)
    vi.mocked(toolStateOf).mockClear()

    // 第 4 张的结果到了
    const withResult: TranscriptState = {
      items: [...items, { kind: 'toolResult', id: 'r3', ts, toolUseId: 'toolu_3', text: 'out 3', isError: false }],
      live: {},
    }
    rerender(<Transcript state={withResult} />)

    // 退回去传整张 map 的话，这里会是 4（连没变的三张一起重渲染）
    expect(renderCount()).toBe(1)
  })

  it('每帧耗时在预算内（整块退化的兜底哨兵）', () => {
    const frames = 60
    const { state, ms } = stream(scene(40), frames)
    const perFrame = ms / frames

    // 数字写进日志：这不是性能目标，是"是不是又整块退回去了"的警报线。
    // 修之前实测 48.7ms/帧，修之后约 0.9ms/帧 —— 25ms 这个阈值留给慢机器的余量很大，
    // 但拦得住"又变成每帧重渲染所有卡片"那种退化
    console.log(
      `[streaming-cost] items=${state.items.length} 帧=${frames} ` +
      `每帧=${perFrame.toFixed(2)}ms`,
    )
    expect(perFrame).toBeLessThan(25)
  })

  /**
   * 正文本身很长时的那一半 —— 卡片那边的 memo 管不着它。
   *
   * 这条只有耗时可量："少建了几棵子树"没有可数的东西（BlockToken 是模块私有的，
   * 而 highlight.js 那边本来就有 useMemo 挡着，数不出来）。所以它是兜底，
   * 不是主力哨兵 —— 上面两条数量断言才是。
   */
  it('正文很长时，逐帧只重建最后那一个块', () => {
    const para =
      '一段已经流出来的正文，带**粗体**、`行内代码`、[链接](https://example.com/a)、\n' +
      '- 列表项一\n- 列表项二\n\n以及代码：\n\n```kotlin\nfun main() { println("hi") }\n```\n\n'
    // 120 遍约 1.5 万字、841 个块 —— 真实的长回复就是这个量级
    const state: TranscriptState = { items: [], live: { assistant: para.repeat(120) } }
    const { rerender } = render(<Transcript state={state} />)

    const frames = 60
    let next = state
    const started = performance.now()
    for (let i = 0; i < frames; i++) {
      next = applyOps(next, [{ op: 'appendDelta', target: 'assistant', text: '一' }])
      rerender(<Transcript state={next} />)
    }
    const perFrame = (performance.now() - started) / frames

    console.log(
      `[streaming-cost] 长正文 ${state.live.assistant.length} 字符 每帧=${perFrame.toFixed(2)}ms`,
    )
    // 逐帧重建全部 841 个块时实测 25.5ms/帧；只重建尾巴后 4.4ms（其中 3.4ms 是分词）
    expect(perFrame).toBeLessThan(12)
  })
})
