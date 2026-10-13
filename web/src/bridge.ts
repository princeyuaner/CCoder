/**
 * JS → Kotlin 的通道。Kotlin 侧在页面加载完成时注入 window.ccoder.send。
 *
 * 打开链接走这里而不是 window.open：JCEF 里的页面导航会把整个应用页面
 * 替换掉。Kotlin 侧用 BrowserUtil.browse 交给系统浏览器，
 * 并在 onBeforeBrowse 里做兜底拦截。
 */
export function openLink(url: string): void {
  window.ccoder?.send?.(JSON.stringify({ op: 'openLink', url }))
}

/**
 * 在编辑器里打开这次工具调用指向的文件。
 *
 * `line` 是 **1 基**行号（Claude 的 offset 原样），减一由 Kotlin 一侧做 ——
 * 两边各减一次就会差掉一行。没有行号时**不发这个字段**：收端不必去分辨
 * `line: 0` 是"第一行"还是"没有行号"。
 */
export function openFile(path: string, line?: number): void {
  const message = line === undefined ? { op: 'openFile', path } : { op: 'openFile', path, line }
  window.ccoder?.send?.(JSON.stringify(message))
}

/**
 * 打开「本轮改动前 ↔ 当前」的对比（2026-10-13，「本轮改动」卡）。
 *
 * `path` 是**工具卡上那个原样路径**：归一化在侧车做（`normalizeTurnPath`），
 * 这里一个字节都不动（同 `tools.ts` 顶上那条"两处各归一化一次会有两份真相"）。
 * 左侧内容由 Kotlin 找侧车要（快照），网页这边不碰文件内容。
 */
export function openTurnDiff(path: string): void {
  window.ccoder?.send?.(JSON.stringify({ op: 'openTurnDiff', path }))
}

/**
 * 回滚一批文件。
 *
 * **没有回调**：逐文件回执由 Kotlin 推回来（`revertReport` op → 那张卡的行标记），
 * 失败也在那里逐条说原因 —— 桥这一层只负责把请求送出去。
 */
export function revertFiles(paths: string[]): void {
  if (paths.length === 0) return
  window.ccoder?.send?.(JSON.stringify({ op: 'revertFiles', paths }))
}

/**
 * 撤掉一条定时待发（2026-10-13，定时发送 · 丙案）。
 *
 * 点的是转写区里那枚"待发"气泡上的 ✕。**没有回调**：Kotlin 撤完会把整张
 * 待发表重推一遍（`scheduled` op），气泡跟着消失 —— 与 [revertFiles] 同一分工。
 */
export function cancelScheduled(id: string): void {
  if (id === '') return
  window.ccoder?.send?.(JSON.stringify({ op: 'cancelScheduled', id }))
}

/**
 * 复制一段文本。
 *
 * **走桥，不走 `navigator.clipboard`**：JCEF 里页面是用 `loadHTML()` 塞进去的，
 * 那不是安全上下文 —— `navigator.clipboard` 是 `undefined`，点了复制键什么都
 * 不会发生（2026-09-15 用户报「复制键没用」就是这么来的）。平台那边的
 * `CopyPasteManager` 一直在，也一直是 IDE 里复制的正路。
 *
 * 桥不在时（浏览器里跑探针 / `npm run dev`）才退回标准 API —— 那条路只在
 * 那种场合能用。
 */
export function copyText(text: string): void {
  const send = window.ccoder?.send
  if (send) {
    send(JSON.stringify({ op: 'copy', text }))
    return
  }
  // 不是所有环境都给了 clipboard（非安全上下文里它整个不存在）
  const clip = (navigator as Navigator & { clipboard?: Clipboard }).clipboard
  void clip?.writeText(text)?.catch(() => undefined)
}
