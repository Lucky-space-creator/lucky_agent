// 安全 Markdown 渲染：先转义 HTML 防 XSS，再应用行内样式与块级结构

export interface Block {
  type: 'text' | 'code'
  /** type=code 时为原始文本；type=text 时为已转义+转换的 HTML */
  content: string
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function inline(s: string): string {
  let html = escapeHtml(s)
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  return html
}

function renderBlock(lines: string[]): Block {
  return { type: 'text', content: lines.map(inline).join('<br/>') }
}

function renderList(items: string[], ordered: boolean): Block {
  const tag = ordered ? 'ol' : 'ul'
  const lis = items.map((it) => `<li>${inline(it)}</li>`).join('')
  return { type: 'text', content: `<${tag}>${lis}</${tag}>` }
}

export function renderMarkdown(text: string): Block[] {
  if (!text) return []
  const blocks: Block[] = []
  const lines = text.split(/\r?\n/)
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // 围栏代码块
    if (line.trim().startsWith('```')) {
      const code: string[] = []
      i++
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        code.push(lines[i])
        i++
      }
      i++ // 跳过结束围栏
      blocks.push({ type: 'code', content: code.join('\n') })
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      const level = heading[1].length
      const tag = level === 1 ? 'h3' : level === 2 ? 'h3' : 'h4'
      blocks.push({ type: 'text', content: `<${tag}>${inline(heading[2])}</${tag}>` })
      i++
      continue
    }

    // 无序/有序列表：收集连续项
    const listStart = line.match(/^\s*[-*]\s+(.+)$/) || line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (listStart) {
      const ordered = !!line.match(/^\s*\d+[.)]\s+/)
      const items: string[] = [listStart[1]]
      i++
      while (i < lines.length) {
        const next = lines[i].match(/^\s*[-*]\s+(.+)$/) || lines[i].match(/^\s*\d+[.)]\s+(.+)$/)
        if (next) {
          items.push(next[1])
          i++
        } else {
          break
        }
      }
      blocks.push(renderList(items, ordered))
      continue
    }

    // 普通段落：收集至空行
    const para: string[] = []
    while (i < lines.length && lines[i].trim() !== '' && !lines[i].trim().startsWith('```')) {
      para.push(lines[i])
      i++
    }
    if (para.length > 0) {
      blocks.push(renderBlock(para))
    }
    // 跳过空行
    while (i < lines.length && lines[i].trim() === '') i++
  }

  return blocks
}
