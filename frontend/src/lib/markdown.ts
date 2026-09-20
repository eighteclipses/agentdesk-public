import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

/** 渲染可信 Markdown：先经 markdown-it 转 HTML，再 DOMPurify 消毒（禁止原始 HTML）。 */
export function renderMarkdown(src: string): string {
  return DOMPurify.sanitize(md.render(src || ''))
}

/** 解析 Obsidian 风格 [[标题#锚点|别名]] 为可点击链接；未解析目标保留原文。 */
export function renderWikilinks(src: string): string {
  const html = md.render(src || '')
  return html.replace(/\[\[([^\]|#]+?)(?:#([^\]|]+?))?(?:\|([^\]]+?))?\]\]/g, (_m, title: string, _anchor?: string, alias?: string) => {
    const label = (alias || title).trim()
    const href = `/vault?wikilink=${encodeURIComponent(title.trim())}`
    return `<a href="${href}" class="wikilink">${escapeHtml(label)}</a>`
  })
}

export function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
