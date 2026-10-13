import { t, useLang } from '../i18n'
import { cancelScheduled } from '../bridge'
import { splitRefs } from '../refs'
import type { ScheduledEntryWire } from '../types'

/**
 * 定时待发的一枚（2026-10-13，定时发送 · **丙案**，选型台见
 * `web/tools/scheduled-send-view.mjs`）。
 *
 * 它是"还没发出去的一条"：**虚线框 + 淡色**，跟右对齐的用户气泡同一个站位。
 * 到点由 Kotlin 那边撤掉它、再推一条真的 user 气泡进来 —— 虚线的换实心，
 * 位置几乎不动，看起来就是它自己"落了地"。
 *
 * `label`（"今天 18:00"）是 **Kotlin 拼好的成品**，这里原样渲染、不碰日期逻辑
 * （同 SystemNote 那条"不再翻一遍"的口径；换语言由 Kotlin 重推）。
 * ✕ 走桥（`cancelScheduled`），推回来的是整张表的新快照。
 *
 * 文字用 [splitRefs] 分记号，与 UserBubble 同一套 —— 定时的那条正文里
 * 可能带着 `⟦路径 12-18 · 7 行⟧`，画法不一致会一眼看出"这不是同一种气泡"。
 */
export function ScheduledBubble({ entry }: { entry: ScheduledEntryWire }) {
  useLang()

  return (
    <div className="row row--scheduled" data-testid="scheduled-bubble">
      <div className="bubble bubble--scheduled">
        <div className="bubble__sched-head">
          <svg
            className="bubble__sched-clock"
            width="11"
            height="11"
            viewBox="0 0 12 12"
            fill="none"
            aria-hidden="true"
          >
            <circle cx="6" cy="6" r="4.7" stroke="currentColor" strokeWidth="1.35" />
            <path
              d="M6 6 L6 3.4 M6 6 L8.1 7.2"
              stroke="currentColor"
              strokeWidth="1.35"
              strokeLinecap="round"
            />
          </svg>
          <span className="bubble__sched-label" data-testid="scheduled-label">
            {entry.label}
          </span>
          <span className="bubble__sched-pending">{t('scheduled.pending')}</span>
          <button
            type="button"
            className="bubble__sched-x"
            data-testid="scheduled-cancel"
            aria-label={t('scheduled.cancel')}
            title={t('scheduled.cancel')}
            onClick={() => cancelScheduled(entry.id)}
          >
            ✕
          </button>
        </div>
        {entry.text !== '' && (
          <div className="bubble__text">
            {splitRefs(entry.text).map((seg, i) =>
              seg.ref ? (
                <span className="ref" key={i}>
                  {seg.text}
                </span>
              ) : (
                seg.text
              ),
            )}
          </div>
        )}
        {entry.imageCount !== undefined && (
          // 带图的那条只挂一个记号：「图 N」（图跟着消息走了，屏幕上认得出就够
          // —— 与排队条那条 imageChip 同一个理由，见 QueueStrip.queueChipText）
          <span className="bubble__sched-chip" data-testid="scheduled-images">
            {entry.imageCount === 1 ? t('scheduled.imageOne') : t('scheduled.images', [entry.imageCount])}
          </span>
        )}
      </div>
    </div>
  )
}
