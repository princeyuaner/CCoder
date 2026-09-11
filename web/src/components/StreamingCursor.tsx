/** 逐字流入时末尾的闪烁光标。动画在 styles.css 里，尊重 prefers-reduced-motion。 */
export function StreamingCursor() {
  return <span className="streaming-cursor" data-testid="streaming-cursor" aria-hidden="true" />
}
