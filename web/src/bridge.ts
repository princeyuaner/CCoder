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
