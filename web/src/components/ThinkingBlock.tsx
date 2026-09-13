import { useElapsed } from '../elapsed'
import { Collapsible } from './Collapsible'

/** 完成的思考块。整块文字已经在手上了，收着，点开才看。 */
export function ThinkingBlock({ text }: { text: string }) {
  return (
    <Collapsible title="思考过程" dim>
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
 * 默认仍然收着，理由和当初一样：逐字铺开会让转写区持续刷屏，而思考的决策价值
 * 低于正文。区别只在于标题里多了一个转圈和一个秒表 —— 它回答的是"它真的在动，
 * 还是卡住了"，而不是"它在想什么"。想看内容，点开就是实时文字。
 */
export function LiveThinkingBlock({ text }: { text: string }) {
  const elapsed = useElapsed(true)

  return (
    <div data-testid="live-thinking">
      <Collapsible
        dim
        title={
          <>
            <span className="spin live-think__spin" data-testid="thinking-spin" />
            <span>思考中</span>
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
