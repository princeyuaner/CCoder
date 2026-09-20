import { useCallback, useEffect, useMemo, useState } from 'react'
import { copyText } from '../bridge'
import { highlightCode } from '../highlight'
import { t, useLang } from '../i18n'

const WRAP_KEY = 'ccoder.codeWrap'

/** 换行开关是全局偏好而非每块独立——用户要么都换行，要么都不换。 */
function readWrapPreference(): boolean {
  try {
    return localStorage.getItem(WRAP_KEY) === '1'
  } catch {
    // 隐私模式下可能不可用
    return false
  }
}

export function CodeBlock({ code, lang }: { code: string; lang: string }) {
  // 订阅挂在**这一层**，不是 Markdown 那一层：代码块的三个按钮是文案，
  // 换语言要跟着换，而 Markdown 那份 memo（marked 分词 + 整棵元素树）不该
  // 因为换语言重跑一遍（见 Markdown.tsx 文件头那笔账）
  useLang()
  const [wrap, setWrap] = useState(readWrapPreference)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    try {
      localStorage.setItem(WRAP_KEY, wrap ? '1' : '0')
    } catch {
      // 隐私模式下可能不可用，忽略即可
    }
  }, [wrap])

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(t)
  }, [copied])

  const html = useMemo(() => highlightCode(code, lang), [code, lang])
  const showLang = lang.trim().length > 0

  const copy = useCallback(() => {
    // 复制**原始代码**而非高亮后的 HTML——后者含大量 span 标签。
    // 走桥（见 copyText）：JCEF 里 navigator.clipboard 根本不存在
    copyText(code)
    setCopied(true)
  }, [code])

  return (
    <div className={`code-block${wrap ? ' is-wrapped' : ''}`} data-testid="code-block">
      <div className="code-block__bar">
        {showLang && (
          <span className="code-block__lang" data-testid="code-lang">
            {lang}
          </span>
        )}
        <span className="code-block__spacer" />
        <button
          type="button"
          className="code-block__action"
          onClick={() => setWrap((v) => !v)}
          aria-pressed={wrap}
          // 悬停提示说的是**点下去会发生什么**，与按钮上的「自动换行」+
          // aria-pressed 合起来才不歧义（开着的时候标题是"关掉"）
          title={wrap ? t('code.disableWrap') : t('code.enableWrap')}
        >
          {t('code.wrap')}
        </button>
        <button
          type="button"
          className="code-block__action"
          onClick={copy}
          title={t('code.copyTitle')}
        >
          {copied ? t('code.copied') : t('code.copy')}
        </button>
      </div>
      {/* 高亮结果是 highlight.js 生成并转义过的 HTML，不是用户输入 */}
      <pre className="code-block__pre">
        <code
          className="code-block__code hljs"
          // eslint-disable-next-line react/no-danger
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </pre>
    </div>
  )
}
