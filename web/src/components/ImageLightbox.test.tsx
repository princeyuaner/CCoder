import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ImageLightbox } from './ImageLightbox'

const IMGS = [
  'data:image/jpeg;base64,AAA',
  'data:image/jpeg;base64,BBB',
  'data:image/jpeg;base64,CCC',
]

function open(index = 0) {
  const onClose = vi.fn()
  const onIndex = vi.fn()
  render(<ImageLightbox images={IMGS} index={index} onClose={onClose} onIndex={onIndex} />)
  return { onClose, onIndex }
}

describe('放大浮层', () => {
  it('画出当前那张', () => {
    open(1)
    expect(screen.getByTestId('lightbox-image')).toHaveAttribute('src', IMGS[1])
  })

  it('Esc 关掉', async () => {
    const { onClose } = open()

    await userEvent.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalled()
  })

  it('左右键翻页', async () => {
    const { onIndex } = open(1)

    await userEvent.keyboard('{ArrowRight}')
    expect(onIndex).toHaveBeenCalledWith(2)

    await userEvent.keyboard('{ArrowLeft}')
    expect(onIndex).toHaveBeenCalledWith(0)
  })

  it('数字键直跳 —— 四张图的时候比按左右键快', async () => {
    const { onIndex } = open(0)

    await userEvent.keyboard('3')

    expect(onIndex).toHaveBeenCalledWith(2)
  })

  it('到头了不越界', async () => {
    const { onIndex } = open(2)

    await userEvent.keyboard('{ArrowRight}')

    expect(onIndex).not.toHaveBeenCalled()
  })

  it('点空白关掉，点图本身不关 —— 想细看的人正是点在图上', async () => {
    const { onClose } = open()

    await userEvent.click(screen.getByTestId('lightbox-image'))
    expect(onClose).not.toHaveBeenCalled()

    await userEvent.click(screen.getByTestId('lightbox'))
    expect(onClose).toHaveBeenCalled()
  })

  it('多图时写清第几张 / 共几张', () => {
    open(1)

    expect(screen.getByTestId('lightbox-hint')).toHaveTextContent('2 / 3')
  })

  it('只有一张时不写计数 —— 「1 / 1」是句废话', () => {
    render(<ImageLightbox images={[IMGS[0]]} index={0} onClose={() => {}} onIndex={() => {}} />)

    expect(screen.getByTestId('lightbox-hint')).not.toHaveTextContent('1 / 1')
    expect(screen.getByTestId('lightbox-hint')).toHaveTextContent('Esc 关闭')
  })
})
