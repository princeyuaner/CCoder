import { memo, useMemo, useRef, type CSSProperties, type ReactNode } from 'react'
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
/**
 * **必须 memo**：正文在流的那些帧里，历史消息的 text 一个字都没变，
 * 而重新渲染意味着整段的 marked 分词与整棵元素树重建（2026-09-14 实测
 * 每帧 48ms 就是这么攒出来的）。text 没变就一个字节都不用重算。
 */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  const tokens = useMemo(() => marked.lexer(text), [text])

  /**
   * 块级元素缓存 —— 流式输出时"只重建尾巴"。
   *
   * 一份 1.5 万字的正文分出来 841 个块，逐帧全部重建元素并逐节点协调要 22ms
   * （2026-09-14 实测；同一段文本分词只花 3ms，所以贵的是建树不是分词）。
   * 而流式时**前面那些块一个字都没变** —— 它们没有理由每 16ms 重建一次。
   *
   * 复用的是**元素对象本身**：React 在协调时如果发现新旧元素是同一个引用，
   * 会直接跳过整棵子树（这不是取巧，是官方认可的 children 优化路径）。
   * 内容没变就必然命中，于是每帧只剩最后一个块在建。
   *
   * 键要带上块的原文 `raw`：同一个位置换了内容（改稿、重放、下一条消息复用
   * 这个组件实例）必须重建，只按下标匹配会拿旧元素糊在新内容上。
   */
  const cache = useRef(new Map<number, { sig: string; el: ReactNode }>())

  return (
    <>
      {tokens.map((t, i) => {
        const token = t as Tokens.Generic
        const sig = `${token.type}:${token.raw ?? ''}`
        const hit = cache.current.get(i)
        if (hit !== undefined && hit.sig === sig) return hit.el

        const el = <BlockToken key={i} token={token} />
        cache.current.set(i, { sig, el })
        return el
      })}
    </>
  )
})

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
            <li key={i}>
              {/* 任务列表的勾选框。marked 把 `[x] ` 从文本里**摘掉了**，只看
                  item.task / item.checked —— 不画的话"已完成 / 未完成"这个信息
                  就整个消失了，界面上看着与普通列表一模一样 */}
              {item.task ? (
                <input
                  className="md-task"
                  type="checkbox"
                  checked={item.checked === true}
                  readOnly
                />
              ) : null}
              {renderBlocks(item.tokens ?? [])}
            </li>
          ))}
        </Tag>
      )
    }

    case 'table': {
      const t = token as Tokens.Table
      // 外面这层是为了**横向滚动**：窄栏里列一多必然超宽，让它自己滚，
      // 而不是把气泡撑破（撑破的代价是整个转写区出横向滚动条）
      return (
        <div className="md-table">
          <table>
            <thead>
              <tr>
                {t.header.map((cell, i) => (
                  <th key={i} style={alignStyle(t.align?.[i])}>
                    {renderInline(cell.tokens ?? [])}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {t.rows.map((row, r) => (
                <tr key={r}>
                  {row.map((cell, c) => (
                    <td key={c} style={alignStyle(t.align?.[c])}>
                      {renderInline(cell.tokens ?? [])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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

/**
 * 列表项里的内容。
 *
 * **不能对 item.tokens 一律用 renderInline**：嵌套列表与松散列表里的段落都是
 * **块级** token，而 renderInline 落到 default 分支会把 `token.raw` 原样吐出来
 * —— 界面上就是连着短横线的一行纯文本（`  - 嵌套一层`），2026-09-14 用真实
 * 产物截图确认过。所以按类型分流：块级交给 BlockToken，行内的
 * （text / strong / codespan / link …）仍走 renderInline。
 */
const BLOCK_TOKENS = new Set(['paragraph', 'list', 'code', 'blockquote', 'heading', 'hr', 'table'])

function renderBlocks(tokens: Tokens.Generic[]): ReactNode[] {
  return tokens.map((t, i) => {
    // 项里的**第二段**：marked 不给它 paragraph，而是 `text` / `space` / `text`
    // 三连（实测）。space 若按块级处理会被丢掉，两段就粘成"一第二段" ——
    // 把它的原文放回 DOM，靠 `.bubble__text` 的 pre-wrap 还原成空行
    if (t.type === 'space') return <span key={i}>{t.raw}</span>
    return BLOCK_TOKENS.has(t.type) ? (
      <BlockToken key={i} token={t} />
    ) : (
      <InlineToken key={i} token={t} />
    )
  })
}

/**
 * 表格列的对齐。marked 给的是 `'left' | 'right' | 'center' | null`，
 * null（没写对齐行）交给 CSS 的默认值，不生成内联样式。
 */
function alignStyle(align: Tokens.Table['align'][number] | undefined): CSSProperties | undefined {
  return align ? { textAlign: align } : undefined
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
