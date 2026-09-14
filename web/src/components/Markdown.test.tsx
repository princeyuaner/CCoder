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

  // ---- 表格 / 嵌套列表 / 任务列表 ----
  //
  // 这三类 token 原先都没有分支：嵌套列表落到 renderInline 的 default，
  // 表格落到 BlockToken 的 default —— 两个 default 都是"把 `token.raw` 原样
  // 吐出来"，于是界面上出现的是**原始 Markdown 文本**（`| 场景 | 改前 |` 与
  // `  - 嵌套一层`；2026-09-14 用真实产物截图确认）。任务列表则是另一回事：
  // marked 把 `[x] ` 从文本里摘掉了，不画勾选框的话状态整个消失。

  it('渲染表格：表头进 thead、数据行进 tbody，竖线不再原样显示', () => {
    const { container } = render(
      <Markdown text={'| 场景 | 改前 |\n| --- | --- |\n| 冷启动 | 320ms |'} />,
    )
    expect(container.querySelectorAll('thead th')).toHaveLength(2)
    expect(container.querySelectorAll('tbody tr')).toHaveLength(1)
    expect(screen.getByText('冷启动').tagName).toBe('TD')
    expect(screen.getByText('320ms').tagName).toBe('TD')
    expect(screen.queryByText(/\|/)).not.toBeInTheDocument()
  })

  it('表格列的对齐按对齐行生效', () => {
    render(<Markdown text={'| 左 | 右 |\n| :--- | ---: |\n| a | b |'} />)
    expect(screen.getByText('左')).toHaveStyle({ textAlign: 'left' })
    expect(screen.getByText('b')).toHaveStyle({ textAlign: 'right' })
  })

  it('表格里的行内代码与粗体照常渲染', () => {
    render(<Markdown text={'| 字段 | 说明 |\n| --- | --- |\n| `foo` | **必填** |'} />)
    expect(screen.getByText('foo').tagName).toBe('CODE')
    expect(screen.getByText('必填').tagName).toBe('STRONG')
  })

  it('列表项里的嵌套列表按列表渲染，不吐原始短横线', () => {
    const { container } = render(<Markdown text={'- 第一层\n  - 第二层'} />)
    expect(container.querySelectorAll('li')).toHaveLength(2)
    expect(container.querySelector('li li')).toBeInTheDocument()
    expect(screen.queryByText(/^\s*-\s/)).not.toBeInTheDocument()
  })

  it('列表项里的第二段不会与第一段粘成一行', () => {
    // marked 对项内第二段给的是 `text` / `space` / `text` 三连（不是 paragraph），
    // 中间那个 space 若按块级处理会被丢掉 → 界面上读成"一第二段"
    const { container } = render(<Markdown text={'- 一\n\n  第二段'} />)
    const li = container.querySelector('li')!
    expect(li.textContent).toContain('第二段')
    expect(li.textContent).not.toBe('一第二段')
  })

  it('任务列表画勾选框，勾选状态跟着 [x] 走', () => {
    render(<Markdown text={'- [x] 完成\n- [ ] 未完成'} />)
    const boxes = screen.getAllByRole('checkbox') as HTMLInputElement[]
    expect(boxes).toHaveLength(2)
    expect(boxes[0].checked).toBe(true)
    expect(boxes[1].checked).toBe(false)
    expect(screen.queryByText(/\[x\]|\[ \]/)).not.toBeInTheDocument()
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

  // ---- HTML 实体双重转义 ----
  //
  // marked 的 text / codespan token 的 `text` 字段是**为拼 HTML 字符串准备的**，
  // 已预先转义。本组件构造的是 React 元素（React 自己会转义），直接用它就会
  // 转两遍。实测故障：模型回复里的 "2" 显示成 &quot;2&quot;、you'd 显示成 you&#39;d。
  // 对照的原始会话记录里是干净的真实引号，确认问题出在本组件而非上游。

  it('段落里的引号与撇号不被转成 HTML 实体', () => {
    render(<Markdown text={`say "hi" and doesn't care`} />)
    expect(screen.getByText(`say "hi" and doesn't care`)).toBeInTheDocument()
    expect(screen.queryByText(/&quot;|&#39;/)).not.toBeInTheDocument()
  })

  it('尖括号与 & 按字面显示', () => {
    render(<Markdown text={'a & b, 1 < 2, 3 > 2'} />)
    expect(screen.getByText('a & b, 1 < 2, 3 > 2')).toBeInTheDocument()
    expect(screen.queryByText(/&amp;|&lt;|&gt;/)).not.toBeInTheDocument()
  })

  it('行内代码里的引号不被转义', () => {
    render(<Markdown text={'用 `a["b"]` 取值'} />)
    expect(screen.getByText('a["b"]')).toBeInTheDocument()
  })

  it('加粗、标题、列表、引用里的引号都不被转义', () => {
    render(<Markdown text={'**粗"体"**\n\n# 标"题"\n\n- 列"表"\n\n> 引"用"'} />)
    expect(screen.getByText('粗"体"')).toBeInTheDocument()
    expect(screen.getByText('标"题"')).toBeInTheDocument()
    expect(screen.getByText('列"表"')).toBeInTheDocument()
    expect(screen.getByText('引"用"')).toBeInTheDocument()
  })

  it('链接文字里的引号不被转义', () => {
    render(<Markdown text={'[点"我"](https://example.com)'} />)
    expect(screen.getByRole('link', { name: '点"我"' })).toBeInTheDocument()
  })

  it('源文本里字面的实体写法保持原样，不被二次解码', () => {
    // 模型真的写出 "&lt;" 这五个字符时，显示出来必须还是 "&lt;"，不能变成 "<"
    render(<Markdown text={'字面量 &lt; 不是小于号'} />)
    expect(screen.getByText('字面量 &lt; 不是小于号')).toBeInTheDocument()
  })

  it('行内代码里字面的实体写法同样保持原样', () => {
    // codespan 走的是 marked 另一条转义路径（全部 & 都转义），
    // 与 text token 不同，这里的还原是可逆的
    render(<Markdown text={'`字面量 &lt; 不是小于号`'} />)
    expect(screen.getByText('字面量 &lt; 不是小于号')).toBeInTheDocument()
  })

  it('行内代码里的 & 与引号按字面显示', () => {
    render(<Markdown text={'`a & b` 与 `他说 "hi"`'} />)
    expect(screen.getByText('a & b')).toBeInTheDocument()
    expect(screen.getByText('他说 "hi"')).toBeInTheDocument()
  })

  it('空文本不报错', () => {
    expect(() => render(<Markdown text="" />)).not.toThrow()
  })

  it('纯换行不产生空段落', () => {
    const { container } = render(<Markdown text={'\n\n'} />)
    expect(container.querySelectorAll('p')).toHaveLength(0)
  })
})
