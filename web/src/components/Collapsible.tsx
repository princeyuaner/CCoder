import { useState, type ReactNode } from 'react'

interface Props {
  title: string
  defaultOpen?: boolean
  dim?: boolean
  children: ReactNode
}

/** 真折叠组件。第一版里的是个假的——只加了 ▸ 前缀，点了没反应。 */
export function Collapsible({ title, defaultOpen = false, dim = false, children }: Props) {
  const [open, setOpen] = useState(defaultOpen)

  return (
    <div className={`collapsible${dim ? ' collapsible--dim' : ''}`}>
      <button
        type="button"
        className="collapsible__head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`collapsible__chevron${open ? ' is-open' : ''}`}>▸</span>
        <span className="collapsible__title">{title}</span>
      </button>
      {open && <div className="collapsible__body">{children}</div>}
    </div>
  )
}
