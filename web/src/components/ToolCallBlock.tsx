import { Collapsible } from './Collapsible'

export function ToolCallBlock({ name, input }: { name: string; input: string }) {
  let pretty = input
  try {
    // 模型给的参数通常是紧凑 JSON，缩进后更易读。
    // 不是合法 JSON 就按原文显示——工具参数未必都是 JSON。
    pretty = JSON.stringify(JSON.parse(input), null, 2)
  } catch {
    // 保持原文
  }

  return (
    <Collapsible title={`工具：${name}`}>
      <pre className="tool-input">{pretty}</pre>
    </Collapsible>
  )
}
