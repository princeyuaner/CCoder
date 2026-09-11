import hljs from 'highlight.js/lib/core'

// 按需注册：全量 highlight.js 约 1MB，这里只引常用的十余种，约 100KB
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import go from 'highlight.js/lib/languages/go'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('go', go)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('kotlin', kotlin)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('python', python)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

/** 常见别名 → 注册名 */
const ALIASES: Record<string, string> = {
  js: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  jsx: 'javascript',
  py: 'python',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  html: 'xml',
  kt: 'kotlin',
  rs: 'rust',
  golang: 'go',
}

function normalize(lang: string): string {
  const lower = lang.trim().toLowerCase()
  return ALIASES[lower] ?? lower
}

export function isKnownLanguage(lang: string): boolean {
  const normalized = normalize(lang)
  return normalized.length > 0 && hljs.getLanguage(normalized) !== undefined
}

/**
 * 高亮代码。
 *
 * 语言未注册时返回**转义后的原文** —— 绝不抛错，因为代码内容来自模型输出，
 * 什么语言标记都可能出现。转义是必须的：结果是经
 * dangerouslySetInnerHTML 注入的。
 */
export function highlightCode(code: string, lang: string): string {
  const normalized = normalize(lang)

  if (normalized.length > 0 && hljs.getLanguage(normalized)) {
    try {
      return hljs.highlight(code, { language: normalized, ignoreIllegals: true }).value
    } catch {
      // 落到转义原文
    }
  }
  return escapeHtml(code)
}

export function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
