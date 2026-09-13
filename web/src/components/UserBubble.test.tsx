import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { UserBubble } from './UserBubble'

/**
 * 用户气泡里的图。
 *
 * 每条断言各钉一件事：图真的画得出来（data URI 拼对了）、两张图不会串成一张、
 * 纯图消息不留一个空文字块、省掉的张数必须说出来 —— 最后一条是 spec §7.1 的
 * "丢失必须可见"：省了 3 张却什么都不说，用户会以为自己只发了 1 张。
 */

describe('UserBubble 的图片', () => {
  it('渲染成 data URI 的 img', () => {
    render(<UserBubble text="看这个" images={[{ mediaType: 'image/png', base64: 'AAA' }]} />)
    const img = screen.getByTestId('user-image-0')
    expect(img).toHaveAttribute('src', 'data:image/png;base64,AAA')
  })

  it('多张图各占一个 img', () => {
    // 只有一张时，"每张都渲染成第一张"这类索引 bug 看不出来
    render(
      <UserBubble
        text="两张"
        images={[
          { mediaType: 'image/png', base64: 'AAA' },
          { mediaType: 'image/jpeg', base64: 'BBB' },
        ]}
      />,
    )
    expect(screen.getByTestId('user-image-0')).toHaveAttribute('src', 'data:image/png;base64,AAA')
    expect(screen.getByTestId('user-image-1')).toHaveAttribute('src', 'data:image/jpeg;base64,BBB')
  })

  it('只有图没有文字时不渲染空文字块', () => {
    render(<UserBubble text="" images={[{ mediaType: 'image/png', base64: 'AAA' }]} />)
    expect(screen.queryByTestId('user-bubble-text')).not.toBeInTheDocument()
    // 文字块没了，图得还在 —— 否则"纯图消息渲染成空气"也能绿
    expect(screen.getByTestId('user-image-0')).toBeInTheDocument()
  })

  it('被省掉的张数要说出来，不能装作没有', () => {
    render(<UserBubble text="看" images={[]} omittedImages={3} />)
    expect(screen.getByText('3 张图已省略')).toBeInTheDocument()
  })

  it('没有省略时不出声', () => {
    // 0 与 undefined 都不该画出"0 张图已省略"这种噪音
    render(<UserBubble text="看" images={[]} omittedImages={0} />)
    expect(screen.queryByText(/张图已省略/)).not.toBeInTheDocument()
  })
})
