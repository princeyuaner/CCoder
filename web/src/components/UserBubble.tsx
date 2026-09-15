import { useState } from 'react'
import { ImageLightbox } from './ImageLightbox'

/**
 * 用户发的一条。设计稿 `docs/design/image-attach.html` 方案甲。
 *
 * 图在**文字上方**（一排放不下就换行），点开是放大浮层。缩略图用 `object-fit: cover`
 * 裁成等大的 150×104：一排横竖不一的图会把气泡撑得参差不齐，而缩略图的职责是
 * "认出是哪张"，不是"看清每个像素"—— 点开那份才是完整的。
 *
 * 纯图消息（没打字）**不画空的那行文字** —— 一个零高度的 div 也会带出
 * 气泡的下内边距，看起来像底下缺了点什么。
 */
export function UserBubble({ text, images }: { text: string; images?: string[] }) {
  const [open, setOpen] = useState<number | null>(null)
  const pics = images ?? []

  return (
    <>
      <div className="row row--user" data-testid="user-bubble">
        <div className="bubble bubble--user">
          {pics.length > 0 && (
            <div className="bubble__images" data-testid="user-images">
              {pics.map((src, i) => (
                <button
                  key={i}
                  type="button"
                  className="bubble__image"
                  data-testid={`user-image-${i}`}
                  aria-label={`查看图片 ${i + 1}`}
                  onClick={() => setOpen(i)}
                >
                  <img src={src} alt={`图片 ${i + 1}`} />
                </button>
              ))}
            </div>
          )}
          {text !== '' && <div className="bubble__text">{text}</div>}
        </div>
      </div>
      {open !== null && (
        <ImageLightbox
          images={pics}
          index={open}
          onClose={() => setOpen(null)}
          onIndex={setOpen}
        />
      )}
    </>
  )
}
