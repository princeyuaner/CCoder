import { useState } from 'react'
import { t, useLang } from '../i18n'
import { splitRefs } from '../refs'
import { previewImage } from '../bridge'
import { ImageLightbox } from './ImageLightbox'

/**
 * 用户发的一条。设计稿 `docs/design/image-attach.html` 方案甲。
 *
 * 图在**文字上方**（一排放不下就换行），点开看大图。缩略图用 `object-fit: cover`
 * 裁成等大的 150×104：一排横竖不一的图会把气泡撑得参差不齐，而缩略图的职责是
 * "认出是哪张"，不是"看清每个像素"—— 点开那份才是完整的。
 *
 * 点开去哪（2026-10-14 改）：真机走**平台那只大看图框**（`previewImage` op，
 * 与输入框附件带点缩略图同一只）—— 页内浮层只盖得住转写区那块 JCEF 视口，
 * 图最大就是输出区那么宽，长截图上的小字看不清。桥不在时（`npm run dev`
 * 的浏览器、探针）退回 [ImageLightbox]，那是它的看家场合。
 *
 * 纯图消息（没打字）**不画空的那行文字** —— 一个零高度的 div 也会带出
 * 气泡的下内边距，看起来像底下缺了点什么。
 */
export function UserBubble({ text, images }: { text: string; images?: string[] }) {
  // 图的 alt 与按钮名走目录；正文（用户自己写的）一个字都不动
  useLang()
  const [open, setOpen] = useState<number | null>(null)
  const pics = images ?? []

  const openAt = (i: number) => {
    // 桥在 = 真机：平台那只框；桥不在 = 浏览器里跑探针 / dev：页内浮层兜底
    if (window.ccoder?.send) {
      previewImage(pics, i)
      return
    }
    setOpen(i)
  }

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
                  aria-label={t('image.view', [i + 1])}
                  onClick={() => openAt(i)}
                >
                  <img src={src} alt={t('image.alt', [i + 1])} />
                </button>
              ))}
            </div>
          )}
          {/* 参考记号（`⟦路径 12-18 · 7 行⟧`）画成和输入框里一样的底色。
              没有记号时这里就是一个纯字符串 —— 与从前渲染出来的 DOM 一字不差 */}
          {text !== '' && (
            <div className="bubble__text">
              {splitRefs(text).map((seg, i) =>
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
