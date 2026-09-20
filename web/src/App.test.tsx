import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

/**
 * 桥握手。
 *
 * 背景：`window.ccoder.send` 由 Kotlin 在 CefLoadHandler.onLoadEnd 里注入，
 * 而 onLoadEnd 要经 CEF 跨进程 IPC 才到 Java，必然晚于本页的 mount effect。
 * 实测（沙箱 idea.log，2026-09-11 20:22）：
 *   {"t":1500,"rootChildren":1,"pushBatchType":"function","sendType":"function",
 *    "hasTheme":false,"kotlinReady":false}
 * React 挂载了、桥也在，但 Kotlin 侧的 ready 始终为 false —— 因为 effect
 * 只在挂载时调了一次 send，那一刻桥还不存在。
 *
 * 后果不是少一条消息：Kotlin 会把**所有**转写操作滞留在 beforeReady 里，
 * 界面全空且没有任何报错，只有状态栏显示"已连接"，极具迷惑性。
 */
describe('App 与 Kotlin 的桥握手', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    delete window.ccoder
  })

  afterEach(() => {
    vi.useRealTimers()
    delete window.ccoder
  })

  it('桥晚于挂载注入时，仍会补发 ready', async () => {
    render(<App />)

    // 挂载这一刻桥还没来——这正是真实时序
    expect(window.ccoder?.send).toBeUndefined()

    // 桥随后注入（对应 Kotlin 的 onLoadEnd）
    const send = vi.fn()
    window.ccoder = { ...(window.ccoder ?? {}), send }

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(send).toHaveBeenCalledTimes(1)
    const payload = JSON.parse((send.mock.calls[0] as unknown as [string])[0])
    expect(payload).toEqual({ op: 'ready' })
  })

  it('桥已存在时立即发送，且只发一次', async () => {
    const send = vi.fn()
    window.ccoder = { send }

    render(<App />)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(send).toHaveBeenCalledTimes(1)
  })

  it('补发 ready 不会覆盖 pushBatch', async () => {
    render(<App />)
    expect(typeof window.ccoder?.pushBatch).toBe('function')

    const send = vi.fn()
    window.ccoder = { ...(window.ccoder ?? {}), send }

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(typeof window.ccoder?.pushBatch).toBe('function')
  })

  // ---- pushBatch 的调用约定 ----
  //
  // 2026-09-11 的"界面全空但零报错"故障就出在这里：Kotlin 侧把 JSON 内联成
  // 对象字面量调用（pushBatch([{...}])），而形参声明是 string。JSON.parse 收到
  // 数组后先被 String() 成 "[object Object],[object Object]" 抛 SyntaxError，
  // 被空 catch 吞掉。两侧各自的测试全绿——因为没有一条覆盖调用约定本身。

  it('收 JSON 字符串时渲染出条目', async () => {
    render(<App />)
    const batch = JSON.stringify([
      { op: 'append', item: { id: 'm0', ts: 1726050000000, kind: 'user', text: '你好' } },
    ])

    await act(async () => {
      window.ccoder?.pushBatch?.(batch)
    })

    expect(screen.getByText('你好')).toBeInTheDocument()
  })

  it('实参不是字符串时记录错误，而非静默丢弃', async () => {
    render(<App />)
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    // 模拟旧写法：传的是数组对象，不是 JSON 字符串
    const wrong = [{ op: 'append', item: { id: 'm0', ts: 1, kind: 'user', text: '不该出现' } }]
    await act(async () => {
      window.ccoder?.pushBatch?.(wrong as unknown as string)
    })

    expect(spy.mock.calls.some((c) => String(c[0]).includes('[ccoder]'))).toBe(true)
    expect(screen.queryByText('不该出现')).not.toBeInTheDocument()
    spy.mockRestore()
  })

  // ---- 语言的调用约定 ----
  //
  // 与上面 pushBatch 那一段同一个道理：语言是**跨进程的约定**，两侧各自的
  // 测试都绿也可能对不上（Kotlin 改了 `locale` 而页面从没读，或者页面把
  // 接收端挂在了 Kotlin 不去叫的名字上）。这里把那条约定整条走一遍。

  it('注入的标签定初始语言，ccoderSetLocale 换语言（不重载页面）', async () => {
    window.ccoder = { locale: 'zh' }
    // Kotlin 侧注入的那一对（见 types.ts）：改 locale，再叫醒页面挂着的 sink
    window.ccoderSetLocale = (tag: string) => {
      window.ccoder = { ...window.ccoder, locale: tag }
      window.ccoderLocaleSink?.(tag)
    }

    const { unmount } = render(<App />)
    // 接收端必须在挂载这一刻就挂上，否则切语言时没人接
    expect(typeof window.ccoderLocaleSink).toBe('function')

    await act(async () => {
      window.ccoder?.pushBatch?.(
        JSON.stringify([
          { op: 'append', item: { id: 'r', ts: 1, kind: 'result', subtype: 'success' } },
        ]),
      )
    })
    expect(screen.getByText('成功')).toBeInTheDocument()

    act(() => {
      window.ccoderSetLocale?.('en')
    })

    expect(screen.getByText('success')).toBeInTheDocument()
    // 属性一起跟上：读屏软件按它挑音库
    expect(document.documentElement.lang).toBe('en')

    // 页面卸掉之后不许再留着一个会往死页面里推语言的接收端
    unmount()
    expect(window.ccoderLocaleSink).toBeUndefined()
  })
})
