import { useEffect } from 'react'

/**
 * 点开看图：一层压暗的浮层，Esc 关掉，左右键（或 1/2/3）翻页。
 *
 * 为什么不"在编辑器里打开这张图"：那要把它落成一个临时文件，还得管清理
 * —— 而此刻的问题是"这是不是那个按钮"，看一眼就够（设计稿 §二）。
 *
 * 浮层用 `position: fixed`，所以它可以直接住在气泡里：DOM 位置不影响它盖住
 * 整个面板，也就不必为它引一套 portal。
 */
export function ImageLightbox({
  images,
  index,
  onClose,
  onIndex,
}: {
  images: string[]
  index: number
  onClose: () => void
  onIndex: (next: number) => void
}) {
  const count = images.length

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
        return
      }
      if (e.key === 'ArrowRight' && index < count - 1) onIndex(index + 1)
      if (e.key === 'ArrowLeft' && index > 0) onIndex(index - 1)
      // 数字键直跳：图多的时候（四张）比按左右键快
      const n = Number(e.key)
      if (Number.isInteger(n) && n >= 1 && n <= count && n - 1 !== index) onIndex(n - 1)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [index, count, onClose, onIndex])

  return (
    <div
      className="lightbox"
      data-testid="lightbox"
      role="dialog"
      aria-modal="true"
      aria-label="图片"
      onClick={onClose}
    >
      <img
        className="lightbox__img"
        data-testid="lightbox-image"
        src={images[index]}
        alt={`图片 ${index + 1}`}
        // 点图本身不该关掉 —— 想细看的人会点在图上
        onClick={(e) => e.stopPropagation()}
      />
      <div className="lightbox__hint" data-testid="lightbox-hint">
        {count > 1 ? `${index + 1} / ${count} · ` : ''}Esc 关闭
      </div>
    </div>
  )
}
