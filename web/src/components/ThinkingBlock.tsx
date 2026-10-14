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
 * 一眼能看出"它在想什么"，而不只是"它在动"。
 *
 * 2026-10-14（用户要求）：标题里的**秒表换成一行思考内容** —— 那一行取当前
 * 思考文本的**最后一行**，模型新起一行就翻面；文本还空着（刚起手）时只有
 * 转圈 +「思考中」。秒数只回答"过了多久"，这一行回答"在想什么"；收起之后，
 * 它和转圈一起继续当"它在动 / 它在想什么"的信号（这个角色原先是秒表的）。
 *
 * 「思考折叠」勾上之后**这一块也跟着收起**（2026-09-21 用户定的：这块最容易占地方）。
 */
export function LiveThinkingBlock({ text }: { text: string }) {
  // 语言变了要重画标题（正文不用 —— 那是模型说的话）
  useLang()
  const collapsed = useCollapseThinking()
  const peek = lastThinkingLine(text)

  return (
    <div className="live-think" data-testid="live-thinking">
      <Collapsible
        defaultOpen={!collapsed}
        title={
          <>
            <span className="spin live-think__spin" data-testid="thinking-spin" />
            <span className="live-think__label">{t('thinking.live')}</span>
            {peek && (
              <span className="live-think__peek" data-testid="thinking-peek">
                {peek}
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

/**
 * 标题里那一行：思考文本的**最后一个非空行**，行内空白归一成一格。
 *
 * 空文本 / 只有空白（刚起手）返回 `''` —— 标题就只摆「思考中」，不摆一行空的。
 * **不做长度裁剪**：装不装得下是宽度的问题，交给 CSS 的 ellipsis（面板多宽它
 * 自己知道；按字数砍会在窄面板上砍不干净、在宽面板上白砍）。
 */
export function lastThinkingLine(text: string): string {
  const lines = text.split('\n')
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].replace(/\s+/g, ' ').trim()
    if (line) return line
  }
  return ''
}
