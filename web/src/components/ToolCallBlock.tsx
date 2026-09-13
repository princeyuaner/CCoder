import { useEffect, useState } from 'react'
import type { ToolResultItem, ToolUseItem } from '../types'
import { useElapsed } from '../elapsed'
import { toolStateOf } from '../toolStatus'
import { toolDelta, toolDiff, toolParams, toolTitle } from '../tools'

/**
 * 一次工具调用。设计稿见 docs/design/transcript-tools.html 方案乙。
 *
 * 折叠只有**一层**：卡片收着，点开后命令、diff、输出直接铺开。
 * 点开卡片本身就表示"我要看这次调用"，再让人点第二下是折腾。
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
}

function outputLines(text: string): string[] {
  if (text === '') return ['（无输出）']
  const lines = text.split('\n')
  // bash 的输出几乎总以换行结尾，末尾那个空行不算一行
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

export function ToolCallBlock({ item, result, turnEnded = false }: Props) {
  // 结果必须与这次调用配上号才用（见文件头）
  const matched = result && result.toolUseId === item.toolUseId ? result : undefined

  // 进行中 / 完成 / 失败 / 已中断（设计稿 tool-progress.html 方案丙）
  const state = toolStateOf(matched, turnEnded)

  // 只有"进行中"才走表：完成态的耗时在回放里算不准（见 elapsed.ts）
  const elapsed = useElapsed(state === 'running')

  // 失败的自动展开：失败正是要立刻看到的东西，让人点开去找等于没显示
  const [open, setOpen] = useState(matched?.isError === true)
  const [showAll, setShowAll] = useState(false)

  // live 路径下结果比调用晚到。失败的结果到点时把卡片弹开 ——
  // 这个 effect 只在"是否失败"变化时跑，用户手动收起后不会被它顶开
  useEffect(() => {
    if (matched?.isError) setOpen(true)
  }, [matched?.isError])

  const title = toolTitle(item.name, item.input)
  const delta = toolDelta(item.name, item.input)
  const diff = toolDiff(item.name, item.input)

  const all = matched ? outputLines(matched.text) : []
  const shown = showAll ? all : all.slice(0, OUTPUT_HEAD_LINES)
  const hidden = all.length - shown.length

  // Bash 的完整命令；认不出的工具没有命令也没有 diff，退回参数原文
  const command = item.name === 'Bash' ? title : ''
  const params = toolParams(item.input)

  return (
    <div className={`tool${matched?.isError ? ' tool--error' : ''}`}>
      <button
        type="button"
        className="tool__head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`tool__chevron${open ? ' is-open' : ''}`}>▸</span>
        <span className="tool__badge">{item.name.slice(0, 1).toUpperCase()}</span>
        <span className="tool__name">{item.name}</span>
        {title !== '' && <span className="tool__title">{title}</span>}
        {delta && (
          <span className="tool__delta" data-testid="tool-delta">
            {delta.add > 0 && <span className="tool__add">+{delta.add}</span>}
            {delta.del > 0 && <span className="tool__del">−{delta.del}</span>}
          </span>
        )}
        {matched?.isError && <span className="tool__fail">失败</span>}

        {/* 状态位：固定在最右，图形尺寸一致，切换时不让标题左右抖 */}
        <span className="tool__status">
          {state === 'running' && <span className="spin" data-testid="tool-running" />}
          {state === 'done' && (
            <svg className="tool__check" data-testid="tool-done" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M3.5 8.5l3 3 6.5-7.5" />
            </svg>
          )}
          {state === 'aborted' && (
            <svg
              className="tool__abort"
              data-testid="tool-aborted"
              viewBox="0 0 16 16"
              aria-hidden="true"
            >
              <title>没有等到结果（被中断或会话已结束）</title>
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
      </button>

      {open && (
        <div className="tool__body">
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
              展开全部 {all.length} 行
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
}

