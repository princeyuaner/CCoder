/**
 * 工具卡片上的标题、diff 与改动规模 —— 全是纯函数。
 *
 * 为什么单独一层：渲染组件跑在 JCEF 里、依赖 DOM，起不了又快又稳的单测，
 * 而"这次调用在改哪个文件、跑了什么命令"恰恰是设计稿里被点名的那件事
 * （见 docs/design/transcript-tools.html 方案乙）。
 *
 * 数据全在 `tool_use.input` 里 —— 命令在 command、文件在 file_path、
 * 搜索词在 pattern。**不需要后端配合**：那串 JSON 一直是原样送到前端的，
 * 只是以前没人读出来。
 */

export interface DiffLine {
  kind: 'add' | 'del'
  text: string
}

export interface ToolDelta {
  add: number
  del: number
}

/** 带文件路径的工具。它们的标题显示文件名而不是整条路径。 */
const FILE_TOOLS = new Set(['Read', 'Write', 'Edit', 'MultiEdit', 'NotebookEdit', 'NotebookRead'])

function str(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function parseArgs(input: string): Record<string, unknown> | null {
  if (!input) return null
  try {
    const parsed: unknown = JSON.parse(input)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

function firstLine(text: string): string {
  const i = text.indexOf('\n')
  return i < 0 ? text : text.slice(0, i)
}

function baseName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, '')
  const i = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return i < 0 ? trimmed : trimmed.slice(i + 1)
}

/** 按行切，并丢掉末尾那一个空行 —— 文件内容几乎总以换行结尾。 */
function lines(text: string): string[] {
  const out = text.split('\n')
  if (out.length > 1 && out[out.length - 1] === '') out.pop()
  return out
}

/**
 * 标题行右侧那句话。认不出来时给空串，调用方据此不画。
 */
export function toolTitle(name: string, input: string): string {
  const args = parseArgs(input)
  // 参数不是 JSON：原样给一段。总比什么都不显示强
  if (!args) return firstLine(input.trim())

  if (name === 'Bash') {
    const command = str(args.command)
    // 多行脚本只取第一行：一行标题放不下整段，而第一行信息量最大
    if (command) return firstLine(command)
  }

  if (FILE_TOOLS.has(name)) {
    const path = str(args.file_path) ?? str(args.notebook_path)
    // 文件名而不是整条路径 —— 路径会把这一行挤爆，项目名在窗口标题里已经有了
    if (path) return baseName(path)
  }

  if (name === 'Grep') {
    const pattern = str(args.pattern)
    if (pattern) return `"${pattern}"`
  }

  if (name === 'Glob') {
    const pattern = str(args.pattern)
    if (pattern) return pattern
  }

  if (name === 'WebFetch' || name === 'WebSearch') {
    const target = str(args.url) ?? str(args.query)
    if (target) return firstLine(target)
  }

  // 认不出的工具（MCP 那些）：第一个字符串参数。
  // 有名字总比只写个工具名强，而它们的第一个参数通常就是主题
  for (const value of Object.values(args)) {
    const text = str(value)
    if (text) return firstLine(text)
  }
  return ''
}

/**
 * 改动内容。只有编辑类工具有，其余给 null。
 *
 * **从 input 算，不看工具结果**：Edit 的结果只有一句
 * "has been updated successfully"，里面没有内容。
 */
export function toolDiff(name: string, input: string): DiffLine[] | null {
  const args = parseArgs(input)
  if (!args) return null

  if (name === 'Edit') {
    const before = str(args.old_string)
    const after = str(args.new_string)
    if (before === null || after === null) return null
    return [
      ...lines(before).map((text): DiffLine => ({ kind: 'del', text })),
      ...lines(after).map((text): DiffLine => ({ kind: 'add', text })),
    ]
  }

  if (name === 'Write') {
    const content = str(args.content)
    if (content === null) return null
    return lines(content).map((text): DiffLine => ({ kind: 'add', text }))
  }

  return null
}

/**
 * 展开后显示的参数原文（缩进过的 JSON）。不是 JSON 时给 null。
 *
 * 非 JSON 的那种，[toolTitle] 的兜底已经把它原样写在标题上了 ——
 * 这里再返一遍就是同一句话在同一张卡片上出现两次。
 */
export function toolParams(input: string): string | null {
  const args = parseArgs(input)
  return args ? JSON.stringify(args, null, 2) : null
}

/** 加了几行、删了几行。标题行右侧的 `+1 −1`，不点开就知道这次改动的规模。 */
export function toolDelta(name: string, input: string): ToolDelta | null {
  const diff = toolDiff(name, input)
  if (!diff) return null
  return {
    add: diff.filter((line) => line.kind === 'add').length,
    del: diff.filter((line) => line.kind === 'del').length,
  }
}
