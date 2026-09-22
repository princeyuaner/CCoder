import { memo, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import type { ToolResultItem, ToolUseItem } from '../types'
import { openFile } from '../bridge'
import { useElapsed } from '../elapsed'
import { t, useLang } from '../i18n'
import { toolStateOf } from '../toolStatus'
import {
  toolBadgeOf,
  toolCommand,
  toolDelta,
  toolDiff,
  toolFile,
  toolParams,
  toolTitle,
  type ToolGlyph,
} from '../tools'

/**
 * 一次工具调用。设计稿见 docs/design/transcript-tools.html 方案乙。
 *
 * 折叠只有**一层**：卡片收着，点开后命令、diff、输出直接铺开。
 * 默认收着 —— 2026-09-14 一度改为默认展开，当天又按用户要求收回：
 * 「显示描述和操作的对应文件即可」。**失败也收着**（2026-09-15 按用户要求
 * 去掉自动弹开）：卡面上一个红 ✗ 就够，要看输出自己点开。
 *
 * 卡面只留一行：Bash 给摘要（Claude 的 description），文件类工具给文件名 ——
 * 名字**可点**，点了在编辑器里打开；真正的命令原文、diff、输出都在展开体里。
 *
 * 结果（[ToolResultItem]）是**另一条消息**，由 [Transcript] 按 toolUseId
 * 配好传进来；配不上就当没有 —— 挂错卡片比不显示更糟。
 */

/** 输出先铺多少行，超出部分折在一个按钮后面。 */
const OUTPUT_HEAD_LINES = 14

interface Props {
  item: ToolUseItem
  result?: ToolResultItem
  /** 回合已结束（它之后再出现过 result 事件）—— 用来把等不到结果的卡片收尾。 */
  turnEnded?: boolean
  /**
   * 子代理那一块（A1）。由 [Transcript] 事先建好传下来 —— 卡片自己不分组、
   * 也不认识 `nestByParent`，它只管把这块画在该在的位置。
   */
  nested?: ReactNode
}

/**
 * 徽标里那个 12px 的图形。**用 currentColor 画** —— 色调只由外层那条
 * `.tool__badge--<类别>` 给，这个文件里一个色号都不写。
 *
 * 笔画粗细与右上角那两个自绘按钮同一档（1.5），尺寸与四个状态位（✓ ✗ ⊘ 转圈）
 * 一致：一排卡片扫过去，粗细细看不能有第二种。
 */
function BadgeGlyph({ glyph }: { glyph: ToolGlyph }) {
  const svg = (children: ReactNode) => (
    <svg
      className="tool__glyph"
      data-testid={`tool-icon-${glyph}`}
      viewBox="0 0 12 12"
      aria-hidden="true"
    >
      {children}
    </svg>
  )

  switch (glyph) {
    // 终端提示符 ❯_：跑命令
    case 'terminal':
      return svg(
        <>
          <path d="M2.6 3.2 L5.4 5.6 L2.6 8" />
          <path d="M6.4 8.4 h3.2" />
        </>,
      )
    // 一页稿纸：读文件
    case 'doc':
      return svg(
        <>
          <path d="M3.4 2.2 h3.2 l2 2 v5.6 h-5.2 z" />
          <path d="M5.2 6.4 h2.4" />
          <path d="M5.2 8.2 h2.4" />
        </>,
      )
    // 铅笔：改文件
    case 'pencil':
      return svg(
        <>
          <path d="M2.8 9.2 l.5-2.1 4-4 a1.2 1.2 0 0 1 1.7 1.7 l-4 4 z" />
          <path d="M6.6 3.7 l1.7 1.7" />
        </>,
      )
    // 稿纸 + 加号：新建文件
    case 'newfile':
      return svg(
        <>
          <path d="M3.4 2.2 h3 l1.8 1.8 v3.4" />
          <path d="M3.4 2.2 v7.6 h2.4" />
          <path d="M7.4 8.4 h3.2" />
          <path d="M9 6.8 v3.2" />
        </>,
      )
    // 一个星号：按模式找文件
    case 'asterisk':
      return svg(
        <>
          <path d="M6 2.4 v6.4" />
          <path d="M3.2 4 l5.6 3.2" />
          <path d="M8.8 4 l-5.6 3.2" />
        </>,
      )
    // 放大镜：搜内容
    case 'search':
      return svg(
        <>
          <circle cx="5.2" cy="5.2" r="2.6" />
          <path d="M7.2 7.2 l2.2 2.2" />
        </>,
      )
    // 地球：联网
    case 'globe':
      return svg(
        <>
          <circle cx="6" cy="6" r="3.6" />
          <ellipse cx="6" cy="6" rx="1.6" ry="3.6" />
          <path d="M2.4 6 h7.2" />
        </>,
      )
    // 清单：任务
    case 'checklist':
      return svg(
        <>
          <rect x="2.4" y="2.8" width="7.2" height="6.4" rx="1.4" />
          <path d="M4.4 6.1 l1.3 1.3 2.1-2.4" />
        </>,
      )
    // 两个小人：子代理
    case 'agent':
      return svg(
        <>
          <circle cx="4.4" cy="4.2" r="1.5" />
          <path d="M2.2 9.4 a2.6 2.6 0 0 1 4.4 0" />
          <circle cx="8.5" cy="5" r="1.2" />
          <path d="M7.5 9.4 a2.1 2.1 0 0 1 2.9 0" />
        </>,
      )
    // 对话气泡：问用户
    case 'bubble':
      return svg(
        <>
          <rect x="2.2" y="2.6" width="7.6" height="5.4" rx="1.8" />
          <path d="M4.6 8 v1.9 l1.7-1.9" />
        </>,
      )
  }
}

function outputLines(text: string): string[] {
  // 结果到了、里面一个字符都没有 —— 这句说的是「确实没吐东西」，
  // 与「还在跑」（那时压根不画输出区）是两回事
  if (text === '') return [t('tool.noOutput')]
  const lines = text.split('\n')
  // bash 的输出几乎总以换行结尾，末尾那个空行不算一行
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

/**
 * **必须 memo**：流式输出期间每一帧都会重渲染整段转写，不 memo 的话每来一个字，
 * 屏幕上每一张卡片都要重跑一遍下面的派生计算并重建整棵 JSX —— 2026-09-14 实测
 * 160 项时每帧 48ms（约 20fps 封顶，且还没算浏览器布局），这就是"输出窗口卡"。
 *
 * memo 生效的前提是 props 引用稳定：`item` 是 state 里的对象（不重建）、
 * `result` 由 Transcript 精确到这一条取出来（不是整张 map，否则别人收到结果也会
 * 让这里失效）、`turnEnded` 是布尔值。
 */
export const ToolCallBlock = memo(function ToolCallBlock({
  item,
  result,
  turnEnded = false,
  nested,
}: Props) {
  // 语言：卡面上有四段文案走目录（无输出 / 失败 / 已中断 / 展开全部），
  // 下面两张 memo 里也各有它 —— 换语言时这些派生文本必须重算
  const lang = useLang()

  // 结果必须与这次调用配上号才用（见文件头）
  const matched = result && result.toolUseId === item.toolUseId ? result : undefined

  // 进行中 / 完成 / 失败 / 已中断（设计稿 tool-progress.html 方案丙）
  const state = toolStateOf(matched, turnEnded)

  // 只有"进行中"才走表：完成态的耗时在回放里算不准（见 elapsed.ts）
  const elapsed = useElapsed(state === 'running')

  // 一律默认收着，**失败也不弹开**（2026-09-15 按用户要求改的，原先是失败自动
  // 展开）：失败要说的只是"这次没成"，卡面那个红 ✗ 已经说完了；输出是细节，
  // 想看的人自己点开。
  //
  // **子代理那块也收着**（2026-09-18 按用户要求改的，A1 当天撤销的那次破例）。
  // 破例当初的理由是：A0 那些子代理工具卡平铺在主流水里、看得见，A1 把它们收进
  // 卡片后若还收着就等于"改完看不见了"。装上一看不是那么回事 —— 子代理的正文能有
  // 几百字，展开后整块插在主线程叙述**中间**，把正在读的正文顶开。用户的原话是
  // "它在文本中间输出，很难看"。而它其实没有被藏起来：卡头写着 Task 与那句描述，
  // 点一下就在。与"失败不弹开"同一个取舍 —— 默认收着，想看的人自己点开。
  const [open, setOpen] = useState(false)
  const [showAll, setShowAll] = useState(false)

  // 从 item.input 派生的一切：只随参数变。六个函数各自 JSON.parse 一遍参数，
  // Write/Edit 还要按行切出 diff —— 卡片因任何原因重渲染（结果到达、收起展开）
  // 都重算一遍是纯浪费
  const { badge, title, delta, diff, file, command } = useMemo(
    () => ({
      // 认不出的工具给 null —— 卡面退回工具名首字母（见 tools.ts 的 toolBadgeOf）
      badge: toolBadgeOf(item.name),
      title: toolTitle(item.name, item.input),
      delta: toolDelta(item.name, item.input),
      diff: toolDiff(item.name, item.input),
      // 这次调用指向的文件。有它，卡面那个文件名就是可点的
      file: toolFile(item.name, item.input),
      // Bash 的完整命令 —— 卡面只做摘要，详情要的是"真正跑了什么"
      command: toolCommand(item.name, item.input),
    }),
    [item.name, item.input],
  )

  // 认不出的工具既没有命令也没有 diff，才轮到"参数原文"这条兜底
  const needsParams = command === '' && diff === null && matched === undefined
  // **惰性**：参数原文是缩进过的整份 JSON —— Write 的参数里装的就是整个文件内容，
  // 无条件算一遍等于每次渲染都把整份文件美化成一大段字符串，然后几乎总是丢掉
  //
  // `lang` 在依赖里不是多余的：toolParams 内部有一句走目录的分隔头
  // （`其余参数：`），不跟着语言重算就会一直显示上一次那种语言
  const params = useMemo(
    () => (needsParams ? toolParams(item.input) : null),
    [needsParams, item.input, lang],
  )

  // 输出切行同理：整段输出按行切开只该在结果变化时做一次。
  // 空输出的那句「（无输出）」同样是目录里的文案，所以也依赖 lang
  const all = useMemo(() => (matched ? outputLines(matched.text) : []), [matched, lang])
  const shown = showAll ? all : all.slice(0, OUTPUT_HEAD_LINES)
  const hidden = all.length - shown.length

  /**
   * 展开体里到底有没有东西可画。
   *
   * 起头帧那张卡（参数还在生成，见 Kotlin 侧的 `startedToolCard`）**什么都没有** ——
   * 点开它只该什么都不发生，不该弹出一个空盒子。参数到了、结果到了，这里就变成 true。
   *
   * `nested` 必须在列：刚出生的 `Task` 卡参数是空的，而**子代理那一块正是它唯一的
   * 内容**（它排在展开体最前，见下面）。
   */
  const hasBody =
    nested !== undefined ||
    command !== '' ||
    diff !== null ||
    matched !== undefined ||
    params !== null

  const toggle = () => setOpen((v) => !v)

  /**
   * 卡片头的键盘语义。div 化以后浏览器不再替我们做这件事。
   *
   * 焦点在文件名按钮上时**直接让路**：它的 Enter/Space 由浏览器变成 click，
   * 这里再拦一次就是按一下空格既打开文件又开合卡片。
   */
  const onHeadKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault() // 空格不拦会滚页面
      toggle()
    }
  }

  return (
    <div className={`tool${matched?.isError ? ' tool--error' : ''}`}>
      {/* 卡片头是 div[role=button] 而不是 <button>：卡面上的文件名要可点，
          而按钮里套按钮是坏结构。代价是键盘与焦点环得自己补
          （onHeadKeyDown 见上，焦点环见 styles.css 里的 :focus-visible） */}
      <div
        className="tool__head"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={toggle}
        onKeyDown={onHeadKeyDown}
      >
        <span className={`tool__chevron${open ? ' is-open' : ''}`}>▸</span>
        <span
          className={`tool__badge${badge ? ` tool__badge--${badge.tone}` : ''}`}
          data-testid="tool-badge"
        >
          {badge ? <BadgeGlyph glyph={badge.glyph} /> : item.name.slice(0, 1).toUpperCase()}
        </span>
        <span className="tool__name">{item.name}</span>
        {title !== '' &&
          (file ? (
            /* 这里**不能**加 aria-expanded：卡片头是既有用例定位"开合手柄"的锚
               （getByRole('button', { expanded: false })），多一个带该属性的按钮
               会让那一批用例报"找到多个元素"，而错误信息完全指不到根因 */
            <button
              type="button"
              className="tool__title tool__file"
              data-testid="tool-file"
              title={file.path}
              onClick={(e) => {
                // 只打开文件，别顺手把卡片也开合了
                e.stopPropagation()
                openFile(file.path, file.line)
              }}
            >
              {title}
            </button>
          ) : (
            // 摘要悬停时给完整命令 —— 卡面省下来的那截信息不该真的消失
            <span className="tool__title" title={command !== '' ? command : undefined}>
              {title}
            </span>
          ))}
        {delta && (
          <span className="tool__delta" data-testid="tool-delta">
            {delta.add > 0 && <span className="tool__add">+{delta.add}</span>}
            {delta.del > 0 && <span className="tool__del">−{delta.del}</span>}
          </span>
        )}
        {/* 状态位：固定在最右，图形尺寸一致，切换时不让标题左右抖 */}
        <span className="tool__status">
          {state === 'running' && <span className="spin" data-testid="tool-running" />}
          {state === 'done' && (
            <svg className="tool__check" data-testid="tool-done" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M3.5 8.5l3 3 6.5-7.5" />
            </svg>
          )}
          {/* ✗ 不设 aria-hidden：它替换掉的是原先那截能读出来的「失败」文字，
              盖上就成了纯装饰。⊘ 那边是既有问题，不在这里顺手改 */}
          {state === 'failed' && (
            <svg
              className="tool__x"
              data-testid="tool-failed"
              viewBox="0 0 16 16"
              role="img"
              aria-label={t('tool.failed')}
            >
              <title>{t('tool.failed')}</title>
              <line x1="4.6" y1="4.6" x2="11.4" y2="11.4" />
              <line x1="11.4" y1="4.6" x2="4.6" y2="11.4" />
            </svg>
          )}
          {state === 'aborted' && (
            <svg
              className="tool__abort"
              data-testid="tool-aborted"
              viewBox="0 0 16 16"
              aria-hidden="true"
            >
              <title>{t('tool.aborted')}</title>
              <circle cx="8" cy="8" r="6.2" />
              <line x1="4.4" y1="4.4" x2="11.6" y2="11.6" />
            </svg>
          )}
          {elapsed !== null && (
            <span className="tool__time" data-testid="tool-elapsed">
              {elapsed}
            </span>
          )}
        </span>
      </div>

      {open && hasBody && (
        <div className="tool__body">
          {/* 子代理那一块**排在最前**：它是这块卡片的"过程"，而下面那些
              （命令 / diff / 输出）是这张卡片自己的参数与结果 —— Task 卡通常没有前者 */}
          {nested}

          {command !== '' && (
            <div className="tool__cmd" data-testid="tool-command">
              <span className="tool__prompt">$ </span>
              {command}
            </div>
          )}

          {diff && (
            <div className="tool__diff" data-testid="tool-diff">
              {diff.map((line, i) => (
                <div key={i} className={`tool__line tool__line--${line.kind}`}>
                  {/* 标记单独一个元素：测试要按内容精确取到那一行 */}
                  <span className="tool__sign">{line.kind === 'add' ? '+' : '−'}</span>
                  <span>{line.text}</span>
                </div>
              ))}
            </div>
          )}

          {matched && (
            <pre className="tool__out" data-testid="tool-output">
              {shown.join('\n')}
            </pre>
          )}

          {hidden > 0 && (
            <button type="button" className="tool__more" onClick={() => setShowAll(true)}>
              {t('tool.showAllLines', [all.length])}
            </button>
          )}

          {!command && !diff && !matched && params !== null && (
            <pre className="tool__params" data-testid="tool-params">
              {params}
            </pre>
          )}
        </div>
      )}
    </div>
  )
})

