import { useCallback, useEffect, useMemo, useState } from 'react'
import { highlightCode } from '../highlight'

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

  const copy = useCallback(async () => {
    try {
      // 复制**原始代码**而非高亮后的 HTML——后者含大量 span 标签
      await navigator.clipboard.writeText(code)
      setCopied(true)
    } catch {
      // 剪贴板不可用时静默失败，不打断阅读
    }
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
          title={wrap ? '关闭自动换行' : '开启自动换行'}
        >
          自动换行
        </button>
        <button type="button" className="code-block__action" onClick={copy} title="复制代码">
          {copied ? '已复制' : '复制'}
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
