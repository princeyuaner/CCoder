import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToolRunCard } from './ToolRunCard'
import { openFile } from '../bridge'
import type { ToolResultItem, ToolUseItem } from '../types'

// 卡面上的文件是能点的：点了就走这条桥去编辑器
vi.mock('../bridge', () => ({ openFile: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
})

const use = (id: string, name: string, input: unknown): ToolUseItem => ({
  kind: 'toolUse',
  id,
  ts: 1,
  toolUseId: `toolu_${id}`,
  name,
  input: JSON.stringify(input),
})

const res = (toolUseId: string, text: string, isError = false): ToolResultItem => ({
  kind: 'toolResult',
  id: `r_${toolUseId}`,
  ts: 2,
  toolUseId,
  text,
  isError,
})

function show(uses: ToolUseItem[], results: ToolResultItem[] = [], ended: string[] = []) {
  return render(
    <ToolRunCard
      uses={uses}
      results={new Map(results.map((r) => [r.toolUseId, r]))}
      ended={new Set(ended)}
    />,
  )
}

const read = (id: string, path: string) => use(id, 'Read', { file_path: path })
const bash = (id: string, command = 'ls') => use(id, 'Bash', { command })

describe('ToolRunCard', () => {
  it('组头写着次数与那半句摘要', () => {
    show([
      read('a', '/p/tools.ts'),
      use('b', 'Grep', { pattern: 'toolTitle' }),
      use('c', 'Edit', { file_path: '/p/tools.ts', old_string: 'a', new_string: 'b' }),
      bash('d'),
    ])

    expect(screen.getByText(/次工具调用/)).toHaveTextContent('4 次工具调用 · 读 2 · 改 1 · 跑 1')
  })

  it('默认展开 —— 边跑边看，不用先点一下', () => {
    show([read('a', '/p/a.ts'), bash('b')], [res('toolu_a', 'x'), res('toolu_b', 'y')])
    expect(screen.getByTestId('run-body')).toBeInTheDocument()
  })

  it('点一下收起 —— 摘要留在卡面上', async () => {
    const user = userEvent.setup()
    show([read('a', '/p/a.ts'), bash('b')], [res('toolu_a', 'x'), res('toolu_b', 'y')])

    await user.click(screen.getByTestId('run-head'))

    expect(screen.queryByTestId('run-body')).not.toBeInTheDocument()
    expect(screen.getByText(/次工具调用/)).toBeInTheDocument()
  })

  it('里面还是原来那些卡片，各自带着自己的结果', () => {
    show([read('a', '/p/a.ts'), bash('b')], [res('toolu_b', '别的输出')])

    expect(screen.getByText('Read')).toBeInTheDocument()
    expect(screen.getByText('Bash')).toBeInTheDocument()
    // 只有 b 有结果：那一份输出只该出现在 b 那张卡上
    // （两张卡都默认展开，所以这里是"整组里只有一处输出"，不是"点开才有一处"）
    expect(screen.getAllByTestId('tool-output')).toHaveLength(1)
    expect(screen.getByTestId('tool-output')).toHaveTextContent('别的输出')
  })

  it('有失败就标出失败数，且只有那一张带失败徽标', () => {
    show(
      [bash('a'), bash('b'), bash('c')],
      [res('toolu_a', 'ok'), res('toolu_b', '2 failed', true), res('toolu_c', 'ok')],
    )

    expect(screen.getByText('含 1 处失败')).toBeInTheDocument()
    expect(screen.getByTestId('run-body')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    // 三张卡都开着，其中一张的输出里是那句失败
    const outs = screen.getAllByTestId('tool-output').map((el) => el.textContent ?? '')
    expect(outs.filter((t) => t.includes('2 failed'))).toHaveLength(1)
  })

  it('跑着的时候组头转圈，写着已完成 / 总数', () => {
    show([bash('a'), bash('b'), bash('c')], [res('toolu_a', 'ok')])

    expect(screen.getByTestId('run-running')).toBeInTheDocument()
    expect(screen.getByTestId('run-step')).toHaveTextContent('1 / 3')
    expect(screen.queryByTestId('run-done')).not.toBeInTheDocument()
  })

  it('全部配上结果 → 打勾，不再转圈', () => {
    show([bash('a'), bash('b')], [res('toolu_a', 'ok'), res('toolu_b', 'ok')])

    expect(screen.getByTestId('run-done')).toBeInTheDocument()
    expect(screen.queryByTestId('run-running')).not.toBeInTheDocument()
  })

  it('回合结束还没等到结果 → 标为已中断，不再转圈', () => {
    // 否则组头会一直转，而且转得和"真的在跑"一模一样
    show([bash('a'), bash('b')], [res('toolu_a', 'ok')], ['toolu_b'])

    expect(screen.getByTestId('run-aborted')).toBeInTheDocument()
    expect(screen.queryByTestId('run-running')).not.toBeInTheDocument()
  })

  it('卡面上列着涉及的文件，点了在编辑器里打开', async () => {
    const user = userEvent.setup()
    show([
      use('a', 'Edit', { file_path: 'C:\\p\\src\\tools.ts', old_string: 'a', new_string: 'b' }),
      read('b', 'C:\\p\\src\\tools.ts'),
    ])

    const chip = screen.getByTestId('run-file')
    expect(chip).toHaveTextContent('tools.ts') // 基名，不是整条路径
    expect(chip).toHaveAttribute('title', 'C:\\p\\src\\tools.ts')

    await user.click(chip)
    expect(openFile).toHaveBeenCalledWith('C:\\p\\src\\tools.ts')
  })

  it('文件超过三个时折成 +N', () => {
    show([read('a', '/a.ts'), read('b', '/b.ts'), read('c', '/c.ts'), read('d', '/d.ts')])

    expect(screen.getAllByTestId('run-file')).toHaveLength(3)
    expect(screen.getByText('+1')).toBeInTheDocument()
  })

  it('没有文件的组不画那一行', () => {
    show([bash('a'), bash('b')])
    expect(screen.queryByTestId('run-file')).not.toBeInTheDocument()
    expect(screen.queryByText('涉及')).not.toBeInTheDocument()
  })
})
