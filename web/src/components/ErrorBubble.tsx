export function ErrorBubble({ text }: { text: string }) {
  return (
    <div className="row row--assistant" data-testid="error-bubble">
      <div className="bubble bubble--error">
        <div className="bubble__text">{text}</div>
      </div>
    </div>
  )
}
