import asyncio
import httpx
import pytest
from fastapi.testclient import TestClient
import app.main as main

client = TestClient(main.app, headers={"X-Agent-Token": "test-agent-token"})
evidence = [{"articleId": 1, "versionId": 1, "title": "VPN", "snippet": "Reconnect VPN", "score": .5, "citation": "article:1/version:1"}]

def test_partial_provider_error_is_not_reported_as_done(monkeypatch):
    monkeypatch.setattr(main, '_resolve', lambda *args: 'fake')
    async def tokens(*args):
        yield 'partial answer'
        raise RuntimeError('connection lost')
    monkeypatch.setattr(main, '_llm_stream_tokens', tokens)
    result = client.post('/v1/answer/stream', json={'question': 'VPN?', 'knowledgeContext': evidence})
    assert 'partial answer' in result.text
    assert 'STREAM_INTERRUPTED' in result.text
    assert 'event: done' not in result.text

@pytest.mark.parametrize('terminal', [True, False])
def test_provider_eof_requires_terminal_marker(monkeypatch, terminal):
    monkeypatch.setattr(main, '_PROVIDERS', {'fake': {'api_key': 'test', 'model': 'fake', 'base_url': 'https://fixture.invalid'}})
    body = 'data: {"choices":[{"delta":{"content":"VPN"}}]}\n\n'
    if terminal:
        body += 'data: [DONE]\n\n'
    transport = httpx.MockTransport(lambda req: httpx.Response(200, text=body))
    original = httpx.AsyncClient
    monkeypatch.setattr(main.httpx, 'AsyncClient', lambda **kwargs: original(transport=transport, **kwargs))
    async def collect():
        return [part async for part in main._llm_stream_tokens('fake', main.AnswerStreamIn(question='VPN?'))]
    if terminal:
        assert asyncio.run(collect()) == ['VPN']
    else:
        with pytest.raises(RuntimeError, match='结束标识'):
            asyncio.run(collect())
