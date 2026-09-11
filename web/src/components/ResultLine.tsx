export function ResultLine({
  subtype,
  costUsd,
  durationMs,
}: {
  subtype: string
  costUsd?: number
  durationMs?: number
}) {
  const parts = [subtype]
  // 只在字段存在时显示——Kotlin 侧为 null 时整体省略该字段
  if (typeof costUsd === 'number') parts.push(`$${costUsd.toFixed(4)}`)
  if (typeof durationMs === 'number') parts.push(`${durationMs}ms`)

  return <div className="result-line">{parts.join(' · ')}</div>
}
