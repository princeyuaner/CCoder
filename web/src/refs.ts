/**
 * 输入框里那行参考记号 —— `⟦路径 24-27 · 4 行⟧`。
 *
 * 用户敲进去的是这一行；发送的那一刻才由 Kotlin 展开成「路径 + 围栏 + 代码全文」
 * 发给模型（`ComposerReferences.kt` 的 `SnippetRefs.expand`）。而转写区里画的是
 * **用户敲的那份**，所以气泡里要按输入框的样子把记号画出底色 —— 否则 `⟦⟧`
 * 光秃秃地混在正文当中，看着像乱码（2026-09-15 用户提）。
 *
 * **`⟦` / `⟧` 必须与 Kotlin 侧的 `REF_OPEN` / `REF_CLOSE` 一致。** 记号在发送时
 * 就被展开掉了，协议里没有它的位置，这两个字符只能两边各存一份 —— 改一处要改两处。
 */
export interface RefSegment {
  text: string
  /** true = 这一段是记号，要画底色。 */
  ref: boolean
}

const REF = /⟦[^⟧]*⟧/g

/**
 * 把一段文本切成「普通文字」与「记号」两类片段，顺序不变。
 *
 * 没有记号时给出原样的单段 —— 绝大多数消息走的就是这条路，DOM 与从前一字不差。
 * 认不出成对的 `⟦` 一律当普通文字留着：拿不准就什么都别做，把用户写的东西原样画出来。
 */
export function splitRefs(text: string): RefSegment[] {
  const out: RefSegment[] = []
  let last = 0
  for (const m of text.matchAll(REF)) {
    const at = m.index ?? 0
    if (at > last) out.push({ text: text.slice(last, at), ref: false })
    out.push({ text: m[0], ref: true })
    last = at + m[0].length
  }
  if (last < text.length) out.push({ text: text.slice(last), ref: false })
  return out
}
