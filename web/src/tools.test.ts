import { beforeEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setLang } from './i18n'
import {
  toolCommand,
  toolDelta,
  toolDiff,
  toolFile,
  toolParams,
  toolTitle,
  toolBadgeOf,
} from './tools'

// ESM 里没有 __dirname —— 用 import.meta.url 推导（同 codec.test.ts）
const HERE = dirname(fileURLToPath(import.meta.url))

/**
 * 语言钉在中文：这个文件里断言的多是**数据**（命令、路径、JSON），
 * 与语言无关，只有 toolParams 那句分隔头是文案 —— 它另有两条专门的用例
 * （中英各一），自己会切语言。钉住是为了让整个文件有个确定的起点。
 */
beforeEach(() => {
  setLang('zh')
})

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

  it('Bash 有 description 时用它当摘要 —— 卡面不铺命令原文', () => {
    const input = JSON.stringify({
      command: 'npm test -- --run\nnode tools/probe.mjs',
      description: '跑前端单测',
    })
    expect(toolTitle('Bash', input)).toBe('跑前端单测')
  })

  it('Bash 的 description 空着时退回命令首行', () => {
    expect(toolTitle('Bash', JSON.stringify({ command: 'ls -la', description: '' }))).toBe('ls -la')
  })

  it('description 是 Bash 专属：别的工具带了也不改标题', () => {
    // 规则外溢会让 Read 的标题变成一句和文件无关的话
    const input = JSON.stringify({ file_path: '/x/y/A.kt', description: '读一下' })
    expect(toolTitle('Read', input)).toBe('A.kt')
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

describe('toolCommand', () => {
  it('给整条命令 —— 多行也不截断', () => {
    // 展开体的职责是"真正跑了什么"，首行取舍只属于卡面
    const input = JSON.stringify({ command: 'set -e\nnode --test\nnode tools/probe.mjs' })
    expect(toolCommand('Bash', input)).toBe('set -e\nnode --test\nnode tools/probe.mjs')
  })

  it('非 Bash 给空串 —— 让「参数原文」那条兜底继续生效', () => {
    expect(toolCommand('Read', JSON.stringify({ file_path: '/a' }))).toBe('')
    expect(toolCommand('mcp__codegraph__explore', JSON.stringify({ query: 'q' }))).toBe('')
  })

  it('参数坏掉、没有命令时给空串，不抛', () => {
    expect(toolCommand('Bash', '{{{')).toBe('')
    expect(toolCommand('Bash', '{}')).toBe('')
  })
})

describe('toolFile', () => {
  it('Read 给路径与行号（offset 是 1 基，原样上传）', () => {
    const input = JSON.stringify({ file_path: 'C:\\p\\src\\A.kt', offset: 120, limit: 40 })
    expect(toolFile('Read', input)).toEqual({ path: 'C:\\p\\src\\A.kt', line: 120 })
  })

  it('Read 没有 offset、或它不是一个正经行号时，不给行号', () => {
    expect(toolFile('Read', JSON.stringify({ file_path: '/p/A.kt' }))).toEqual({ path: '/p/A.kt' })
    expect(toolFile('Read', JSON.stringify({ file_path: '/p/A.kt', offset: 0 }))).toEqual({
      path: '/p/A.kt',
    })
    expect(toolFile('Read', JSON.stringify({ file_path: '/p/A.kt', offset: '3' }))).toEqual({
      path: '/p/A.kt',
    })
  })

  it('编辑类工具取 file_path，但都不给行号', () => {
    // 它们的参数里没有行号信息，猜一个等于把人指到错的地方
    for (const name of ['Write', 'Edit', 'MultiEdit', 'NotebookEdit']) {
      expect(toolFile(name, JSON.stringify({ file_path: '/p/A.kt' }))).toEqual({ path: '/p/A.kt' })
    }
  })

  it('NotebookRead 走 notebook_path', () => {
    expect(toolFile('NotebookRead', JSON.stringify({ notebook_path: '/p/n.ipynb' }))).toEqual({
      path: '/p/n.ipynb',
    })
  })

  it('没有单一路径的工具给 null', () => {
    expect(toolFile('Bash', JSON.stringify({ command: 'ls' }))).toBeNull()
    expect(toolFile('Grep', JSON.stringify({ pattern: 'x' }))).toBeNull()
    expect(toolFile('Glob', JSON.stringify({ pattern: '**/*.kt' }))).toBeNull()
  })

  it('参数坏掉、缺路径、路径空白时给 null，不抛', () => {
    expect(toolFile('Read', '{{{')).toBeNull()
    expect(toolFile('Read', '{}')).toBeNull()
    expect(toolFile('Read', JSON.stringify({ file_path: '' }))).toBeNull()
    expect(toolFile('Edit', JSON.stringify({ old_string: 'a', new_string: 'b' }))).toBeNull()
  })

  it('路径原样透传 —— 归一化只在 Kotlin 一侧做一次', () => {
    const input = JSON.stringify({ file_path: '.\\web\\..\\web/src/A.tsx' })
    expect(toolFile('Read', input)).toEqual({ path: '.\\web\\..\\web/src/A.tsx' })
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

  it('MultiEdit 的多处编辑按顺序铺成一条删/加列表', () => {
    // 它一直躺在 FILE_TOOLS 与徽标表里（卡片认得文件名和铅笔图标），
    // 就是从来没有 diff，也没有 +N −N（2026-09-24 补）
    const input = JSON.stringify({
      file_path: 'c.txt',
      edits: [
        { old_string: 'one', new_string: '1' },
        { old_string: 'two', new_string: '2' },
      ],
    })
    expect(toolDiff('MultiEdit', input)).toEqual([
      { kind: 'del', text: 'one' },
      { kind: 'add', text: '1' },
      { kind: 'del', text: 'two' },
      { kind: 'add', text: '2' },
    ])
  })

  it('edits 是空数组时给 null —— 空数组在 JS 里是 truthy', () => {
    // 不拦的话卡片上会多出一个空的 diff 块（真机上就是一条空边框）
    expect(toolDiff('MultiEdit', JSON.stringify({ edits: [] }))).toBeNull()
  })

  it('MultiEdit 里有一处缺字段，整条给 null', () => {
    const input = JSON.stringify({
      edits: [{ old_string: 'a', new_string: 'b' }, { old_string: 'c' }],
    })
    expect(toolDiff('MultiEdit', input)).toBeNull()
  })

  it('Windows 换行留下的 \\r 不留到行尾', () => {
    // `.tool__line` 是 white-space: pre —— 一个 \r 就是一次换行，行里会多出一截空白
    const input = JSON.stringify({ old_string: 'a\r\nb', new_string: 'a\r\nc' })
    expect(toolDiff('Edit', input)).toEqual([
      { kind: 'del', text: 'a' },
      { kind: 'del', text: 'b' },
      { kind: 'add', text: 'a' },
      { kind: 'add', text: 'c' },
    ])
  })
})

/**
 * 规则在这个仓库里说了**两遍**：这一份，与 Kotlin 的 `ToolDiff.kt`。
 *
 * 权限审批框（"before it lands"那一次）画的是 Kotlin 那份，而转写区画的是这一份 ——
 * 同一次改动，批准前和批准后长得不一样，用户会以为批错了。所以规则不由注释保证，
 * 由这份两端共读的表保证：任一侧改了规则，另一侧的测试就红。
 */
describe('契约 fixture', () => {
  const FIXTURE = resolve(HERE, '../../shared/tool-diff.json')

  it('与 Kotlin 侧读取的是同一份文件', () => {
    // 路径指向仓库根的 shared/ 而不是 web/ 内部 —— 这正是"共享"的意义（同 codec.test.ts）
    expect(FIXTURE).toContain('shared')
    expect(() => readFileSync(FIXTURE, 'utf8')).not.toThrow()
  })

  it('表里每一条都与这里算出来的一致', () => {
    const cases = JSON.parse(readFileSync(FIXTURE, 'utf8')) as Array<{
      name: string
      input: unknown
      expect: unknown
    }>
    expect(cases.length).toBeGreaterThanOrEqual(15)
    for (const one of cases) {
      const input = JSON.stringify(one.input)
      expect(toolDiff(one.name, input), input).toEqual(one.expect)
    }
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

  it('长正文按文本铺开，不是一行转义 JSON', () => {
    // ExitPlanMode 的 plan 是三千多字的计划，压成一行 `\n` 转义之后人读不了，
    // 而那正是用户要审的东西（2026-09-15 用户报"里面的内容都看不到"）。
    // 与 Kotlin 侧 permissionBody 是同一套规则。
    const plan = '# 计划\n\n第一段\n第二段'
    const out = toolParams(JSON.stringify({ plan, planFilePath: 'C:\\plans\\x.md' }))!

    expect(out.startsWith('# 计划')).toBe(true)
    expect(out).toContain('第一段\n第二段') // 真换行
    expect(out).toContain('planFilePath') // 其余字段一个都不藏
  })

  it('多行参数也算正文', () => {
    const out = toolParams(JSON.stringify({ command: 'set -e\ncd /tmp', description: '跑一段' }))!

    expect(out.startsWith('set -e\ncd /tmp')).toBe(true)
  })

  it('短参数照旧走缩进 JSON', () => {
    expect(toolParams(JSON.stringify({ command: 'ls' }))).toBe('{\n  "command": "ls"\n}')
  })

  it('正文与其余字段之间那句分隔头是文案（中文）', () => {
    const out = toolParams(JSON.stringify({ plan: '# 计划\n\n第一段', planFilePath: 'x.md' }))!

    expect(out).toContain('其余参数：')
  })

  it('英文界面下那句分隔头是英文 —— 紧随其后的 JSON 一个字都不动', () => {
    setLang('en')

    const out = toolParams(JSON.stringify({ plan: '# 计划\n\n第一段', planFilePath: 'x.md' }))!

    expect(out).toContain('Other parameters:')
    // 分隔线是排版（12 个破折号），两边的形状要一致 —— 它不进目录
    expect(out).toContain('————————————')
    // 参数值原样透传：路径不会被"翻译"
    expect(out).toContain('"planFilePath": "x.md"')
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

  it('MultiEdit 把每一处编辑的行数加起来', () => {
    const input = JSON.stringify({
      edits: [
        { old_string: 'a', new_string: 'b\nc' },
        { old_string: 'd\ne', new_string: 'f' },
      ],
    })
    expect(toolDelta('MultiEdit', input)).toEqual({ add: 3, del: 3 })
  })

  it('其他工具没有这个概念', () => {
    expect(toolDelta('Bash', JSON.stringify({ command: 'ls' }))).toBeNull()
  })
})

describe('toolBadgeOf', () => {
  it('Bash 是终端图形，归"跑"那一档', () => {
    expect(toolBadgeOf('Bash')).toEqual({ glyph: 'terminal', tone: 'run' })
  })

  it('读类：Read 稿纸、Glob 星号、Grep 放大镜 —— 同一个色调', () => {
    // 三个形状各说各的"怎么找的"，颜色则把"都是读"这件事说出来
    expect(toolBadgeOf('Read')).toEqual({ glyph: 'doc', tone: 'read' })
    expect(toolBadgeOf('Glob')).toEqual({ glyph: 'asterisk', tone: 'read' })
    expect(toolBadgeOf('Grep')).toEqual({ glyph: 'search', tone: 'read' })
  })

  it('写类：Write 是新建、Edit 与 MultiEdit 是铅笔', () => {
    expect(toolBadgeOf('Write')).toEqual({ glyph: 'newfile', tone: 'write' })
    expect(toolBadgeOf('Edit')).toEqual({ glyph: 'pencil', tone: 'write' })
    expect(toolBadgeOf('MultiEdit')).toEqual({ glyph: 'pencil', tone: 'write' })
    expect(toolBadgeOf('NotebookEdit')).toEqual({ glyph: 'pencil', tone: 'write' })
  })

  it('任务与提问各归各的档', () => {
    expect(toolBadgeOf('Task')).toEqual({ glyph: 'agent', tone: 'task' })
    expect(toolBadgeOf('TodoWrite')).toEqual({ glyph: 'checklist', tone: 'task' })
    expect(toolBadgeOf('TaskCreate')).toEqual({ glyph: 'checklist', tone: 'task' })
    expect(toolBadgeOf('AskUserQuestion')).toEqual({ glyph: 'bubble', tone: 'ask' })
  })

  it('父卡认 Agent 也认 Task —— 真机发的是 Agent', () => {
    // 少了 Agent，父卡就落到"首字母兜底"显示一个大写 A，两个小人图标不出现，
    // 而子代理那条竖线的颜色本该跟这个徽标同源（styles.css 里定的那一句）
    expect(toolBadgeOf('Agent')).toEqual({ glyph: 'agent', tone: 'task' })
    expect(toolBadgeOf('Agent')).toEqual(toolBadgeOf('Task'))
  })

  it('联网那两条都走地球', () => {
    expect(toolBadgeOf('WebFetch')).toEqual({ glyph: 'globe', tone: 'net' })
    expect(toolBadgeOf('WebSearch')).toEqual({ glyph: 'globe', tone: 'net' })
  })

  it('认不出的给 null —— 卡片退回工具名首字母', () => {
    // MCP 工具的名字本身带着信息（哪个 server、哪个 tool），
    // 硬塞一个"未知"图形反而把那点信息盖掉了
    expect(toolBadgeOf('mcp__foo__bar')).toBeNull()
    expect(toolBadgeOf('SomeFutureTool')).toBeNull()
    expect(toolBadgeOf('')).toBeNull()
  })
})
