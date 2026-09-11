import { useMemo, type ReactNode } from 'react'
import { marked, type Tokens } from 'marked'
import { CodeBlock } from './CodeBlock'
import { openLink } from '../bridge'

/**
 * 把模型输出的 Markdown 渲染为 React 元素。
 *
 * **不用 dangerouslySetInnerHTML 渲染整段**：那样模型输出里的 <script>
 * 与 onerror 之类会真的生效。这里把 marked 解析成 token，再逐节点构造
 * React 元素 —— React 的文本插值天然转义，只有代码高亮那一处是受控的
 * dangerouslySetInnerHTML。
 */
export function Markdown({ text }: { text: string }) {
  const tokens = useMemo(() => marked.lexer(text), [text])
  return (
    <>
      {tokens.map((t, i) => (
        <BlockToken key={i} token={t as Tokens.Generic} />
      ))}
    </>
  )
}

/** 只有 http/https 才当作可打开的链接，其余一律当纯文本。 */
function safeUrl(href: string | null | undefined): string | null {
  if (!href) return null
  return /^https?:\/\//i.test(href) ? href : null
}

function BlockToken({ token }: { token: Tokens.Generic }): ReactNode {
  switch (token.type) {
    case 'paragraph':
      return <p>{renderInline(token.tokens ?? [])}</p>

    case 'heading': {
      const depth = Math.min(Math.max(token.depth ?? 1, 1), 6)
      const Tag = `h${depth}` as 'h1'
      return <Tag>{renderInline(token.tokens ?? [])}</Tag>
    }

    case 'code':
      return <CodeBlock code={token.text ?? ''} lang={(token.lang ?? '').trim()} />

    case 'list': {
      const Tag = token.ordered ? 'ol' : 'ul'
      return (
        <Tag>
          {(token.items ?? []).map((item: Tokens.ListItem, i: number) => (
            <li key={i}>{renderInline(item.tokens ?? [])}</li>
          ))}
        </Tag>
      )
    }

    case 'blockquote':
      return (
        <blockquote>
          {(token.tokens ?? []).map((t: Tokens.Generic, i: number) => (
            <BlockToken key={i} token={t} />
          ))}
        </blockquote>
      )

    case 'hr':
      return <hr />

    case 'space':
      return null

    // 模型输出里的裸 HTML 与未知 token 一律按纯文本渲染。
    // React 会转义，因此不会真的产生元素，也不会执行脚本。
    default:
      return token.raw ? <p>{token.raw}</p> : null
  }
}

function renderInline(tokens: Tokens.Generic[]): ReactNode[] {
  return tokens.map((t, i) => <InlineToken key={i} token={t} />)
}

function InlineToken({ token }: { token: Tokens.Generic }): ReactNode {
  switch (token.type) {
    case 'text':
      // text token 可能自带子 token（marked 在链接、强调等处会嵌套）
      return token.tokens && token.tokens.length > 0 ? (
        <>{renderInline(token.tokens)}</>
      ) : (
        <>{token.text ?? token.raw}</>
      )

    case 'codespan':
      return <code className="inline-code">{token.text}</code>

    case 'strong':
      return <strong>{renderInline(token.tokens ?? [])}</strong>

    case 'em':
      return <em>{renderInline(token.tokens ?? [])}</em>

    case 'del':
      return <del>{renderInline(token.tokens ?? [])}</del>

    case 'link': {
      const url = safeUrl(token.href)
      if (!url) {
        // 非 http/https 的链接退化为纯文本，不给它任何可点击语义
        return <>{renderInline(token.tokens ?? [])}</>
      }
      return (
        <a
          href={url}
          className="md-link"
          onClick={(e) => {
            // 阻止默认导航：JCEF 里的页面导航会把整个应用页面替换掉
            e.preventDefault()
            openLink(url)
          }}
        >
          {renderInline(token.tokens ?? [])}
        </a>
      )
    }

    case 'br':
      return <br />

    // 内联 HTML 与未知 token 按纯文本渲染
    default:
      return <>{token.raw}</>
  }
}
