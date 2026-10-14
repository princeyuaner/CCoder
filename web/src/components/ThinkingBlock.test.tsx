import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LiveThinkingBlock, ThinkingBlock } from './ThinkingBlock'
import { setLang } from '../i18n'
import { applyPrefs } from '../prefs'

// 「思考中」/「思考过程」两个标题的断言先把语言钉住
beforeEach(() => {
  setLang('zh')
})

/**
 * 思考块。
 *
 * 为什么需要一个"进行中"的形态：实测 207 个思考块里，29% 跑过 5 秒以上
 * （P90 10.5s，最长 31.5s），而在此之前那段时间屏幕上**什么都不动** ——
 * 用户的原话是"看起来感觉像卡死了"。
 *
 * 2026-09-14 改：两个阶段都**默认展开**（用户要"流式输出"看得见）。
 *
 * 2026-10-14 改（用户要求）：标题里的**秒表换成一行思考内容**（正文最后一行，
 * 流式新起一行就翻面）—— 收起之后，转圈与那一行接着当"它在动 / 在想什么"的信号。
 *
 * 2026-09-21 加了「思考折叠」那个开关：上面那几条是**默认档**（不勾）的样子，
 * 勾上之后两种块都收起 —— 见文件末那一组。
 *
 * 正文断言一律带 `selector: 'div.thinking-text'`：单行文本时标题里那一行与正文
 * 同字，不带选择器会撞上两个元素。
 */
describe('LiveThinkingBlock（进行中）', () => {
  it('默认展开：标题是「思考中」，正文直接看得见', () => {
    render(<LiveThinkingBlock text={'在想\n再往下想'} />)
    expect(screen.getByText('思考中')).toBeInTheDocument()
    expect(screen.getByText('在想 再往下想', { selector: 'div.thinking-text' })).toBeInTheDocument()
  })

  it('带一个转圈 —— 这是"它在动"的信号本身', () => {
    render(<LiveThinkingBlock text="在想" />)
    expect(screen.getByTestId('thinking-spin')).toBeInTheDocument()
  })

  it('标题里是**最后一行**思考内容；秒数没有了', () => {
    render(<LiveThinkingBlock text={'第一行\n最后想到的一句'} />)
    expect(screen.getByTestId('thinking-peek')).toHaveTextContent('最后想到的一句')
    expect(screen.queryByTestId('thinking-elapsed')).not.toBeInTheDocument()
  })

  it('流式里新起一行，标题那一行跟着翻面', () => {
    const { rerender } = render(<LiveThinkingBlock text="甲" />)
    expect(screen.getByTestId('thinking-peek')).toHaveTextContent('甲')

    rerender(<LiveThinkingBlock text={'甲\n乙'} />)
    expect(screen.getByTestId('thinking-peek')).toHaveTextContent('乙')
  })

  it('刚起手（空文本 / 只有空白）不摆那一行', () => {
    render(<LiveThinkingBlock text={'\n  \n'} />)
    expect(screen.queryByTestId('thinking-peek')).not.toBeInTheDocument()
    expect(screen.getByText('思考中')).toBeInTheDocument()
  })

  it('点一下收起 —— 收起来之后转圈和那一行还在（"它在动"与"在想什么"都留在标题里）', async () => {
    render(<LiveThinkingBlock text="正在推敲这个方案" />)
    expect(screen.getByText('正在推敲这个方案', { selector: 'div.thinking-text' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button'))
    expect(screen.queryByText('正在推敲这个方案', { selector: 'div.thinking-text' })).not.toBeInTheDocument()
    expect(screen.getByTestId('thinking-spin')).toBeInTheDocument()
    expect(screen.getByTestId('thinking-peek')).toHaveTextContent('正在推敲这个方案')
  })
})

describe('ThinkingBlock（完成态）', () => {
  it('标题是「思考过程」，正文默认展开', () => {
    // 与进行中那块一样默认展开：否则思考一结束，屏幕上会**跳一下**
    // （同一块内容从一个形态换成另一个形态，展开态不该变）
    render(<ThinkingBlock text="想完了" />)
    expect(screen.getByText('思考过程')).toBeInTheDocument()
    expect(screen.getByText('想完了')).toBeInTheDocument()
  })
})

/**
 * 界面偏好（设置里那个「思考折叠」开关）。
 *
 * 上面那几条是**默认档**（不勾）的样子；这里测勾上之后的两种块，以及
 * **开关一改当场生效** —— 后者是 `Collapsible` 里那条复位 effect 的唯一守门人。
 */
describe('界面偏好：思考折叠', () => {
  afterEach(() => {
    // 偏好的 store 是**模块级**的：vitest 按文件隔离、同文件内会串 ——
    // 不收尾的话下一条用例会带着上一条的开关跑（见 prefs.test.ts）
    act(() => applyPrefs(undefined))
    delete window.ccoderPrefs
  })

  it('勾上之后：完成的思考块默认收起', () => {
    act(() => applyPrefs({ collapseThinking: true }))
    render(<ThinkingBlock text="想完了" />)

    expect(screen.getByText('思考过程')).toBeInTheDocument()
    expect(screen.queryByText('想完了')).not.toBeInTheDocument()
  })

  it('勾上之后：进行中那块也收起 —— 但转圈和那一行还在（"它在想"不能丢）', () => {
    act(() => applyPrefs({ collapseThinking: true }))
    render(<LiveThinkingBlock text="在想" />)

    expect(screen.getByText('思考中')).toBeInTheDocument()
    expect(screen.queryByText('在想', { selector: 'div.thinking-text' })).not.toBeInTheDocument()
    expect(screen.getByTestId('thinking-spin')).toBeInTheDocument()
    expect(screen.getByTestId('thinking-peek')).toHaveTextContent('在想')
  })

  it('渲染之后改偏好，当场跟着收/展 —— Collapsible 那条 effect 的守门人', () => {
    render(<ThinkingBlock text="想完了" />)
    expect(screen.getByText('想完了')).toBeInTheDocument()

    act(() => applyPrefs({ collapseThinking: true }))
    expect(screen.queryByText('想完了')).not.toBeInTheDocument()

    act(() => applyPrefs({ collapseThinking: false }))
    expect(screen.getByText('想完了')).toBeInTheDocument()
  })

  it('手动点开的那一块：同一个值再推一遍不许动它（幂等的那一次推送）', async () => {
    act(() => applyPrefs({ collapseThinking: true }))
    render(<ThinkingBlock text="想完了" />)
    expect(screen.queryByText('想完了')).not.toBeInTheDocument()

    // 用户自己点开
    await userEvent.click(screen.getByRole('button'))
    expect(screen.getByText('想完了')).toBeInTheDocument()

    // Kotlin 那边每次关设置对话框都会推一次同样的值 —— 不许把它按回去
    act(() => applyPrefs({ collapseThinking: true }))
    expect(screen.getByText('想完了')).toBeInTheDocument()
  })

  it('偏好真的变了才复位 —— 手动开着的那块会被拉回新默认', async () => {
    act(() => applyPrefs({ collapseThinking: true }))
    render(<ThinkingBlock text="想完了" />)
    await userEvent.click(screen.getByRole('button')) // 用户点开
    expect(screen.getByText('想完了')).toBeInTheDocument()

    // 关掉折叠：新默认是"展开"，它本来就是开的 —— 仍在
    act(() => applyPrefs({ collapseThinking: false }))
    expect(screen.getByText('想完了')).toBeInTheDocument()

    // 再勾上：新默认是"收起" —— 这一次它被拉回去
    act(() => applyPrefs({ collapseThinking: true }))
    expect(screen.queryByText('想完了')).not.toBeInTheDocument()
  })
})
