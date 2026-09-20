/** 解析引用串（与后端 KnowledgeService/VaultService 生成的格式一致）并在新标签页打开原文。 */
export interface CitationTarget {
  type: 'article' | 'note'
  articleId?: number
  versionId?: number
  chunk?: number | null
  noteId?: number
}

export function citationParts(citation: string): CitationTarget | null {
  const m = /^article:(\d+)\/version:(\d+)(?:#c(\d+))?$/.exec(citation || '')
  if (m) return { type: 'article', articleId: Number(m[1]), versionId: Number(m[2]), chunk: m[3] ? Number(m[3]) : null }
  const n = /^note:(\d+)$/.exec(citation || '')
  if (n) return { type: 'note', noteId: Number(n[1]) }
  return null
}

export function openCitationTarget(citation: string) {
  const p = citationParts(citation)
  if (!p) return false
  if (p.type === 'article') {
    window.open(`/knowledge/articles/${p.articleId}?version=${p.versionId}${p.chunk != null ? `&chunk=${p.chunk}` : ''}`, '_blank')
  } else if (p.noteId) {
    window.open(`/vault/notes/${p.noteId}`, '_blank')
  }
  return true
}
