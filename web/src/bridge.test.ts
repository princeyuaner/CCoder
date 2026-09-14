import { afterEach, describe, expect, it } from 'vitest'
import { openFile, openLink } from './bridge'

/**
 * 桥发出的那串字节就是 Kotlin 侧 `handleFromJs` 要解析的东西 ——
 * 这里断言的是**线上格式**，不是函数调用。
 */
function captureSend(): string[] {
  const sent: string[] = []
  window.ccoder = { send: (message: string) => sent.push(message) }
  return sent
}

afterEach(() => {
  delete window.ccoder
})

describe('openFile', () => {
  it('带行号时发 path 与 line', () => {
    const sent = captureSend()
    openFile('C:\\p\\src\\A.kt', 120)
    expect(JSON.parse(sent[0])).toEqual({ op: 'openFile', path: 'C:\\p\\src\\A.kt', line: 120 })
  })

  it('没有行号时不发 line 字段', () => {
    const sent = captureSend()
    openFile('/p/A.kt')
    expect(JSON.parse(sent[0])).toEqual({ op: 'openFile', path: '/p/A.kt' })
  })

  it('路径原样送，不做任何归一化', () => {
    const sent = captureSend()
    openFile('.\\web\\..\\web/src/A.tsx')
    expect(JSON.parse(sent[0]).path).toBe('.\\web\\..\\web/src/A.tsx')
  })

  it('桥不在时不抛 —— 页面可能比桥先就绪', () => {
    delete window.ccoder
    expect(() => openFile('/p/A.kt', 3)).not.toThrow()
  })
})

describe('openLink', () => {
  // 与 openFile 同一条通道，这里只钉住线上格式没被顺手改掉
  it('发 op 与 url', () => {
    const sent = captureSend()
    openLink('https://example.com')
    expect(JSON.parse(sent[0])).toEqual({ op: 'openLink', url: 'https://example.com' })
  })
})
