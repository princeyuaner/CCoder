import { t, useLang } from '../i18n'

/**
 * 粘贴键的写法：macOS 是 ⌘V，其余平台 Ctrl+V。
 *
 * 平台从 `navigator.platform` 现读 —— JCEF 里报的就是本机平台。写死一个
 * "Ctrl+V" 在 Mac 上就是撒谎，与 `Composer.kt` 里"发送说明跟着设置走"
 * 是同一条账：界面上印的键位，就得是那台机器上真能按的。
 *
 * 键名是**数据**不是文案（同 strings.ts 文件头那条约定：命令、工具名这类
 * 不翻的东西不进目录），所以这里不经过 `t()`。
 */
export function pasteKeyLabel(platform: string): string {
  return /^mac/i.test(platform) ? '⌘V' : 'Ctrl+V'
}

/**
 * 空会话的引导卡（设计稿 `docs/design/empty-session.html` 方案乙，2026-10-10）。
 *
 * 只在**没有任何内容项的新会话**里出现 —— 判据在 `Transcript.tsx`（`fresh`
 * 标记 + 无内容项），来了第一条消息就连同整个空态一起消失。
 *
 * 三条提示说的是三件**光看输入框看不出来**的事：符号引用会随消息发出、
 * 斜杠命令能唤起什么、截图可以粘贴。发送/换行的键位**不在这里重复** ——
 * 输入框那行占位符里就写着它，而且它跟着设置里的发送约定走（见 Composer.kt），
 * 卡上再印一份迟早会说岔。
 */
export function EmptyState() {
  // 三条提示都是文案，语言换了要跟着重画
  useLang()
  return (
    <div className="empty" data-testid="empty-state">
      <div className="empty__card">
        <div className="empty__head">
          {/* 品牌标记：形状是侧边栏图标那份（大 C 套小 C），颜色走 --brand
              （主题注入的两档橙色，见 ThemeInjector） */}
          <svg className="empty__mark" viewBox="0 0 40 40" aria-hidden="true">
            <path
              d="M29.63 10.70 A13.38 13.38 0 1 0 29.63 29.30"
              fill="none"
              stroke="currentColor"
              strokeWidth="6.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <path
              d="M24.21 15.94 A5.85 5.85 0 1 0 24.21 24.06"
              fill="none"
              stroke="currentColor"
              strokeWidth="4.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <span>{t('transcript.empty.title')}</span>
        </div>
        <div className="empty__row">
          <span className="empty__key">@</span>
          <span className="empty__key">#</span>
          <span>{t('transcript.empty.refs')}</span>
        </div>
        <div className="empty__row">
          <span className="empty__key">/</span>
          <span>{t('transcript.empty.commands')}</span>
        </div>
        <div className="empty__row">
          <span className="empty__key">{pasteKeyLabel(navigator.platform ?? '')}</span>
          <span>{t('transcript.empty.images')}</span>
        </div>
      </div>
    </div>
  )
}
