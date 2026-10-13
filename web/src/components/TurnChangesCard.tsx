import { memo, useState, type KeyboardEvent } from 'react'
import type { RevertResultWire } from '../types'
import { openFile, openTurnDiff, revertFiles } from '../bridge'
import { t, useLang } from '../i18n'
import { markOf, revertStateOf, type TurnSummary, type TurnFile } from '../turnChanges'

/**
 * 「本轮改动」卡（2026-10-13，设计稿 docs/design/turn-changes.html，用户选定形态）：
 *
 *  - **卡片乙**：默认收起成一行（图标 + 「本轮改动 · 3 个文件」 + `+A −D` + 展开），
 *    点开才列文件。收起态**不露回滚入口** —— 这是它的取舍，安静优先。
 *  - **选择甲**：展开后每行一个复选框；勾了底部升起操作条（已选 N 个 · 回滚所选）。
 *    卡头另有「全部回滚」。
 *  - **确认甲**：点回滚后**卡内翻成确认态**（与会话列表删除的行内确认同源）——
 *    逐行摆出后果（恢复 / 删除），焦点在「取消」上（回车不执行）。
 *
 * 这一层只画界面：清单聚合在 `turnChanges.ts`（纯函数），两条动作走 bridge。
 * 行上的「新 / 无存档」标记来自 Kotlin 推来的 `snapshots` op（按 toolUseId 对）。
 *
 * 点行的动作：**主点击 = 打开对比**（看改动是这张卡的主诉求），
 * 「在编辑器打开」是悬停浮出的次动作（同设计稿里「点行=对比」的取舍）。
 */
interface Props {
  summary: TurnSummary
  marks?: Record<string, boolean | null>
  revert?: Record<string, RevertResultWire>
}

function TurnChangesCardImpl({ summary, marks, revert }: Props) {
  // 语言切换要重画（文案全走目录）
  useLang()
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [confirming, setConfirming] = useState(false)

  if (summary.files.length === 0) return null

  const running = summary.running
  const count = summary.files.length
  const countText = count === 1 ? t('turn.fileCount', [count]) : t('turn.fileCounts', [count])

  const isReverted = (file: TurnFile): boolean => revertStateOf(file.path, revert)?.ok === true
  const revertible = summary.files.filter(
    (f) => markOf(f, marks) !== 'noSnapshot' && !isReverted(f),
  )

  const toggle = (path: string): void => {
    const next = new Set(selected)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    setSelected(next)
  }

  const beginConfirm = (paths: readonly string[]): void => {
    setSelected(new Set(paths))
    setConfirming(true)
  }

  const submitRevert = (): void => {
    // 回执由 Kotlin 推回来（revertReport op），这里只把请求送出去、把界面收回来
    revertFiles([...selected])
    setConfirming(false)
    setSelected(new Set())
  }

  const onConfirmKeyDown = (e: KeyboardEvent): void => {
    if (e.key === 'Escape') {
      e.stopPropagation()
      setConfirming(false)
    }
  }

  const head = (
    <div className="turn-changes__head">
      <span className="turn-changes__icon" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
          <path d="M3 3.5 h8" />
          <path d="M3 7 h5.5" />
          <path d="M3 10.5 h8" />
          <path d="M11.4 6.2 v2.6 M10.1 7.5 h2.6" strokeOpacity=".9" />
        </svg>
      </span>
      <span className="turn-changes__title">{t('turn.title')}</span>
      <span className="turn-changes__count">{countText}</span>
      <span className="turn-changes__delta">
        {summary.add > 0 && <span className="turn-add">+{summary.add}</span>}
        {summary.del > 0 && <span className="turn-del">−{summary.del}</span>}
      </span>
      <span className="turn-changes__spacer" />
      {open && !confirming && (
        <>
          <button
            type="button"
            className="turn-btn"
            data-testid="turn-review"
            onClick={() => {
              // 未回滚的那些，每个一个对比标签页（回滚过的不再开：开了也是空的）
              summary.files.filter((f) => !isReverted(f)).forEach((f) => openTurnDiff(f.path))
            }}
          >
            {t('turn.review')}
          </button>
          <button
            type="button"
            className="turn-btn"
            data-testid="turn-revert-all"
            disabled={running || revertible.length === 0}
            title={running ? t('turn.busy') : undefined}
            onClick={() => beginConfirm(revertible.map((f) => f.path))}
          >
            {t('turn.revertAll')}
          </button>
        </>
      )}
      {!confirming && (
        <button
          type="button"
          className="turn-btn turn-btn--ghost"
          data-testid="turn-expand"
          onClick={() => setOpen(!open)}
        >
          {open ? t('turn.collapse') : t('turn.expand')}
        </button>
      )}
    </div>
  )

  if (confirming) {
    return (
      <div className="turn-changes" data-testid="turn-changes" data-state="confirm" onKeyDown={onConfirmKeyDown}>
        <div className="turn-changes__head">
          <span className="turn-changes__title">
            {selected.size === 1
              ? t('turn.confirmOne')
              : t('turn.confirmMany', [selected.size])}
          </span>
          <span className="turn-changes__spacer" />
          <span className="turn-changes__count">{t('turn.confirmIrreversible')}</span>
        </div>
        <div className="turn-changes__body">
          {summary.files
            .filter((f) => selected.has(f.path))
            .map((f) => {
              const mark = markOf(f, marks)
              const { dir, name } = splitPath(f.path)
              return (
                <div className="turn-row" key={f.path} data-testid="turn-confirm-row">
                  <span className={`turn-mark ${mark === 'new' ? 'turn-mark--del' : 'turn-mark--rec'}`}>
                    {mark === 'new' ? t('turn.confirmDelete') : t('turn.confirmRestore')}
                  </span>
                  <span className="turn-row__path" title={f.path}>
                    {dir !== '' && <span className="turn-row__dir">{dir}</span>}
                    <span className="turn-row__name">{name}</span>
                  </span>
                  <span className="turn-row__why">
                    {mark === 'new' ? t('turn.confirmDeleteWhy') : t('turn.confirmRestoreWhy')}
                  </span>
                </div>
              )
            })}
          <p className="turn-changes__note">{t('turn.confirmNote')}</p>
          <div className="turn-bar">
            <span className="turn-changes__spacer" />
            <button type="button" className="turn-btn turn-btn--focus" autoFocus onClick={() => setConfirming(false)}>
              {t('turn.cancel')}
            </button>
            <button type="button" className="turn-btn turn-btn--danger" data-testid="turn-confirm-revert" onClick={submitRevert}>
              {t('turn.revert')}
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="turn-changes" data-testid="turn-changes" data-state={open ? 'open' : 'collapsed'}>
      {head}
      {open && (
        <div className="turn-changes__body">
          {summary.files.map((f) => {
            const mark = markOf(f, marks)
            const state = revertStateOf(f.path, revert)
            const reverted = state?.ok === true
            const failed = state !== undefined && !state.ok
            const { dir, name } = splitPath(f.path)
            const selectable = mark !== 'noSnapshot' && !reverted && !running
            return (
              <div
                className={`turn-row${reverted ? ' turn-row--done' : ''}`}
                key={f.path}
                data-testid="turn-row"
              >
                <input
                  type="checkbox"
                  className="turn-row__cb"
                  aria-label={t('turn.selectRow', [name])}
                  checked={selected.has(f.path)}
                  disabled={!selectable}
                  title={running ? t('turn.busy') : undefined}
                  onChange={() => toggle(f.path)}
                />
                <button
                  type="button"
                  className="turn-row__path turn-row__path--link"
                  title={t('turn.openDiff')}
                  onClick={() => openTurnDiff(f.path)}
                >
                  {dir !== '' && <span className="turn-row__dir">{dir}</span>}
                  <span className="turn-row__name">{name}</span>
                </button>
                {mark === 'new' && <span className="turn-tag turn-tag--new">{t('turn.tagNew')}</span>}
                {mark === 'noSnapshot' && <span className="turn-tag">{t('turn.tagNoSnapshot')}</span>}
                {reverted && <span className="turn-tag turn-tag--ok">{t('turn.tagReverted')}</span>}
                {reverted && state?.dirty === true && (
                  <span className="turn-tag">{t('turn.tagDirty')}</span>
                )}
                {failed && (
                  <span className="turn-row__err">{revertErrorText(state)}</span>
                )}
                <span className="turn-row__delta">
                  {f.add > 0 && <span className="turn-add">+{f.add}</span>}
                  {f.del > 0 && <span className="turn-del">−{f.del}</span>}
                </span>
                <button
                  type="button"
                  className="turn-row__open"
                  data-testid="turn-row-open"
                  title={t('turn.openFile')}
                  onClick={() => openFile(f.path)}
                >
                  ↗
                </button>
              </div>
            )
          })}
          {selected.size > 0 && (
            <div className="turn-bar">
              <span>{t('turn.selected', [selected.size])}</span>
              <span className="turn-changes__spacer" />
              <button type="button" className="turn-btn turn-btn--ghost" onClick={() => setSelected(new Set())}>
                {t('turn.clearSelection')}
              </button>
              <button
                type="button"
                className="turn-btn turn-btn--danger"
                data-testid="turn-revert-selected"
                disabled={running}
                title={running ? t('turn.busy') : undefined}
                // 翻到卡内确认态，**不是**直接提交 —— 确认这一步是设计的一部分
                // （「确认不是装饰」，同会话列表删除那条）
                onClick={() => setConfirming(true)}
              >
                {t('turn.revertSelected')}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * memo 的理由与 Item 同源（见 Transcript.tsx 里那条）：流式的每一帧 Transcript
 * 都会重渲染，而这三份 props 的引用在帧间是稳定的（summary 只依赖 items；
 * 两张表只在 op 到位时换引用）—— 不 memo 的话这一层每帧白跑一遍
 * （streamingCost.test.tsx 的流式预算是按整个转写区算的，别在这里漏）。
 */
export const TurnChangesCard = memo(TurnChangesCardImpl)

/**
 * 回执里的失败原因。
 *
 * `no-snapshot` / `busy` 是侧车给的**稳定的码**（翻成当前语言）；其余是系统
 * 错误的原文，**原样透传**（转它等于替系统撒谎 —— 同 Kotlin 侧那条规矩）。
 */
function revertErrorText(state: RevertResultWire): string {
  if (state.error === 'no-snapshot') return t('turn.errNoSnapshot')
  if (state.error === 'busy') return t('turn.errBusy')
  return t('turn.errOther', [state.error ?? ''])
}

/**
 * 路径拆成"目录 / 文件名"两段。
 *
 * 拆开是为了让目录**暗一档**、文件名亮着（VSCode 那种观感），也为了窄栏下
 * 省略号吃的是目录段而不是文件名（见 styles.css 里 `.turn-row__dir` 的说明）。
 */
function splitPath(path: string): { dir: string; name: string } {
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  if (i < 0) return { dir: '', name: path }
  return { dir: path.slice(0, i + 1), name: path.slice(i + 1) }
}
