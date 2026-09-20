import { beforeEach, describe, expect, it } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { setLang } from '../i18n'
import { ResultLine, formatResultDuration, formatTokens, resultLineText } from './ResultLine'

/**
 * 回合结束那一行。
 *
 * 纯格式化（数字与耗时怎么缩）在这里钉死；**观感**（那行灰字在暗色下够不够、会不会
 * 与卡片抢注意力）不在这层 —— 它没有新形状，沿用原样。
 *
 * 语言：这一行现在有中英两份文案，下面那些中文断言是**文案的回归网**
 * （`toBe` 比的是整行，最容易被顺手改坏），所以先把语言钉住；英文那一半
 * 另有两处 golden，不然英文被改歪了没有任何东西会红。
 */
beforeEach(() => {
  setLang('zh')
})
describe('token 数字', () => {
  it('不足一千照原样，避免「0.0k」这种读不出量级的写法', () => {
    expect(formatTokens(0)).toBe('0')
    expect(formatTokens(842)).toBe('842')
    expect(formatTokens(999)).toBe('999')
  })

  it('一千到十万给一位小数 —— 三位有效数字', () => {
    expect(formatTokens(1000)).toBe('1.0k')
    expect(formatTokens(1234)).toBe('1.2k')
    expect(formatTokens(12432)).toBe('12.4k')
    expect(formatTokens(99999)).toBe('100.0k') // 四舍五入到十万那一档
  })

  it('十万以上不再给小数（一行里塞不下第四个长数字）', () => {
    expect(formatTokens(124456)).toBe('124k')
    expect(formatTokens(1234567)).toBe('1.2M')
  })

  it('异常输入不抛，退成 0', () => {
    expect(formatTokens(-5)).toBe('0')
    expect(formatTokens(Number.NaN)).toBe('0')
  })
})

describe('耗时', () => {
  it('不足一分钟给一位小数', () => {
    expect(formatResultDuration(33818)).toBe('33.8s')
    expect(formatResultDuration(900)).toBe('0.9s')
  })

  it('过了一分钟换成 m和s —— 78.3s 需要心算，1m18s 不用', () => {
    expect(formatResultDuration(78300)).toBe('1m18s')
    expect(formatResultDuration(60000)).toBe('1m0s')
    expect(formatResultDuration(3599000)).toBe('59m59s')
  })

  it('差一点到一分钟不写成 60.0s', () => {
    expect(formatResultDuration(59999)).toBe('1m0s')
  })
})

describe('那一行的文字', () => {
  it('本回合的 token 与耗时，花费不出现', () => {
    expect(
      resultLineText({
        subtype: 'success',
        inputTokens: 12432,
        cacheReadTokens: 8100,
        outputTokens: 1234,
        durationMs: 33818,
      }),
    ).toBe('成功 · 输入 12.4k · 缓存 8.1k · 输出 1.2k · 33.8s')
  })

  it('缓存命中为 0 时不显示 —— 一行里多一个「缓存 0」是噪音', () => {
    expect(resultLineText({ subtype: 'success', inputTokens: 900, cacheReadTokens: 0, outputTokens: 10, durationMs: 1200 }))
      .toBe('成功 · 输入 900 · 输出 10 · 1.2s')
  })

  it('字段缺失时相应那一段整体省略（旧记录没有 token）', () => {
    expect(resultLineText({ subtype: 'success', durationMs: 33818 })).toBe('成功 · 33.8s')
    expect(resultLineText({ subtype: 'success' })).toBe('成功')
  })

  it('认不出的 subtype 原样留着 —— 编一个中文名比留着英文更糟', () => {
    expect(resultLineText({ subtype: 'error_max_turns', durationMs: 1000 }))
      .toBe('error_max_turns · 1.0s')
  })

  // ---- 英文那一半的形状 ----
  //
  // 中文那几条是锚，但只锚得住中文：英文被改成 `Success · In 12.4k` 的话，
  // 上面每一条都会照过。这里的词汇是 **CLI 的原词**（小写、`in` / `cache` / `out`），
  // 与 `success` 原样透传那条规矩是同一条线上的事
  it('英文界面下同一行：CLI 的词汇，全小写', () => {
    setLang('en')

    expect(
      resultLineText({
        subtype: 'success',
        inputTokens: 12432,
        cacheReadTokens: 8100,
        outputTokens: 1234,
        durationMs: 33818,
      }),
    ).toBe('success · in 12.4k · cache 8.1k · out 1.2k · 33.8s')
  })

  it('英文下同样省掉缓存 0 与缺失的字段', () => {
    setLang('en')

    expect(
      resultLineText({ subtype: 'success', inputTokens: 900, cacheReadTokens: 0, outputTokens: 10, durationMs: 1200 }),
    ).toBe('success · in 900 · out 10 · 1.2s')
  })
})

describe('渲染', () => {
  it('画出来就是那一行字', () => {
    render(
      <ResultLine
        subtype="success"
        inputTokens={12432}
        cacheReadTokens={8100}
        outputTokens={1234}
        durationMs={33818}
      />,
    )

    expect(screen.getByText('成功 · 输入 12.4k · 缓存 8.1k · 输出 1.2k · 33.8s')).toBeTruthy()
  })

  it('换语言时这一行跟着重画 —— 组件订阅着 i18n 的 store', () => {
    // 这一行是 `Item`（memo）底下画的，外面那层不会因为换语言重渲染它，
    // 所以订阅挂在它自己身上（ResultLine 里的 useLang）。少了那句订阅，
    // 这条会红：文字仍是上一次那种语言
    render(<ResultLine subtype="success" />)
    expect(screen.getByText('成功')).toBeInTheDocument()

    act(() => {
      setLang('en')
    })

    expect(screen.getByText('success')).toBeInTheDocument()
    expect(screen.queryByText('成功')).not.toBeInTheDocument()
  })
})
