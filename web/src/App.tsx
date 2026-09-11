import { useEffect, useState } from 'react'
import { Transcript } from './components/Transcript'
import { applyOps, parseOps } from './codec'
// window.ccoder 的全局声明在 types.ts，此处不重复声明
import type { TranscriptState } from './types'

/**
 * 等待 Kotlin 注入桥的轮询间隔。
 *
 * 桥到达的时刻取决于 CEF 的 IPC 延迟，量级在毫秒到几十毫秒；50ms 足够
 * 及时，又不会让首帧白等。
 */
const READY_POLL_MS = 50

export function App() {
  const [state, setState] = useState<TranscriptState>({ items: [], live: {} })

  useEffect(() => {
    window.ccoder = window.ccoder || {}
    window.ccoder.pushBatch = (json: string) => {
      try {
        const ops = parseOps(JSON.parse(json))
        // 用函数式更新：节流器可能在一帧内推多批，直接读 state 会丢更新
        setState((prev) => applyOps(prev, ops))
      } catch (e) {
        // 仍然不抛（畸形批次不该让界面白屏），但必须留下痕迹。
        // 这里曾经是空 catch：调用约定错了（实参传成对象而非 JSON 字符串）时
        // JSON.parse 抛 SyntaxError 被吞掉，表现为"界面全空且零报错"，
        // 排查代价极高。静默失败比报错贵得多。
        console.error('[ccoder] 推送批次解析失败：', e, String(json).slice(0, 300))
      }
    }

    // 通知 Kotlin 侧可以开始推送了。
    //
    // 必须放在 pushBatch 赋值**之后**：否则 Kotlin 收到 ready 立刻推送时
    // 会打到 undefined 上。
    //
    // 也不能只发一次。桥是 Kotlin 在 CefLoadHandler.onLoadEnd 里注入的，
    // 而 onLoadEnd 要经 CEF 跨进程 IPC 才到 Java，必然晚于本 effect。发一次
    // 而桥还没到，`?.` 会静默吞掉，Kotlin 侧就把**所有**操作滞留在缓冲区里：
    // 界面全空、无任何报错，只有状态栏显示"已连接"（实测见 App.test.tsx）。
    // 所以轮询到桥出现为止，发出后立即停。
    const sendReady = (): boolean => {
      const send = window.ccoder?.send
      if (!send) return false
      send(JSON.stringify({ op: 'ready' }))
      return true
    }

    if (sendReady()) return

    const timer = window.setInterval(() => {
      if (sendReady()) window.clearInterval(timer)
    }, READY_POLL_MS)
    return () => window.clearInterval(timer)
  }, [])

  return <Transcript state={state} />
}
