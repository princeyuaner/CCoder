import { useEffect, useState } from 'react'
import type { ToolResultItem, ToolUseItem } from '../types'
import { openFile } from '../bridge'
import { baseName } from '../tools'
import { describeRun, runFiles } from '../grouping'
import { ToolCallBlock } from './ToolCallBlock'

/**
 * 一组连着的工具调用（设计稿 docs/design/tool-grouping.html 方案甲「归堆」）。
 *
 * 只做"怎么摆"：卡面一句摘要 + 涉及的文件，点开后里面还是原来的
 * [ToolCallBlock]，diff、输出、状态位一行都不改。
 *
 * 与单张卡片**同一条展开规矩**：默认展开（2026-09-14 按用户要求改的，
 * 原来是收着的），点了才收起；失败时自动弹开。
 *
 * 一屏会不会被铺满由**里面那些卡片**管：输出 14 行封顶、命令块也有高度上限，
 * 所以展开不等于"整屏都是内容"。真正的收纳手段是"组"本身：
 * 回放旧会话时一屏能扫过好几组。
 */

interface Props {
  uses: ToolUseItem[]
  /** 全体结果，按 toolUseId 配好（与单张卡片共用同一张表）。 */
  results: Map<string, ToolResultItem>
  /** 回合已结束仍没等到结果的 id —— 用来收尾。 */
  ended: Set<string>
}

export function ToolRunCard({ uses, results, ended }: Props) {
  const matched = uses.map((use) => ({ use, result: results.get(use.toolUseId) }))
  const failed = matched.filter((m) => m.result?.isError === true).length
  const waiting = matched.filter((m) => m.result === undefined)

  const done = matched.filter((m) => m.result !== undefined).length
  const running = waiting.length > 0 && waiting.some((m) => !ended.has(m.use.toolUseId))
  const aborted = waiting.length > 0 && !running

  const [open, setOpen] = useState(true)

  // 失败自动弹开 —— 与单张卡片逐字同一条规矩：失败正是要立刻看到的东西。
  // 只弹组卡，组内那一张失败的卡片自己会弹（ToolCallBlock 里同样的 effect），
  // 其余几张仍是收着的
  useEffect(() => {
    if (failed > 0) setOpen(true)
  }, [failed])

  const summary = describeRun(uses)
  const { shown, rest } = runFiles(uses)

  return (
    <div className={`run${failed > 0 ? ' run--error' : ''}`}>
      <button
        type="button"
        className="run__head"
        data-testid="run-head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`tool__chevron${open ? ' is-open' : ''}`}>▸</span>
        <span className="run__title">
          <span className="run__count">{uses.length}</span> 次工具调用
          {summary !== '' && <> · {summary}</>}
        </span>
        <span className="run__status">
          {running && (
            <>
              <span className="spin" data-testid="run-running" />
              <span className="run__step" data-testid="run-step">
                {done} / {uses.length}
              </span>
            </>
          )}
          {aborted && (
            <svg
              className="tool__abort"
              data-testid="run-aborted"
              viewBox="0 0 16 16"
              aria-hidden="true"
            >
              <title>没有等到结果（被中断或会话已结束）</title>
              <circle cx="8" cy="8" r="6.2" />
              <line x1="4.4" y1="4.4" x2="11.6" y2="11.6" />
            </svg>
          )}
          {!running && !aborted && failed === 0 && (
            <svg className="tool__check" data-testid="run-done" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M3.5 8.5l3 3 6.5-7.5" />
            </svg>
          )}
          {failed > 0 && <span className="tool__fail">含 {failed} 处失败</span>}
        </span>
      </button>

      {shown.length > 0 && (
        <div className="run__files">
          <span className="run__files-lbl">涉及</span>
          {shown.map((file) => (
            <button
              key={file.path}
              type="button"
              className="run__file"
              data-testid="run-file"
              title={file.path}
              onClick={() => openFile(file.path)}
            >
              {baseName(file.path)}
              {file.delta !== null && (file.delta.add > 0 || file.delta.del > 0) && (
                <span className="run__file-delta">
                  {file.delta.add > 0 && <span className="tool__add">+{file.delta.add}</span>}
                  {file.delta.del > 0 && <span className="tool__del">−{file.delta.del}</span>}
                </span>
              )}
            </button>
          ))}
          {rest > 0 && <span className="run__files-rest">+{rest}</span>}
        </div>
      )}

      {open && (
        <div className="run__body" data-testid="run-body">
          <div className="run__ind">
            {uses.map((use) => (
              <ToolCallBlock
                key={use.id}
                item={use}
                result={results.get(use.toolUseId)}
                turnEnded={ended.has(use.toolUseId)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
