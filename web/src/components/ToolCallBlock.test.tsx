import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToolCallBlock } from './ToolCallBlock'
import { openFile } from '../bridge'
import type { ToolResultItem } from '../types'

// 打开文件走的是桥；这里只关心"点了有没有发出那次调用"
vi.mock('../bridge', () => ({ openFile: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
})

/**
 * 工具卡片（设计稿 transcript-tools.html 方案乙）。
 *
 * 用户的原话是「bash 没有显示详情，编辑文件没有显示正在编辑哪个文件」，
 * 所以断言盯的就是那两件事，外加三个最容易做错的边界：
 * 结果还没回来时不能画出空的输出区、id 对不上时不能挂错卡片、
 * 参数坏掉时不能把整条消息崩掉。
 *
 * 折叠层次只有一层：卡片收起，点开后命令、diff、输出**直接铺开**。
 * 点开卡片本身就表示"我要看这次调用"，再让人点第二下是折腾。
 *
 * 2026-09-14 追加两条：「bash 别在卡面上铺命令原文」（改摘要）与
 * 「点文件名在编辑器里打开」—— 卡面因此多了一个按钮，卡片头也从
 * <button> 变成了 div[role=button]。
 */

const use = (name: string, input: unknown, toolUseId = 'toolu_1') => ({
  kind: 'toolUse' as const,
  id: 'm1',
  ts: 1,
  toolUseId,
  name,
  input: JSON.stringify(input),
})

const result = (text: string, isError = false, toolUseId = 'toolu_1'): ToolResultItem => ({
  kind: 'toolResult',
  id: 'm2',
  ts: 2,
  toolUseId,
  text,
  isError,
})

/** 卡片的开合手柄：标题行那个按钮。 */
const header = () => screen.getByRole('button', { expanded: false })

describe('ToolCallBlock', () => {
  it('标题行显示工具名与文件名', () => {
    render(<ToolCallBlock item={use('Edit', { file_path: 'C:\\a\\b\\SessionSwitchStateTest.kt' })} />)

    expect(screen.getByText('Edit')).toBeInTheDocument()
    // 文件名而不是整条路径 —— 这一行只有 420px
    expect(screen.getByText('SessionSwitchStateTest.kt')).toBeInTheDocument()
  })

  it('Bash 的标题就是它跑的那条命令', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'gradlew test' })} />)
    expect(screen.getByText('gradlew test')).toBeInTheDocument()
  })

  it('Bash 卡面只留摘要，整条命令留到展开体', async () => {
    // 用户的第二条原话是「bash 能不要直接显示代码吗」：卡面一句话，
    // 命令全文点开才出现 —— 而且必须是**全文**，不是首行
    render(
      <ToolCallBlock
        item={use('Bash', { command: 'npm test\nnode tools/probe.mjs', description: '跑单测' })}
      />,
    )

    expect(screen.getByText('跑单测')).toBeInTheDocument()
    expect(screen.queryByText('npm test')).not.toBeInTheDocument()

    await userEvent.click(header())
    expect(screen.getByTestId('tool-command')).toHaveTextContent('node tools/probe.mjs')
  })

  it('文件名可点 → 在编辑器里打开（Read 带上 offset 行号）', async () => {
    render(<ToolCallBlock item={use('Read', { file_path: '/p/src/A.kt', offset: 120 })} />)

    await userEvent.click(screen.getByTestId('tool-file'))
    expect(openFile).toHaveBeenCalledWith('/p/src/A.kt', 120)
  })

  it('点文件名只打开文件，不会顺手把卡片开合', async () => {
    render(<ToolCallBlock item={use('Edit', { file_path: '/p/A.kt' })} />)

    await userEvent.click(screen.getByTestId('tool-file'))
    expect(openFile).toHaveBeenCalledWith('/p/A.kt', undefined)
    // 仍然是收起的那张：展开体没被点出来
    expect(header()).toBeInTheDocument()
    expect(screen.queryByTestId('tool-params')).not.toBeInTheDocument()
  })

  it('文件名进得了 Tab 序，Enter 也能打开', async () => {
    render(<ToolCallBlock item={use('Read', { file_path: '/p/A.kt' })} />)

    await userEvent.tab() // 第一站：卡片头
    await userEvent.tab() // 第二站：文件名
    const link = screen.getByTestId('tool-file')
    expect(link).toHaveFocus()

    await userEvent.keyboard('{Enter}')
    expect(openFile).toHaveBeenCalledWith('/p/A.kt', undefined)
  })

  it('卡片头用 Enter 与 Space 都能开合 —— div 化以后得自己补', async () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} result={result('out')} />)

    const head = header()
    await userEvent.tab()
    expect(head).toHaveFocus()

    await userEvent.keyboard('{Enter}')
    expect(screen.getByTestId('tool-output')).toBeInTheDocument()

    await userEvent.keyboard(' ')
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()
  })

  it('文件名显示基名，完整路径挂在 tooltip 上', () => {
    render(<ToolCallBlock item={use('Edit', { file_path: 'C:\\a\\b\\C.kt' })} />)

    const link = screen.getByTestId('tool-file')
    expect(link).toHaveTextContent('C.kt')
    expect(link).toHaveAttribute('title', 'C:\\a\\b\\C.kt')
  })

  it('没有文件路径的工具不画可点的文件名', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)
    expect(screen.queryByTestId('tool-file')).not.toBeInTheDocument()
  })

  it('带 aria-expanded 的按钮只有一个 —— 文件名按钮不许带', () => {
    // 该属性是既有用例定位"开合手柄"的锚（getByRole('button', {expanded:false})）。
    // 给文件名按钮也补一个，会让那批用例一起报"找到多个元素"
    render(<ToolCallBlock item={use('Read', { file_path: '/p/A.kt' })} result={result('内容')} />)

    expect(screen.getAllByRole('button', { expanded: false })).toHaveLength(1)
    expect(screen.getByTestId('tool-file')).not.toHaveAttribute('aria-expanded')
  })

  it('默认是收起的 —— 一屏要能扫过好几个工具', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} result={result('a\nb')} />)
    expect(header()).toBeInTheDocument()
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()
  })

  it('展开后能看到完整命令', async () => {
    render(<ToolCallBlock item={use('Bash', { command: 'git log --oneline -15' })} />)

    await userEvent.click(header())
    expect(screen.getByTestId('tool-command')).toHaveTextContent('git log --oneline -15')
  })

  it('展开后输出直接铺开，不用再点第二下', async () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} result={result('a\nb\nc')} />)

    await userEvent.click(header())
    expect(screen.getByTestId('tool-output')).toHaveTextContent('a')
  })

  it('输出很长时截断，并给出一键展开', async () => {
    const long = Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n')
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} result={result(long)} />)

    // 断言必须落在 tool-output 的**包含**匹配上：输出整块在一个 <pre> 里，
    // getByText 是精确匹配，拿它写"看不见/看得见"两条都会永远通过
    const out = () => screen.getByTestId('tool-output')

    await userEvent.click(header())
    // 折掉的尾巴不进文档：转写区是滚动阅读区，一条 40 行的输出会顶走上面所有对话
    expect(out()).not.toHaveTextContent('line 39')
    expect(screen.getByText(/展开全部 40 行/)).toBeInTheDocument()

    await userEvent.click(screen.getByText(/展开全部 40 行/))
    expect(out()).toHaveTextContent('line 39')
  })

  it('结果还没回来时不画输出区', () => {
    // 画一个空的"输出"会让人以为这次调用没输出，其实它还在跑
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()
  })

  it('失败也收着，卡面上只多一个红 ✗', async () => {
    // 2026-09-15 按用户要求改的：原先失败会自动弹开，现在不弹 ——
    // 「这次没成」红 ✗ 已经说完了，输出是细节，想看的人自己点开
    render(
      <ToolCallBlock
        item={use('Bash', { command: 'gradlew test' })}
        result={result('3 tests failed', true)}
      />,
    )

    expect(screen.getByTestId('tool-failed')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()

    // 收着不等于藏起来：点开照样看得到失败原因
    await userEvent.click(header())
    expect(screen.getByTestId('tool-output')).toHaveTextContent('3 tests failed')
  })

  it('Edit 的 diff 不点开就画在卡面上', async () => {
    // 2026-09-15 用户要求「工具卡片直接画 diff」：改文件是这条流水线上最该被看见
    // 的事，而卡片默认收着 —— 埋在展开体里等于每次都得点一下才知道改了什么
    render(
      <ToolCallBlock
        item={use('Edit', { file_path: '/a/B.kt', old_string: '旧行', new_string: '新行' })}
        result={result('updated successfully')}
      />,
    )

    // 标题行右侧不点开就知道改动规模
    expect(screen.getByTestId('tool-delta')).toHaveTextContent('+1')
    // 卡面上直接读得到改了哪两行
    const diff = screen.getByTestId('tool-diff')
    expect(diff).toHaveTextContent('旧行')
    expect(diff).toHaveTextContent('新行')

    // 展开后**不再画第二份**：同一个东西在原地长大，不是换个地方再出现
    await userEvent.click(header())
    expect(screen.getAllByTestId('tool-diff')).toHaveLength(1)
  })

  it('卡面先画几行 diff，剩下的折在"还有 N 行"后面', async () => {
    const oldLines = Array.from({ length: 9 }, (_, i) => `旧${i}`).join('\n')
    const newLines = Array.from({ length: 9 }, (_, i) => `新${i}`).join('\n')
    render(
      <ToolCallBlock
        item={use('Edit', { file_path: '/a/B.kt', old_string: oldLines, new_string: newLines })}
      />,
    )

    expect(screen.getByTestId('tool-diff').querySelectorAll('.tool__line')).toHaveLength(6)
    expect(screen.getByTestId('tool-diff-more')).toHaveTextContent('还有 12 行')

    await userEvent.click(screen.getByTestId('tool-diff-more'))

    // 点开之后画全，那句话也就没了
    expect(screen.getByTestId('tool-diff').querySelectorAll('.tool__line')).toHaveLength(18)
    expect(screen.queryByTestId('tool-diff-more')).not.toBeInTheDocument()
  })

  it('Write 的卡面也画 diff —— 整份文件都是新增', () => {
    render(
      <ToolCallBlock item={use('Write', { file_path: '/a/C.kt', content: '第一行\n第二行' })} />,
    )

    const diff = screen.getByTestId('tool-diff')
    expect(diff.querySelectorAll('.tool__line--add')).toHaveLength(2)
    expect(diff.querySelectorAll('.tool__line--del')).toHaveLength(0)
  })

  it('跑命令、读文件的卡片卡面不画 diff', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)
    expect(screen.queryByTestId('tool-diff')).not.toBeInTheDocument()
  })

  it('没有配上的结果（id 对不上）不挂到这张卡片上', () => {
    // 老版本后端不送 id、或者会话切换后残留的结果 —— 宁可不显示，也不能挂错卡片
    render(
      <ToolCallBlock
        item={use('Bash', { command: 'ls' }, 'toolu_1')}
        result={result('别人的输出', false, 'toolu_999')}
      />,
    )
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()
  })

  it('结果晚到且是失败时，卡片也不自己弹开', () => {
    // live 路径下结果本来就比调用晚到（是另一条消息）。
    // 「结果没到 → 失败」这一下不许动开合状态：用户收起过的卡片不能被
    // 一条迟到的失败结果顶开，否则是替人做决定
    const item = use('Bash', { command: 'gradlew test' })
    const { rerender } = render(<ToolCallBlock item={item} />)
    expect(header()).toBeInTheDocument()

    rerender(<ToolCallBlock item={item} result={result('3 tests failed', true)} />)
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()
    expect(screen.getByTestId('tool-failed')).toBeInTheDocument()
  })

  it('认不出的工具展开后给参数原文', async () => {
    // MCP 工具、将来新增的工具都落在这条路上：没有命令也没有 diff 可显示，
    // 但展开后至少要看得到它被喂了什么
    render(<ToolCallBlock item={use('mcp__x__y', { query: 'buildSessionList' })} />)

    await userEvent.click(header())
    expect(screen.getByTestId('tool-params')).toHaveTextContent('buildSessionList')
  })

  it('参数不是 JSON 也不崩', () => {
    render(<ToolCallBlock item={{ ...use('Bash', {}), input: '{{{' }} />)
    expect(screen.getByText('Bash')).toBeInTheDocument()
  })
})

// 设计稿 docs/design/tool-progress.html 方案丙：跑的时候转圈 + 走秒，跑完打勾。
// 状态是从"结果到没到 + 回合结没结束"推出来的，不需要协议支持。
describe('工具状态', () => {
  it('结果没到 → 转圈，且不画勾', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'npm run build' })} />)
    expect(screen.getByTestId('tool-running')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-done')).not.toBeInTheDocument()
  })

  it('结果到了 → 绿勾，不再转圈', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} result={result('out')} />)
    expect(screen.getByTestId('tool-done')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-running')).not.toBeInTheDocument()
  })

  it('失败不打勾，改打一个红 ✗', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'gradlew test' })} result={result('boom', true)} />)
    expect(screen.queryByTestId('tool-done')).not.toBeInTheDocument()
    expect(screen.queryByTestId('tool-running')).not.toBeInTheDocument()
    // 与"已中断"的 ⊘ 一眼分得开：那是灰的，这是红的（样式见 styles.css）
    expect(screen.getByTestId('tool-failed')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-aborted')).not.toBeInTheDocument()
    // 一串徽标里"失败"两个字读不出来就等于没有，✗ 得有名字
    expect(screen.getByTestId('tool-failed')).toHaveAccessibleName('失败')
  })

  it('回合已结束仍未等到结果 → 标为已中断，不许再转圈', () => {
    // 被拒绝、被中断、会话被杀掉的工具永远不会再有结果。
    // 这条不钉住的话，卡片会一直转圈，而且和"真的在跑"长得一模一样
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} turnEnded />)
    expect(screen.getByTestId('tool-aborted')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-running')).not.toBeInTheDocument()
  })

  it('配不上号的结果（id 对不上）不算完成', () => {
    render(
      <ToolCallBlock
        item={use('Bash', { command: 'ls' }, 'toolu_1')}
        result={result('别人的输出', false, 'toolu_999')}
      />,
    )
    expect(screen.queryByTestId('tool-done')).not.toBeInTheDocument()
    expect(screen.getByTestId('tool-running')).toBeInTheDocument()
  })

  it('不到一秒不显示秒数 —— 「0s」比不显示更吵', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)
    expect(screen.queryByTestId('tool-elapsed')).not.toBeInTheDocument()
  })

  it('跑着的时候每秒走一格', () => {
    vi.useFakeTimers()
    try {
      render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)
      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(screen.getByTestId('tool-elapsed')).toHaveTextContent('1s')
      act(() => {
        vi.advanceTimersByTime(2000)
      })
      expect(screen.getByTestId('tool-elapsed')).toHaveTextContent('3s')
    } finally {
      vi.useRealTimers()
    }
  })

  it('完成后停表，且不再显示秒数', () => {
    vi.useFakeTimers()
    try {
      const item = use('Bash', { command: 'ls' })
      const { rerender } = render(<ToolCallBlock item={item} />)
      act(() => {
        vi.advanceTimersByTime(3000)
      })
      expect(screen.getByTestId('tool-elapsed')).toHaveTextContent('3s')

      rerender(<ToolCallBlock item={item} result={result('out')} />)
      expect(screen.queryByTestId('tool-elapsed')).not.toBeInTheDocument()
      expect(screen.getByTestId('tool-done')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('已中断的卡片不走表', () => {
    vi.useFakeTimers()
    try {
      render(<ToolCallBlock item={use('Bash', { command: 'ls' })} turnEnded />)
      act(() => {
        vi.advanceTimersByTime(5000)
      })
      expect(screen.queryByTestId('tool-elapsed')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})

// 徽标：从"工具名首字母"换成"图形 + 类别色调"（2026-09-15 用户要求）。
// 字母只区分"名字不同"，图形才区分"这事是干嘛的"。
describe('工具徽标', () => {
  it('认得出的工具画图形，并带上类别色调', () => {
    render(<ToolCallBlock item={use('Bash', { command: 'ls' })} />)

    expect(screen.getByTestId('tool-icon-terminal')).toBeInTheDocument()
    expect(screen.getByTestId('tool-badge')).toHaveClass('tool__badge--run')
  })

  it('读类与写类分属两个色调 —— 同一排卡片扫一眼能归类', () => {
    const { unmount } = render(<ToolCallBlock item={use('Read', { file_path: '/a/B.kt' })} />)
    expect(screen.getByTestId('tool-icon-doc')).toBeInTheDocument()
    expect(screen.getByTestId('tool-badge')).toHaveClass('tool__badge--read')
    unmount()

    render(<ToolCallBlock item={use('Edit', { file_path: '/a/B.kt' })} />)
    expect(screen.getByTestId('tool-icon-pencil')).toBeInTheDocument()
    expect(screen.getByTestId('tool-badge')).toHaveClass('tool__badge--write')
  })

  it('认不出的工具退回首字母，且不带任何色调类', () => {
    // MCP 工具的名字本身带着信息，不该被一个"未知"图形盖掉
    render(<ToolCallBlock item={use('mcp__foo__bar', { query: 'x' })} />)

    const badge = screen.getByTestId('tool-badge')
    expect(badge).toHaveTextContent('M')
    expect(badge.className).toBe('tool__badge')
  })
})
