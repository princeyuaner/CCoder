import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserBubble } from './UserBubble'

/**
 * 用户气泡里的图（贴图）。设计稿 `docs/design/image-attach.html` 方案甲：
 * 图在文字**上方**、点开是放大浮层。
 */
const PNG = 'data:image/jpeg;base64,AAAA'
const PNG2 = 'data:image/jpeg;base64,BBBB'

describe('用户气泡的图', () => {
  it('纯文字：整排图不画出来', () => {
    render(<UserBubble text="只有字" />)

    expect(screen.queryByTestId('user-images')).not.toBeInTheDocument()
    expect(screen.getByText('只有字')).toBeInTheDocument()
  })

  it('带图：图在文字上方', () => {
    render(<UserBubble text="看这两张" images={[PNG, PNG2]} />)

    const images = screen.getByTestId('user-images')
    const text = screen.getByText('看这两张')
    // compareDocumentPosition：图那一排必须排在文字**前面**
    expect(images.compareDocumentPosition(text) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByRole('img')).toHaveLength(2)
  })

  it('纯图（没打字）不画空的那行文字', () => {
    // 空 div 也会带出气泡的下内边距，看起来像底下缺了一块
    render(<UserBubble text="" images={[PNG]} />)

    expect(screen.getByTestId('user-images')).toBeInTheDocument()
    expect(document.querySelector('.bubble__text')).toBeNull()
  })

  it('点缩略图打开浮层，Esc 关掉', async () => {
    render(<UserBubble text="看这张" images={[PNG, PNG2]} />)

    await userEvent.click(screen.getByTestId('user-image-1'))
    expect(screen.getByTestId('lightbox-image')).toHaveAttribute('src', PNG2)

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByTestId('lightbox')).not.toBeInTheDocument()
  })

  it('缩略图是按钮，键盘也够得着 —— 鼠标点不开的东西键盘也点不开', async () => {
    render(<UserBubble text="看这张" images={[PNG]} />)

    await userEvent.tab()
    expect(screen.getByTestId('user-image-0')).toHaveFocus()

    await userEvent.keyboard('{Enter}')
    expect(screen.getByTestId('lightbox')).toBeInTheDocument()
  })
})

/**
 * 右键加进来的片段，在输入框里是一行记号（`⟦路径 24-27 · 4 行⟧`）。
 * 转写区里**画的是同一份** —— 而不是展开后的整段代码（2026-09-15 用户提：
 * "发送到输出区后显示的格式应该和输入框中的一样"）。
 */
describe('用户气泡里的参考记号', () => {
  const T = '⟦src/a/X.kt 24-27 · 4 行⟧'

  it('记号画成一块底色，前后的文字一个不少、顺序不变', () => {
    const { container } = render(<UserBubble text={`看这里 ${T} 为什么`} />)

    const chip = container.querySelector('.bubble__text .ref')
    expect(chip).toHaveTextContent(T)
    expect(container.querySelector('.bubble__text')?.textContent).toBe(`看这里 ${T} 为什么`)
  })

  it('没有记号时不画那个 span —— DOM 与从前一模一样', () => {
    const { container } = render(<UserBubble text="普通的一句话" />)

    expect(container.querySelector('.bubble__text .ref')).toBeNull()
  })
})
