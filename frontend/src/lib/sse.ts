import { apiBase } from '../api'

/**
 * 流式问答 SSE 消费：fetch POST（EventSource 不能 POST）+ ReadableStream 逐帧解析。
 * 事件契约：stage -> citations(先于首个 token) -> token... -> done / error。
 */
export interface Citation {
  id?: number
  citation: string
  articleId?: number
  versionId?: number
  chunkId?: number
  chunkIndex?: number
  title?: string
  snippet?: string
  headingPath?: string
  page?: number
  score?: number
}

export interface AskStreamCallbacks {
  onStage?: (step: string, elapsedMs: number) => void
  onCitations?: (hits: Citation[]) => void
  onToken?: (delta: string) => void
  onDone?: (info: { messageId: number; conversationId: number; totalMs: number; source?: string; confidence?: number; citations?: Citation[] }) => void
  onError?: (reason: string, messageId?: number, conversationId?: number) => void
}

export async function askStream(opts: {
  question: string
  scope?: 'ENTERPRISE' | 'PERSONAL' | 'ALL'
  provider?: string
  conversationId?: number
  signal?: AbortSignal
} & AskStreamCallbacks): Promise<void> {
  const { question, scope, provider, conversationId, signal, ...cb } = opts
  let res: Response
  try {
    res = await fetch(`${apiBase}/knowledge/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      signal,
      body: JSON.stringify({ question, scope: scope || 'ENTERPRISE', provider, conversationId }),
    })
  } catch (e: any) {
    if (e?.name !== 'AbortError') cb.onError?.('AGENT_UNAVAILABLE')
    return
  }
  if (!res.ok || !res.body) { cb.onError?.('HTTP_' + res.status); return }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal = false

  const handleFrame = (frame: string) => {
    let event: string | null = null
    let data = ''
    if (terminal || signal?.aborted) return
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).trim()
    }
    if (!event || !data) return
    let payload: any
    try { payload = JSON.parse(data) } catch { return }
    switch (event) {
      case 'stage': cb.onStage?.(payload.step || '', payload.elapsedMs || 0); break
      case 'citations': cb.onCitations?.(payload.hits || []); break
      case 'token': cb.onToken?.(String(payload.delta ?? '')); break
      case 'done': terminal = true; cb.onDone?.({ messageId: payload.messageId, conversationId: payload.conversationId, totalMs: payload.totalMs || 0, source: payload.source, confidence: payload.confidence, citations: payload.citations }); break
      case 'error': terminal = true; cb.onError?.(String(payload.reason || 'AGENT_ERROR'), payload.messageId, payload.conversationId); break
    }
  }

  try {
    while (!terminal && !signal?.aborted) {
      const { done, value } = await reader.read()
      if (done) { buffer += decoder.decode(); break }
      buffer += decoder.decode(value, { stream: true })
      let boundary: RegExpExecArray | null
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        handleFrame(buffer.slice(0, boundary.index))
        buffer = buffer.slice(boundary.index + boundary[0].length)
      }
    }
    if (buffer.trim()) handleFrame(buffer)
    if (!terminal && !signal?.aborted) cb.onError?.('STREAM_INTERRUPTED')
  } catch (e: any) {
    if (!terminal && !signal?.aborted && e?.name !== 'AbortError') cb.onError?.('STREAM_INTERRUPTED')
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
