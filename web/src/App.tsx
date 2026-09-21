import { useEffect, useState } from 'react'
import { Transcript } from './components/Transcript'
import { applyOps, parseOps } from './codec'
import { setLang } from './i18n'
import { applyPrefs } from './prefs'
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

    // 语言：页面这一侧挂上接收端，Kotlin 换语言时叫它（不必重载页面）；
    // 同时把**注入时**那个标签读一次 —— 首次加载走的是这条路，之后
    // ready 握手里还会再回推一次（ccoderSetLocale），两次落到同一个地方，
    // 幂等（见 i18n.ts 的 setLang）。
    //
    // 读标签放在这里而不是模块顶层：模块求值早于桥注入（main.tsx 直接就渲染），
    // 顶层读会永远读到 undefined，整页钉死在基底语言上。
    window.ccoderLocaleSink = (tag: string) => setLang(tag)
    setLang(window.ccoder?.locale)

    // 偏好（思考折叠）走同一套：页面这一侧挂接收端，再把**注入的那一份**读一次。
    //
    // 读这一下不是多余的：桥可能比 React 先到 —— 那时 Kotlin 已经推过一遍，
    // 而推的时候还没有 sink（桥脚本里那句 `&&` 把它变成了"只写值"）。
    // 与语言同理，两次落到同一个地方，幂等（见 prefs.ts 的 applyPrefs）。
    window.ccoderPrefsSink = (raw: unknown) => applyPrefs(raw)
    applyPrefs(window.ccoderPrefs)

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

    // 定时器句柄用 let 存着（不是 const 提前 return）：下面两条出口
    // （立即发成 / 轮询发成）都要走到同一个清理函数。
    let timer: number | null = null
    const stopPolling = () => {
      if (timer === null) return
      window.clearInterval(timer)
      timer = null
    }

    if (!sendReady()) {
      timer = window.setInterval(() => {
        if (sendReady()) stopPolling()
      }, READY_POLL_MS)
    }

    return () => {
      stopPolling()
      // 摘掉接收端：App 卸载后 Kotlin 再推语言/偏好就是打到一个已经不存在的页面上
      delete window.ccoderLocaleSink
      delete window.ccoderPrefsSink
    }
  }, [])

  return <Transcript state={state} />
}
