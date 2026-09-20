import { describe, expect, it } from 'vitest'
import { CATALOGS } from './strings'

/** CJK 统一表意文字区。写成转义序列而不是两个汉字，免得这段本身读不出边界 */
const CJK = /[\u4e00-\u9fff]/

/**
 * 目录本身的规矩 —— 不测具体文案，测的是"两份目录还对不对得上"。
 *
 * 为什么要这一层：文案在这个仓库里是**产品的一部分**（一条说错的文案比一个
 * 画歪的图标更贵），而中英两份目录最容易出的错不是翻得不好，是**悄悄少了一边** ——
 * 那种错在界面上表现成"某处突然显示了一个键名"，只有用户撞上才发现。
 * `zh` 的类型已经从 `en` 长出来了（strings.ts），编译期就挡一道；这里是运行期
 * 的第二张网，顺带管住类型管不到的地方。
 */
describe('目录', () => {
  it('中英键集完全一致', () => {
    expect(Object.keys(CATALOGS.zh).sort()).toEqual(Object.keys(CATALOGS.en).sort())
  })

  it('没有空文案 —— 空串在界面上就是"这里什么都没有"', () => {
    for (const [lang, catalog] of Object.entries(CATALOGS)) {
      for (const [key, value] of Object.entries(catalog)) {
        // 带上键名，红了才知道是哪一个
        expect(value.trim(), `${lang} 的 ${key} 是空的`).not.toBe('')
      }
    }
  })

  it('英文目录里一个汉字都没有', () => {
    // 中文只许出现在 zh 里。`中文` 这种"自称词"本目录里没有用到，所以不开口子 ——
    // 真要加的话得连这条豁免一起写明白，而不是让它悄悄溜过去
    for (const [key, value] of Object.entries(CATALOGS.en)) {
      expect(CJK.test(value), `en 的 ${key} 里有汉字：${value}`).toBe(false)
    }
  })

  it('占位符 {0} {1}… 两边一一对应，且从 0 开始一个不断', () => {
    for (const key of Object.keys(CATALOGS.en)) {
      const en = placeholders(CATALOGS.en[key])
      const zh = placeholders(CATALOGS.zh[key])

      expect(zh, `${key} 的占位符中英对不上`).toEqual(en)
      // 断号（比如只写 {0} {2}）说明有个参数永远填不进去，而界面上只会
      // 原样印出 `{2}` —— 编译期查不出，只有这条能查
      expect(en, `${key} 的占位符断号了`).toEqual(en.map((_, i) => i))
    }
  })
})

/** 一条文案里出现过的占位符下标，去重后升序。 */
function placeholders(value: string): number[] {
  const found = new Set<number>()
  for (const m of value.matchAll(/\{(\d+)\}/g)) found.add(Number(m[1]))
  return [...found].sort((a, b) => a - b)
}
