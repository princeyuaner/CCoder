import { useEffect, useState, type ReactNode } from 'react'

interface Props {
  /** 标题可以是节点：进行中的思考要在标题里放转圈和秒数。 */
  title: ReactNode
  /**
   * 初始形态 —— 而且**偏好变了要跟着复位**（见下面那条 effect）。
   *
   * 于是语义是"初始值 + 偏好变化时的复位值"：用户手动开合优先，直到偏好本身改变。
   */
  defaultOpen?: boolean
  children: ReactNode
}

/** 真折叠组件。第一版里的是个假的——只加了 ▸ 前缀，点了没反应。 */
export function Collapsible({ title, defaultOpen = false, children }: Props) {
  const [open, setOpen] = useState(defaultOpen)

  // 偏好变了当场复位（2026-09-21，为「思考折叠」那一条加的）。
  //
  // 为什么非要有：`defaultOpen` 是**初始值**，`useState` 只在挂载那一刻理它 ——
  // 而偏好是页面跑起来之后才推过来的（关设置对话框那一刻）。少了这条 effect，
  // 已经画在屏幕上的思考块一动不动，看起来像开关没生效。
  //
  // 挂载时那次 `setOpen(同值)` 被 React 的 `Object.is` 挡掉 —— 不是冗余代码，别删。
  // 于是"用户自己点开的那一块"只在偏好真的变了时才被拉回去。
  useEffect(() => {
    setOpen(defaultOpen)
  }, [defaultOpen])

  return (
    <div className="collapsible">
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
