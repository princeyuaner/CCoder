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
