"""向量检索端点（/v1/embed）的离线单测：mock 供应商 HTTP，不访问网络、不依赖真实 Key。"""
from fastapi.testclient import TestClient

from app import main as app_main
from app.main import app

client = TestClient(app, headers={"X-Agent-Token": "test-agent-token"})
FAKE_PROVIDER = {"glm": {"api_key": "test-glm-key", "base_url": "https://llm.example/v1", "model": "glm-5.3-flash"}}


class _FakeEmbedResponse:
    def __init__(self, status_code=200, payload=None):
        self.status_code = status_code
        self.text = "" if status_code < 400 else "upstream-error-body"
        self._payload = payload or {}

    def json(self):
        return self._payload


def test_embed_disabled_without_model(monkeypatch):
    monkeypatch.setattr(app_main, "EMBED_MODEL", "")
    r = client.post("/v1/embed", json={"texts": ["VPN 配置"]})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert "AGENT_EMBED_MODEL" in body["failureReason"]
    assert body["embeddings"] == []


def test_embed_success_orders_by_index_and_forwards_dimensions(monkeypatch):
    monkeypatch.setattr(app_main, "EMBED_MODEL", "embedding-3")
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDER)
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured["url"] = url
        captured["json"] = json
        # 乱序返回 index，验证按 index 重排
        return _FakeEmbedResponse(200, {"data": [{"index": 1, "embedding": [0.3, 0.4]}, {"index": 0, "embedding": [0.1, 0.2]}]})

    monkeypatch.setattr(app_main.httpx, "post", fake_post)
    r = client.post("/v1/embed", json={"texts": ["第一段", "第二段"]})
    body = r.json()
    assert r.status_code == 200 and body["ok"] is True
    assert captured["url"].endswith("/embeddings")
    assert captured["json"]["dimensions"] == 512
    assert captured["json"]["input"] == ["第一段", "第二段"]
    assert body["embeddings"] == [[0.1, 0.2], [0.3, 0.4]]
    assert body["dimensions"] == 2


def test_embed_upstream_error_fails_gracefully(monkeypatch):
    monkeypatch.setattr(app_main, "EMBED_MODEL", "embedding-3")
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDER)
    monkeypatch.setattr(app_main.httpx, "post", lambda url, headers=None, json=None, timeout=None: _FakeEmbedResponse(404))
    r = client.post("/v1/embed", json={"texts": ["x"]})
    body = r.json()
    assert body["ok"] is False
    assert "404" in body["failureReason"]
    assert body["embeddings"] == []


def test_embed_rejects_empty_texts(monkeypatch):
    monkeypatch.setattr(app_main, "EMBED_MODEL", "")
    r = client.post("/v1/embed", json={"texts": ["  "]})
    assert r.json()["ok"] is False


def test_embed_endpoint_requires_service_token():
    anon = TestClient(app)  # 不带 X-Agent-Token
    assert anon.post("/v1/embed", json={"texts": ["x"]}).status_code == 401
    assert anon.get("/v1/embed/status").status_code == 401


def test_embed_status_reflects_configuration(monkeypatch):
    monkeypatch.setattr(app_main, "EMBED_MODEL", "")
    assert client.get("/v1/embed/status").json()["enabled"] is False
    monkeypatch.setattr(app_main, "EMBED_MODEL", "embedding-3")
    status = client.get("/v1/embed/status").json()
    assert status == {"enabled": True, "model": "embedding-3", "dimensions": 512}
