import type { TranscriptImage } from '../types'

interface Props {
  text: string
  images?: TranscriptImage[]
  omittedImages?: number
}

export function UserBubble({ text, images, omittedImages }: Props) {
  return (
    <div className="row row--user" data-testid="user-bubble">
      <div className="bubble bubble--user">
        {images?.map((img, i) => (
          <img
            key={i}
            className="bubble__image"
            data-testid={`user-image-${i}`}
            src={`data:${img.mediaType};base64,${img.base64}`}
            alt="粘贴的图片"
          />
        ))}
        {/* 纯图消息是合法的：文字为空串时不画这个块，否则气泡底部会多一块空白 */}
        {text !== '' && (
          <div className="bubble__text" data-testid="user-bubble-text">
            {text}
          </div>
        )}
        {omittedImages !== undefined && omittedImages > 0 && (
          <div className="bubble__omitted">{omittedImages} 张图已省略</div>
        )}
      </div>
    </div>
  )
}
