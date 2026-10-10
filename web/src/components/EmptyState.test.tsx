import { describe, expect, it } from 'vitest'
import { pasteKeyLabel } from './EmptyState'

/**
 * 粘贴键的写法 —— 界面上印的键位必须是那台机器上真能按的。
 *
 * 组件本身的行为在 Transcript.test.tsx 的「空会话的引导卡」那组用例里
 * （它经由 Transcript 渲染），这里只钉这条纯函数。
 */
describe('粘贴键的写法', () => {
  it('macOS 是 ⌘V', () => {
    expect(pasteKeyLabel('MacIntel')).toBe('⌘V')
  })

  it('其余平台是 Ctrl+V（jsdom 的空串也走这支 —— 兜底不能是 ⌘）', () => {
    expect(pasteKeyLabel('Win32')).toBe('Ctrl+V')
    expect(pasteKeyLabel('Linux x86_64')).toBe('Ctrl+V')
    expect(pasteKeyLabel('')).toBe('Ctrl+V')
  })
})
