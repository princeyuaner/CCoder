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
