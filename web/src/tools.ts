/**
 * 工具卡片上的标题、可点文件名、diff 与改动规模 —— 全是纯函数。
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

/**
 * 一次工具调用指向的文件。
 *
 * [path] **原样透传**（不做任何归一化）：归一化只存在于 Kotlin 一侧，
 * 两处各归一化一次就会有两份真相。
 *
 * [line] 是 **1 基**行号，与 Claude 的 `offset` 同基准；减一在 Kotlin 那一步做，
 * 只做一次。
 */
export interface ToolFileRef {
  path: string
  line?: number
}

/** 带文件路径的工具。它们的标题显示文件名而不是整条路径。 */
const FILE_TOOLS = new Set(['Read', 'Write', 'Edit', 'MultiEdit', 'NotebookEdit', 'NotebookRead'])

function str(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
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

/** 路径的最后一段。卡面只有一行，整条路径会把那一行挤爆。 */
export function baseName(path: string): string {
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
    // 摘要优先：description 是 Claude 自己写的那句人话（"运行测试"），
    // 卡面上"只留一行"要的就是它；命令原文留给展开体（见 toolCommand）
    const summary = str(args.description)
    if (summary) return firstLine(summary)
    // 没有摘要才退回命令首行 —— 卡面宁可露一行命令，也不要空着。
    // 多行脚本只取第一行：一行标题放不下整段，而第一行信息量最大
    const command = str(args.command)
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
 * 展开体里的**完整**命令。非 Bash 给空串。
 *
 * 与 [toolTitle] 分开是刻意的：卡面要的是"一句话"，详情要的是"真正跑了什么"。
 * 多行脚本在标题里只留首行是卡面的取舍，不该传染到详情里 ——（曾经传染过：
 * 详情复用了标题，于是整条命令永远看不全）。
 *
 * 非 Bash 给**空串**而不是 null：调用方拿它判断要不要回落到"参数原文"那条兜底，
 * 空串才让那条路继续生效。
 */
export function toolCommand(name: string, input: string): string {
  if (name !== 'Bash') return ''
  const args = parseArgs(input)
  if (!args) return ''
  return str(args.command) ?? ''
}

/**
 * 这次调用指向的文件。认不出来给 null，调用方据此不画可点的文件名。
 *
 * 只有真的有文件在手上的工具才给：Bash / Grep / Glob 没有单一路径，
 * 硬凑一个出来只会把用户带到错的地方。
 */
export function toolFile(name: string, input: string): ToolFileRef | null {
  if (!FILE_TOOLS.has(name)) return null
  const args = parseArgs(input)
  if (!args) return null

  const path = str(args.file_path) ?? str(args.notebook_path)
  if (path === null) return null

  // 行号只有 Read 给得出：它的 offset 就是行号（1 基，原样上传）。
  // 编辑类工具的参数里没有行号信息 —— 猜一个等于把人指到错的地方
  if (name !== 'Read') return { path }
  const offset = num(args.offset)
  return offset !== null && offset >= 1 ? { path, line: offset } : { path }
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
  if (!args) return null

  // 长正文（`ExitPlanMode` 的 `plan` 就是）按**文本**铺开，不等长地塞进 JSON 的
  // 一行字符串里 —— 那份计划有三千多字，压成一行 `\n` 转义之后人根本读不了，
  // 而这正是用户要审的东西（2026-09-15 用户报"里面的内容都看不到"）。
  // 与 Kotlin 侧 permissionBody 同一条规则、同一套阈值，两边显示的形状要一致。
  const field = longTextField(args)
  if (field === null) return JSON.stringify(args, null, 2)

  const rest: Record<string, unknown> = { ...args }
  delete rest[field]
  const body = String(args[field])
  if (Object.keys(rest).length === 0) return body
  return `${body}\n\n————————————\n其余参数：\n${JSON.stringify(rest, null, 2)}`
}

/** 超过这个长度就算"正文"（与 Kotlin 侧 LONG_FIELD_MIN_CHARS 同一个数）。 */
const LONG_FIELD_MIN_CHARS = 200

/**
 * 入参里最长的那个字符串字段 —— 只有它够长或带换行时才认。
 *
 * 按形状认而不是按字段名：`plan` 是实测到的那一个，这类"正文型入参"以后还会有。
 */
function longTextField(args: Record<string, unknown>): string | null {
  let best: string | null = null
  for (const [key, value] of Object.entries(args)) {
    if (typeof value !== 'string') continue
    if (best === null || value.length > (args[best] as string).length) best = key
  }
  if (best === null) return null
  const text = args[best] as string
  return text.length >= LONG_FIELD_MIN_CHARS || text.includes('\n') ? best : null
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

/**
 * 工具卡片左上角那个徽标画什么 —— 图形 + 色调。
 *
 * 从前那里是**工具名的首字母**（Bash→B、Read→R）。字母的毛病是它只区分"名字
 * 不同"，不区分"这事是干嘛的"：一张卡扫过去，B、R、E、G 四个字母没有任何
 * 共同语言，眼睛每次都得重新认。换成图形之后，"读 / 写 / 跑 / 联网 / 子任务"
 * 是五个一眼能分开的形状（2026-09-15 用户要求）。
 *
 * 颜色按**类别**给，不按工具给：十几个工具各一个色会变成彩虹，
 * 而"读蓝 / 写绿 / 跑琥珀"这种分法扫一眼就能归类（见 styles.css 的
 * `.tool__badge--*`）。配色本身不进这个文件 —— 它是展示层的事。
 */
export type ToolGlyph =
  /** 终端提示符：跑命令 */
  | 'terminal'
  /** 文稿：读文件 */
  | 'doc'
  /** 铅笔：改文件 */
  | 'pencil'
  /** 文稿 + 加号：新建文件 */
  | 'newfile'
  /** 星号：按模式找文件 */
  | 'asterisk'
  /** 放大镜：搜内容 */
  | 'search'
  /** 地球：联网 */
  | 'globe'
  /** 清单：任务 */
  | 'checklist'
  /** 小人：子代理 */
  | 'agent'
  /** 对话气泡：问用户 */
  | 'bubble'

export type ToolTone = 'read' | 'write' | 'run' | 'net' | 'task' | 'ask' | 'other'

export interface ToolBadge {
  glyph: ToolGlyph
  tone: ToolTone
}

/**
 * 工具名 → 徽标。**认不出的给 null**，卡片退回"首字母"那个老样子。
 *
 * 退回而不是硬塞一个通用图形：MCP 工具（`mcp__server__tool`）的名字本身
 * 就带着信息，给它一个"未知"图形反而把那点信息盖掉了。
 */
const TOOL_GLYPHS: Record<string, ToolGlyph> = {
  Bash: 'terminal',

  Read: 'doc',
  NotebookRead: 'doc',

  Write: 'newfile',

  Edit: 'pencil',
  MultiEdit: 'pencil',
  NotebookEdit: 'pencil',

  Glob: 'asterisk',
  Grep: 'search',

  WebFetch: 'globe',
  WebSearch: 'globe',

  Task: 'agent',

  TodoWrite: 'checklist',
  TaskCreate: 'checklist',
  TaskUpdate: 'checklist',
  TaskList: 'checklist',

  AskUserQuestion: 'bubble',
}

/** 图形 → 色调。一个图形只属于一个类别，所以色调从这里推，不另记一张表。 */
const GLYPH_TONES: Record<ToolGlyph, ToolTone> = {
  terminal: 'run',
  doc: 'read',
  asterisk: 'read',
  search: 'read',
  pencil: 'write',
  newfile: 'write',
  globe: 'net',
  checklist: 'task',
  agent: 'task',
  bubble: 'ask',
}

export function toolBadgeOf(name: string): ToolBadge | null {
  const glyph = TOOL_GLYPHS[name]
  return glyph ? { glyph, tone: GLYPH_TONES[glyph] } : null
}
