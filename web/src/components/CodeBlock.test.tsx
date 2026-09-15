import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CodeBlock } from './CodeBlock'

beforeEach(() => {
  localStorage.clear()
  // 桥是每个用例自己决定要不要挂的（挂了 = JCEF 真机，没挂 = 浏览器/探针）
  delete (window as { ccoder?: unknown }).ccoder
})

/**
 * 建立剪贴板 spy。
 *
 * 必须在 userEvent.setup() **之后**调用——setup() 会替换 navigator.clipboard
 * 为它的 stub，在它之前设的 spy 会被覆盖掉。
 */
function clipboardSpy() {
  return vi.spyOn(navigator.clipboard, 'writeText')
}

describe('CodeBlock', () => {
  it('渲染代码内容', () => {
    render(<CodeBlock code="print('hi')" lang="python" />)
    expect(screen.getByText(/print/)).toBeInTheDocument()
  })

  it('显示语言标签', () => {
    render(<CodeBlock code="x = 1" lang="python" />)
    expect(screen.getByTestId('code-lang')).toHaveTextContent('python')
  })

  it('语言为空时不显示标签', () => {
    render(<CodeBlock code="x = 1" lang="" />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.queryByTestId('code-lang')).not.toBeInTheDocument()
  })

  it('默认不换行（保护缩进）', () => {
    render(<CodeBlock code={'def f():\n    return 1'} lang="python" />)
    expect(screen.getByTestId('code-block')).not.toHaveClass('is-wrapped')
  })

  it('桥在时走桥 —— JCEF 里 navigator.clipboard 根本不存在', async () => {
    const user = userEvent.setup()
    const writeText = clipboardSpy()
    const send = vi.fn()
    ;(window as { ccoder?: unknown }).ccoder = { send }
    render(<CodeBlock code="print('hi')" lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))

    // 复制必须交给 Kotlin 去做（平台的 CopyPasteManager），
    // navigator 那条路在 JCEF 里是 undefined：点了什么都不发生
    expect(send).toHaveBeenCalledTimes(1)
    expect(JSON.parse(send.mock.calls[0][0] as string)).toEqual({ op: 'copy', text: "print('hi')" })
    expect(writeText).not.toHaveBeenCalled()
  })

  it('复制的是原始代码而非高亮后的 HTML', async () => {
    const user = userEvent.setup()
    const send = vi.fn()
    ;(window as { ccoder?: unknown }).ccoder = { send }
    const raw = 'def f(x):\n    return x < 3 and x > 1'
    render(<CodeBlock code={raw} lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))

    // 高亮会把 < > & 变成实体，复制必须拿原文
    expect(JSON.parse(send.mock.calls[0][0] as string).text).toBe(raw)
  })

  it('桥不在时退回 navigator.clipboard（浏览器里跑探针/dev 那条路）', async () => {
    const user = userEvent.setup()
    const writeText = clipboardSpy()
    render(<CodeBlock code="print('hi')" lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))

    expect(writeText).toHaveBeenCalledWith("print('hi')")
  })

  it('复制后按钮文案变为已复制', async () => {
    const user = userEvent.setup()
    render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /复制/ }))
    expect(screen.getByRole('button', { name: /已复制/ })).toBeInTheDocument()
  })

  it('换行开关切换 is-wrapped 类', async () => {
    const user = userEvent.setup()
    render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /自动换行/ }))
    expect(screen.getByTestId('code-block')).toHaveClass('is-wrapped')
  })

  it('换行开关状态持久化到 localStorage', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<CodeBlock code="x" lang="python" />)

    await user.click(screen.getByRole('button', { name: /自动换行/ }))
    unmount()

    render(<CodeBlock code="y" lang="python" />)
    expect(screen.getByTestId('code-block')).toHaveClass('is-wrapped')
  })

  it('未识别的语言不高亮但内容完整', () => {
    render(<CodeBlock code="◊◊◊ 特殊字符" lang="notalang" />)
    expect(screen.getByText(/◊◊◊/)).toBeInTheDocument()
  })

  it('未给出语言时也渲染内容', () => {
    render(<CodeBlock code="plain text" lang="" />)
    expect(screen.getByText(/plain text/)).toBeInTheDocument()
  })

  it('HTML 特殊字符被转义而非当作标签', () => {
    const { container } = render(<CodeBlock code={'<script>alert(1)</script>'} lang="xml" />)
    // 必须被转义成实体，不能真的产生 script 元素
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByText(/alert\(1\)/)).toBeInTheDocument()
  })

  it('空代码不报错', () => {
    expect(() => render(<CodeBlock code="" lang="python" />)).not.toThrow()
  })
})

describe('highlightCode', () => {
  it('已知语言产出高亮标签', async () => {
    const { highlightCode } = await import('../highlight')
    const html = highlightCode('def f():\n    pass', 'python')
    expect(html).toContain('<span')
  })

  it('别名被正确映射', async () => {
    const { highlightCode, isKnownLanguage } = await import('../highlight')
    expect(isKnownLanguage('py')).toBe(true)
    expect(isKnownLanguage('js')).toBe(true)
    expect(isKnownLanguage('sh')).toBe(true)
    expect(highlightCode('x = 1', 'py')).toContain('<span')
  })

  it('未知语言返回转义后的原文', async () => {
    const { highlightCode, isKnownLanguage } = await import('../highlight')
    expect(isKnownLanguage('notalang')).toBe(false)
    expect(highlightCode('<b>', 'notalang')).toBe('&lt;b&gt;')
  })

  it('空语言返回转义后的原文', async () => {
    const { highlightCode, isKnownLanguage } = await import('../highlight')
    expect(isKnownLanguage('')).toBe(false)
    expect(highlightCode('a & b', '')).toBe('a &amp; b')
  })
})
