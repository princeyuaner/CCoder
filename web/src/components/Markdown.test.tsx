import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Markdown } from './Markdown'
import { openLink } from '../bridge'

vi.mock('../bridge', () => ({ openLink: vi.fn() }))

beforeEach(() => vi.clearAllMocks())

describe('Markdown', () => {
  it('渲染段落', () => {
    render(<Markdown text="普通文本" />)
    expect(screen.getByText('普通文本')).toBeInTheDocument()
  })

  it('渲染行内代码', () => {
    render(<Markdown text="调用 `foo()` 即可" />)
    expect(screen.getByText('foo()')).toBeInTheDocument()
  })

  it('围栏代码块走 CodeBlock 组件', () => {
    render(<Markdown text={'```python\nx = 1\n```'} />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.getByTestId('code-lang')).toHaveTextContent('python')
  })

  it('无语言标记的代码块也能渲染', () => {
    render(<Markdown text={'```\nplain\n```'} />)
    expect(screen.getByTestId('code-block')).toBeInTheDocument()
    expect(screen.getByText(/plain/)).toBeInTheDocument()
  })

  it('链接点击走桥而非默认导航', async () => {
    const user = userEvent.setup()
    render(<Markdown text="见 [文档](https://example.com)" />)

    await user.click(screen.getByText('文档'))

    expect(openLink).toHaveBeenCalledWith('https://example.com')
  })

  it('链接点击阻止默认行为', () => {
    render(<Markdown text="[x](https://example.com)" />)

    const link = screen.getByText('x')
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    const notPrevented = link.dispatchEvent(event)

    expect(notPrevented).toBe(false)
  })

  it('渲染无序列表', () => {
    render(<Markdown text={'- 一\n- 二'} />)
    expect(screen.getByText('一')).toBeInTheDocument()
    expect(screen.getByText('二')).toBeInTheDocument()
  })

  it('渲染有序列表', () => {
    render(<Markdown text={'1. 一\n2. 二'} />)
    expect(screen.getByText('一').tagName).toBe('LI')
  })

  it('渲染粗体与斜体', () => {
    render(<Markdown text="**粗** 与 *斜*" />)
    expect(screen.getByText('粗').tagName).toBe('STRONG')
    expect(screen.getByText('斜').tagName).toBe('EM')
  })

  it('渲染标题', () => {
    render(<Markdown text="## 标题" />)
    expect(screen.getByText('标题').tagName).toBe('H2')
  })

  it('渲染引用块', () => {
    render(<Markdown text="> 引用内容" />)
    expect(screen.getByText('引用内容')).toBeInTheDocument()
  })

  it('不执行内联脚本（XSS 防护）', () => {
    render(<Markdown text={'<script>window.__pwned = true</script>'} />)
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
  })

  it('裸 HTML 不产生真实元素', () => {
    const { container } = render(<Markdown text={'<img src=x onerror="window.__pwned2=true">'} />)
    // 断言元素**根本不存在**，比"属性为空"更强：
    // 说明整段 HTML 被当作纯文本处理，而非"渲染出来但清掉了危险属性"
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText(/onerror/)).toBeInTheDocument()
  })

  it('链接的 javascript 协议不被执行', () => {
    render(<Markdown text="[点我](javascript:window.__pwned3=true)" />)
    expect((window as unknown as { __pwned3?: boolean }).__pwned3).toBeUndefined()
  })

  it('空文本不报错', () => {
    expect(() => render(<Markdown text="" />)).not.toThrow()
  })

  it('纯换行不产生空段落', () => {
    const { container } = render(<Markdown text={'\n\n'} />)
    expect(container.querySelectorAll('p')).toHaveLength(0)
  })
})
