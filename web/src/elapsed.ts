import { useEffect, useState } from 'react'

/**
 * 已耗时。**不到 1 秒返回 null（不显示）** —— 显示一个「0s」比不显示更吵，
 * 而几分之一秒本来也没有信息量。
 *
 * 只在"进行中"的东西上调用。完成态的耗时是**算不准**的：回放历史时调用与结果
 * 两条消息的 ts 都是回放那一刻（Kotlin 推 op 时统一取 now()），相减得到的差值
 * 只反映推送节流，不反映真实耗时。与其加个"只看大于 1 秒的"这种启发式去猜，
 * 不如只在能确定的地方显示。
 *
 * 负数（时钟回拨等异常输入）按不显示处理，不抛。
 */
export function formatElapsed(ms: number): string | null {
  if (!(ms >= 1000)) return null // 顺带挡住 NaN
  const total = Math.floor(ms / 1000)
  if (total < 60) return `${total}s`
  return `${Math.floor(total / 60)}m${total % 60}s`
}

/**
 * 从**挂载时刻**起走的秒表；[active] 为 false 时停表并返回 null。
 *
 * 起点取挂载时刻而不是消息自己的 ts：live 路径下组件正是在那条消息到达时挂载的，
 * 两者是同一时刻；而回放路径上的组件不会是 active，用不到它。这样还避开一个坑 ——
 * 回放时那些 ts 是回放那一刻，拿来相减会得到一个只反映推送节流的假耗时。
 *
 * 工具卡片的"进行中"与思考块的"思考中"共用它：两处要的是同一个东西 ——
 * 一个一直在走的秒数，用来回答"它是真的在动，还是卡住了"。
 */
export function useElapsed(active: boolean): string | null {
  const [mountedAt] = useState(() => Date.now())
  const [now, setNow] = useState(mountedAt)

  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active])

  return active ? formatElapsed(now - mountedAt) : null
}
