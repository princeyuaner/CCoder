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

/**
 * 还原 marked 为「拼 HTML 字符串」做的预处理。
 *
 * marked 的 token `text` 字段是给 HTML 输出用的，已被转义：`"` → `&quot;`、
 * `'` → `&#39;`。但本组件构造的是 React 元素，React 的文本插值自己会转义 ——
 * 直接用 `token.text` 就转了两遍，界面上显示成 `&quot;2&quot;`、`you&#39;d`。
 * 实测故障：模型回复的 `"2"` 显示成 `&quot;2&quot;`，原始会话记录里是干净的真实引号。
 *
 * **只对 `codespan` 用它**。两类 token 的转义规则不同（marked 14 实测）：
 *
 * | 源文本 | text token 的 text | codespan 的 text |
 * |---|---|---|
 * | `写 &lt; 字面量` | `写 &lt; 字面量`（**不动**，marked 视为已是实体） | `写 &amp;lt; 字面量`（全转义） |
 * | `写 & 和 "` | `写 &amp; 和 &quot;` | `写 &amp; 和 &quot;` |
 *
 * text token 走的是 entity-aware 的 `escape()`，**不可逆**（源里字面的 `&lt;`
 * 与转义结果无法区分），所以那边必须改用 `token.raw` 取原文；codespan 走的是
 * `escape(text, true)`，全部 `&` 都转义，因此可逆。
 *
 * 必须**最后**处理 `&amp;`，否则 `&amp;lt;` 会被二次解码成 `<`。
 */
function unescapeHtml(s: string): string {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
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
        // 用 raw（原文）而不是 text：text 被 marked 转义过，且那套转义
        // 对 `&lt;` 这类已是实体的写法不可逆（见 unescapeHtml 的对照表）
        <>{token.raw ?? ''}</>
      )

    case 'codespan':
      // 同上：codespan 的 text 也被 marked 转义过。
      // 不用 token.raw —— 它带着定界反引号，且反引号个数可变，剥起来不可靠。
      return <code className="inline-code">{unescapeHtml(token.text ?? '')}</code>

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
