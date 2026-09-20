import { useElapsed } from '../elapsed'
import { t, useLang } from '../i18n'
import { Collapsible } from './Collapsible'

/**
 * 完成的思考块。
 *
 * **默认展开**（2026-09-14 按用户要求改的，原来收着）。两个理由：
 * 用户要"流式输出"看得见；而且进行中那块是展开的，结束时若突然收起来，
 * 屏幕上会跳一下 —— 同一种块在两个阶段不该长得不一样。
 */
export function ThinkingBlock({ text }: { text: string }) {
  useLang()
  return (
    <Collapsible defaultOpen title={t('thinking.title')}>
      <div className="thinking-text">{text}</div>
    </Collapsible>
  )
}

/**
 * 进行中的思考。
 *
 * 为什么要有它：实测 207 个思考块里 **29% 跑过 5 秒以上**（P90 10.5s，最长 31.5s），
 * 而在此之前那段时间屏幕上**什么都不动** —— 用户的原话是"看起来感觉像卡死了"。
 *
 * **默认展开**（2026-09-14 按用户要求改的，原来收着）：思考的文字逐字流出来，
 * 一眼能看出"它在想什么"，而不只是"它在动"。标题里的转圈与秒表留着 ——
 * 收起之后，它们是唯一还在动的信号。
 */
export function LiveThinkingBlock({ text }: { text: string }) {
  const elapsed = useElapsed(true)
  // 语言变了要重画标题（正文不用 —— 那是模型说的话）
  useLang()

  return (
    <div data-testid="live-thinking">
      <Collapsible
        defaultOpen
        title={
          <>
            <span className="spin live-think__spin" data-testid="thinking-spin" />
            <span>{t('thinking.live')}</span>
            {elapsed !== null && (
              <span className="live-think__time" data-testid="thinking-elapsed">
                {elapsed}
              </span>
            )}
          </>
        }
      >
        <div className="thinking-text">{text}</div>
      </Collapsible>
    </div>
  )
}
