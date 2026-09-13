import { describe, expect, it } from 'vitest'
import { toolDelta, toolDiff, toolParams, toolTitle } from './tools'

/**
 * 工具卡片上那行标题。
 *
 * 这一层之所以是纯函数：ClaudePanel 起不了单测，界面上"显示哪个文件、
 * 跑了什么命令"全靠这里，而它正是设计稿里被点名抱怨的那件事。
 */
describe('toolTitle', () => {
  it('Bash 给命令原文', () => {
    expect(toolTitle('Bash', JSON.stringify({ command: 'git log --oneline -15' }))).toBe(
      'git log --oneline -15',
    )
  })

  it('Bash 多行命令只取第一行', () => {
    // 一行标题放不下整段脚本，第一行已经是信息量最大的那部分
    const input = JSON.stringify({ command: 'git log --oneline -15\n&& git branch --show-current' })
    expect(toolTitle('Bash', input)).toBe('git log --oneline -15')
  })

  it('文件类工具给文件名，而不是整条路径', () => {
    // 路径全写出来会把这一行挤爆，而窗口标题栏里已经写着项目名了
    expect(toolTitle('Read', JSON.stringify({ file_path: 'C:\\a\\b\\ClaudePanel.kt' }))).toBe(
      'ClaudePanel.kt',
    )
    expect(toolTitle('Edit', JSON.stringify({ file_path: '/x/y/z/SessionList.kt' }))).toBe(
      'SessionList.kt',
    )
    expect(toolTitle('Write', JSON.stringify({ file_path: '/x/y/new-file.ts' }))).toBe('new-file.ts')
  })

  it('Grep 给搜索词', () => {
    expect(toolTitle('Grep', JSON.stringify({ pattern: 'currentSessionId', path: 'src' }))).toBe(
      '"currentSessionId"',
    )
  })

  it('Glob 给模式', () => {
    expect(toolTitle('Glob', JSON.stringify({ pattern: '**/*.kt' }))).toBe('**/*.kt')
  })

  it('认不出的工具退回第一个字符串参数', () => {
    // MCP 工具、将来新增的工具都走这条 —— 有名字总比只写个工具名强
    expect(toolTitle('mcp__codegraph__explore', JSON.stringify({ query: 'buildSessionList' }))).toBe(
      'buildSessionList',
    )
  })

  it('参数不是 JSON 时给一段原文，不抛', () => {
    expect(toolTitle('Bash', 'not json at all')).toBe('not json at all')
  })

  it('什么都没有时给空串 —— 调用方据此不画那一行', () => {
    expect(toolTitle('Bash', '')).toBe('')
    expect(toolTitle('Bash', '{}')).toBe('')
  })
})

describe('toolDiff', () => {
  it('Edit 把旧文本标成删除、新文本标成新增', () => {
    const input = JSON.stringify({ old_string: '旧的一行\n旧的第二行', new_string: '新的一行' })
    expect(toolDiff('Edit', input)).toEqual([
      { kind: 'del', text: '旧的一行' },
      { kind: 'del', text: '旧的第二行' },
      { kind: 'add', text: '新的一行' },
    ])
  })

  it('Write 的正文全是新增行', () => {
    expect(toolDiff('Write', JSON.stringify({ content: 'a\nb' }))).toEqual([
      { kind: 'add', text: 'a' },
      { kind: 'add', text: 'b' },
    ])
  })

  it('不是编辑类工具就没有 diff', () => {
    // Bash 的"改了什么"得看输出，猜不出来
    expect(toolDiff('Bash', JSON.stringify({ command: 'ls' }))).toBeNull()
    expect(toolDiff('Read', JSON.stringify({ file_path: '/a' }))).toBeNull()
  })

  it('参数坏掉时给 null 而不是抛', () => {
    expect(toolDiff('Edit', '{{{')).toBeNull()
  })
})

describe('toolParams', () => {
  it('给缩进后的参数 JSON', () => {
    expect(toolParams('{"query":"x","projectPath":"/a"}')).toBe(
      '{\n  "query": "x",\n  "projectPath": "/a"\n}',
    )
  })

  it('不是 JSON 时给 null', () => {
    // 那种参数标题已经原样显示过了（toolTitle 的兜底），展开再写一遍
    // 就是同一句话在同一张卡片上出现两次
    expect(toolParams('not json')).toBeNull()
    expect(toolParams('')).toBeNull()
  })
})

describe('toolDelta', () => {
  it('Edit 给出加了几行、删了几行', () => {
    // 标题行右侧的 +1 −1，不用点开就知道这次改动的规模
    expect(toolDelta('Edit', JSON.stringify({ old_string: 'a\nb\nc', new_string: 'a' }))).toEqual({
      add: 1,
      del: 3,
    })
  })

  it('Write 只有新增', () => {
    expect(toolDelta('Write', JSON.stringify({ content: 'a\nb' }))).toEqual({ add: 2, del: 0 })
  })

  it('其他工具没有这个概念', () => {
    expect(toolDelta('Bash', JSON.stringify({ command: 'ls' }))).toBeNull()
  })
})
