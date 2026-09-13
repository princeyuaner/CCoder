import { describe, expect, it } from 'vitest'
import { formatElapsed } from './elapsed'

/**
 * 秒表文字。工具卡片的"进行中"与思考块的"思考中"共用它。
 *
 * 这里钉住的是"什么时候**不**显示"：一个「0s」比不显示更吵，而完成态根本
 * 不该显示秒数（回放时算出来的是假数）。
 */
describe('formatElapsed', () => {
  it('不到 1 秒不显示 —— 显示「0s」比不显示更吵', () => {
    expect(formatElapsed(0)).toBeNull()
    expect(formatElapsed(999)).toBeNull()
  })

  it('秒', () => {
    expect(formatElapsed(1000)).toBe('1s')
    expect(formatElapsed(12_400)).toBe('12s')
    expect(formatElapsed(59_999)).toBe('59s')
  })

  it('超过一分钟换成 m+s', () => {
    expect(formatElapsed(60_000)).toBe('1m0s')
    expect(formatElapsed(723_000)).toBe('12m3s')
  })

  it('负数当成 0（时钟回拨等异常输入不能让它崩）', () => {
    expect(formatElapsed(-5000)).toBeNull()
  })

  it('NaN 不显示而不是崩', () => {
    expect(formatElapsed(Number.NaN)).toBeNull()
  })
})
