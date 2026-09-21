import { useElapsed } from '../elapsed'
import { t, useLang } from '../i18n'
import { useCollapseThinking } from '../prefs'
import { Collapsible } from './Collapsible'

/**
 * 完成的思考块。
 *
 * **默认展开**（2026-09-14 按用户要求改的，原来收着）。两个理由：
 * 用户要"流式输出"看得见；而且进行中那块是展开的，结束时若突然收起来，
 * 屏幕上会跳一下 —— 同一种块在两个阶段不该长得不一样。
 *
 * 2026-09-21 起这是**默认档**，不是唯一形态：设置里勾上「思考折叠」
 * （`UiPreferences.collapseThinking`）后这一块默认收起。默认值没动 ——
 * 不勾就是上面那个原样。
 */
export function ThinkingBlock({ text }: { text: string }) {
  useLang()
  // 设置里的「思考折叠」（默认关）。开关一改，这里跟着重画 —— 复位那一下在 Collapsible 里
  const collapsed = useCollapseThinking()
  return (
    <Collapsible defaultOpen={!collapsed} title={t('thinking.title')}>
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
 *
 * 「思考折叠」勾上之后**这一块也跟着收起**（2026-09-21 用户定的：这块最容易占地方）。
 * 而"它在想"由标题里那两样继续说 —— 那正是当初留下它们的原因。
 */
export function LiveThinkingBlock({ text }: { text: string }) {
  const elapsed = useElapsed(true)
  // 语言变了要重画标题（正文不用 —— 那是模型说的话）
  useLang()
  const collapsed = useCollapseThinking()

  return (
    <div data-testid="live-thinking">
      <Collapsible
        defaultOpen={!collapsed}
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
