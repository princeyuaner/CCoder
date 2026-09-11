import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Transcript } from './Transcript'
import type { TranscriptItem } from '../types'

function state(...items: TranscriptItem[]) {
  return { items, live: {} }
}

const ts = 1726050000000

describe('Transcript', () => {
  it('空转写区不报错', () => {
    render(<Transcript state={state()} />)
    expect(screen.getByTestId('transcript')).toBeInTheDocument()
  })

  it('渲染用户消息', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    expect(screen.getByText('你好')).toBeInTheDocument()
  })

  it('渲染 Claude 消息', () => {
    render(<Transcript state={state({ kind: 'assistant', id: 'a', ts, text: '在的' })} />)
    expect(screen.getByText('在的')).toBeInTheDocument()
  })

  it('渲染错误消息', () => {
    render(<Transcript state={state({ kind: 'error', id: 'e', ts, text: '认证失败' })} />)
    expect(screen.getByText('认证失败')).toBeInTheDocument()
    expect(screen.getByTestId('error-bubble')).toBeInTheDocument()
  })

  it('渲染系统提示', () => {
    render(<Transcript state={state({ kind: 'systemNote', id: 's', ts, text: '会话已就绪' })} />)
    expect(screen.getByText('会话已就绪')).toBeInTheDocument()
  })

  it('result 显示成本与耗时', () => {
    render(
      <Transcript
        state={state({ kind: 'result', id: 'r', ts, subtype: 'success', costUsd: 0.1008, durationMs: 1681 })}
      />,
    )
    expect(screen.getByText(/\$0\.1008/)).toBeInTheDocument()
    expect(screen.getByText(/1681ms/)).toBeInTheDocument()
  })

  it('result 缺省可选字段时不显示它们', () => {
    render(<Transcript state={state({ kind: 'result', id: 'r', ts, subtype: 'success' })} />)
    expect(screen.getByText('success')).toBeInTheDocument()
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
  })

  it('每条消息显示时间戳', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    // 时间戳按本地时区格式化，只断言格式而非具体值
    expect(screen.getByTestId('timestamp')).toHaveTextContent(/^\d{2}:\d{2}$/)
  })

  it('思考块默认折叠，点击后展开', async () => {
    const user = userEvent.setup()
    render(<Transcript state={state({ kind: 'thinking', id: 't', ts, text: '让我想想' })} />)

    expect(screen.queryByText('让我想想')).not.toBeInTheDocument()
    await user.click(screen.getByText(/思考过程/))
    expect(screen.getByText('让我想想')).toBeInTheDocument()
  })

  it('工具调用默认折叠，展开显示参数', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({ kind: 'toolUse', id: 'x', ts, name: 'Read', input: '{"file_path":"/a.txt"}' })}
      />,
    )

    expect(screen.getByText(/Read/)).toBeInTheDocument()
    expect(screen.queryByText(/file_path/)).not.toBeInTheDocument()
    await user.click(screen.getByText(/Read/))
    expect(screen.getByText(/file_path/)).toBeInTheDocument()
  })

  it('工具调用的参数被格式化缩进', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({ kind: 'toolUse', id: 'x', ts, name: 'Bash', input: '{"command":"ls"}' })}
      />,
    )
    await user.click(screen.getByText(/Bash/))
    // 格式化成多行 JSON，而不是原样的紧凑字符串
    expect(screen.getByText(/"command": "ls"/)).toBeInTheDocument()
  })

  it('工具调用的非法 JSON 参数按原文显示', async () => {
    const user = userEvent.setup()
    render(
      <Transcript state={state({ kind: 'toolUse', id: 'x', ts, name: 'X', input: 'not json' })} />,
    )
    await user.click(screen.getByText(/工具：X/))
    expect(screen.getByText('not json')).toBeInTheDocument()
  })

  it('进行中的气泡以流式形式渲染', () => {
    render(<Transcript state={{ items: [], live: { assistant: '正在输入' } }} />)
    expect(screen.getByText('正在输入')).toBeInTheDocument()
    expect(screen.getByTestId('streaming-cursor')).toBeInTheDocument()
  })

  it('多条消息按序渲染', () => {
    render(
      <Transcript
        state={state(
          { kind: 'user', id: 'u', ts, text: '第一' },
          { kind: 'assistant', id: 'a', ts: ts + 1, text: '第二' },
        )}
      />,
    )
    const transcript = screen.getByTestId('transcript')
    const text = transcript.textContent ?? ''
    expect(text.indexOf('第一')).toBeLessThan(text.indexOf('第二'))
  })

  it('未填充的 live 字段不渲染气泡', () => {
    render(<Transcript state={{ items: [], live: { thinking: 'x' } }} />)
    expect(screen.queryByTestId('streaming-cursor')).not.toBeInTheDocument()
  })

  it('未知 kind 不导致崩溃', () => {
    // 对应设计文档 §3.3：Kotlin 侧新增类型时旧前端不该白屏
    const bogus = { kind: 'someNewKind', id: 'z', ts } as unknown as TranscriptItem
    expect(() => render(<Transcript state={state(bogus)} />)).not.toThrow()
  })
})
