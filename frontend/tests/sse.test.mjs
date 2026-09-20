import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

// Run the actual transport; replace only its Vite-specific API base import.
const source = (await readFile(new URL('../src/lib/sse.ts', import.meta.url), 'utf8'))
  .replace("import { apiBase } from '../api'", "const apiBase = '/api'")
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { askStream } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
const originalFetch = globalThis.fetch
const encoder = new TextEncoder()

function response(text, chunkSize = 1) {
  const bytes = encoder.encode(text)
  return new Response(new ReadableStream({ start(c) {
    for (let i = 0; i < bytes.length; i += chunkSize) c.enqueue(bytes.slice(i, i + chunkSize))
    c.close()
  } }))
}
test.afterEach(() => { globalThis.fetch = originalFetch })

test('CRLF frames and split UTF-8 bytes preserve Chinese tokens and metadata', async () => {
  globalThis.fetch = async () => response('event: token\r\ndata: {"delta":"中文答案"}\r\n\r\nevent: done\r\ndata: {"messageId":3,"conversationId":2,"source":"RULE_FALLBACK","confidence":0.4}\r\n\r\n')
  let content = '', done
  await askStream({ question: '问题', onToken: t => { content += t }, onDone: x => { done = x }, onError: assert.fail })
  assert.equal(content, '中文答案')
  assert.equal(done.messageId, 3)
  assert.equal(done.source, 'RULE_FALLBACK')
})
test('unexpected EOF reports interruption instead of success', async () => {
  globalThis.fetch = async () => response('event: token\ndata: {"delta":"partial"}\n\n', 12)
  const errors = []
  await askStream({ question: 'q', onDone: assert.fail, onError: e => errors.push(e) })
  assert.deepEqual(errors, ['STREAM_INTERRUPTED'])
})
test('reader rejection is handled', async () => {
  globalThis.fetch = async () => new Response(new ReadableStream({ start(c) { c.error(new Error('network lost')) } }))
  const errors = []
  await askStream({ question: 'q', onError: e => errors.push(e) })
  assert.deepEqual(errors, ['STREAM_INTERRUPTED'])
})
test('stop while connecting does not publish an error to another conversation', async () => {
  const controller = new AbortController()
  globalThis.fetch = async () => { controller.abort(); throw new DOMException('Stopped', 'AbortError') }
  await askStream({ question: 'q', signal: controller.signal, onDone: assert.fail, onError: assert.fail })
})
test('cancelled streams ignore buffered tokens and terminal events', async () => {
  const controller = new AbortController()
  globalThis.fetch = async () => { controller.abort(); return response('event: token\ndata: {"delta":"stale"}\n\nevent: done\ndata: {}\n\n') }
  await askStream({ question: 'q', signal: controller.signal, onToken: assert.fail, onDone: assert.fail, onError: assert.fail })
})
test('only the first terminal event is delivered', async () => {
  globalThis.fetch = async () => response('event: done\ndata: {"messageId":1}\n\nevent: done\ndata: {"messageId":2}\n\n', 500)
  const ids = []
  await askStream({ question: 'q', onDone: d => ids.push(d.messageId), onError: assert.fail })
  assert.deepEqual(ids, [1])
})
test('no evidence carries the persisted conversation and message identity', async () => {
  globalThis.fetch = async () => response('event: error\ndata: {"reason":"NO_EVIDENCE","messageId":9,"conversationId":8}\n\n')
  let result
  await askStream({ question: 'q', onError: (...args) => { result = args } })
  assert.deepEqual(result, ['NO_EVIDENCE', 9, 8])
})
test('HTTP failure is surfaced', async () => {
  globalThis.fetch = async () => new Response('', { status: 429 })
  let error
  await askStream({ question: 'q', onError: e => { error = e } })
  assert.equal(error, 'HTTP_429')
})
