export function UserBubble({ text }: { text: string }) {
  return (
    <div className="row row--user" data-testid="user-bubble">
      <div className="bubble bubble--user">
        <div className="bubble__text">{text}</div>
      </div>
    </div>
  )
}
