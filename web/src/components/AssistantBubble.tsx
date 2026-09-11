import type { ReactNode } from 'react'

export function AssistantBubble({ children }: { children: ReactNode }) {
  return (
    <div className="row row--assistant" data-testid="assistant-bubble">
      <div className="bubble bubble--assistant">
        <div className="bubble__text">{children}</div>
      </div>
    </div>
  )
}
