import { describe, expect, it } from 'vitest'
import { splitRefs } from './refs'

/** 与 Kotlin 侧 refToken 拼出来的一模一样。 */
const T = '⟦src/a/X.kt 24-27 · 4 行⟧'

describe('参考记号的切分', () => {
  it('没有记号时就是原样一段', () => {
    expect(splitRefs('普通的一句话')).toEqual([{ text: '普通的一句话', ref: false }])
  })

  it('记号在中间：切成 文字 / 记号 / 文字', () => {
    expect(splitRefs(`看这里 ${T} 为什么`)).toEqual([
      { text: '看这里 ', ref: false },
      { text: T, ref: true },
      { text: ' 为什么', ref: false },
    ])
  })

  it('两个记号各自成段', () => {
    const second = '⟦B.kt 1 · 1 行⟧'
    expect(splitRefs(`${T} 和 ${second}`).map((s) => s.ref)).toEqual([true, false, true])
  })

  it('整条就是一个记号', () => {
    expect(splitRefs(T)).toEqual([{ text: T, ref: true }])
  })

  it('符号引用那种形状（名字在前）照样认得', () => {
    const symbol = '⟦parseWithConfig · src/a/Y.kt 12-18 · 7 行⟧'
    expect(splitRefs(`看看 ${symbol}`).map((s) => s.ref)).toEqual([false, true])
  })

  it('不成对的 ⟦ 当普通文字 —— 拿不准就什么都别做', () => {
    expect(splitRefs('半个 ⟦ 记号')).toEqual([{ text: '半个 ⟦ 记号', ref: false }])
  })

  it('空串给空数组', () => {
    expect(splitRefs('')).toEqual([])
  })

  it('拼回去必须等于原文（换行也不能丢）', () => {
    const text = `第一行 ${T}\n第二行\n${T} 收尾`
    expect(splitRefs(text).map((s) => s.text).join('')).toBe(text)
  })
})
