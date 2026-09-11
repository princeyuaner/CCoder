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

    // 通知 Kotlin 侧可以开始推送了。必须放在 pushBatch 赋值**之后**——
    // 否则 Kotlin 收到 ready 立刻推送时会打到 undefined 上。
    window.ccoder.send?.(JSON.stringify({ op: 'ready' }))
  }, [])

  return <Transcript state={state} />
}
