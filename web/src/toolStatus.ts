import type { ToolResultItem } from './types'

/**
 * 工具卡片的四种状态。设计稿见 docs/design/tool-progress.html 方案丙。
 *
 * 状态**不需要协议**：调用与结果本来就是两条独立消息，靠 toolUseId 配对 ——
 * 结果没到就是"还在跑"。Kotlin 侧一个字没动。
 */
export type ToolState = 'running' | 'done' | 'failed' | 'aborted'

/**
 * 由"结果到没到"和"回合结没结束"推出状态。
 *
 * [turnEnded] 是**推导出来**的（见 Transcript.tsx）：这张卡片之后已经出现过
 * 回合结束的 result 事件，却始终没等到自己的结果。
 *
 * 这一条不能省。被拒绝、被中断、会话被杀掉的工具**永远不会**再收到结果，
 * 少了它那张卡片会一直转圈，转到用户下次打开面板 —— 而且它转得和"真的在跑"
 * 一模一样，用户没有任何办法分辨。
 */
export function toolStateOf(result: ToolResultItem | undefined, turnEnded: boolean): ToolState {
  if (result) return result.isError ? 'failed' : 'done'
  return turnEnded ? 'aborted' : 'running'
}
