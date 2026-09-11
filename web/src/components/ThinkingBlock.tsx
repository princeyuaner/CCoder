import { Collapsible } from './Collapsible'

export function ThinkingBlock({ text }: { text: string }) {
  return (
    <Collapsible title="思考过程" dim>
      <div className="thinking-text">{text}</div>
    </Collapsible>
  )
}
