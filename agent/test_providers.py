"""供应商注册、解析与 LLM 调用健壮性的离线单测：不访问网络，不依赖真实 Key。"""
import json

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app import main as app_main
from app.main import app

client = TestClient(app, headers={"X-Agent-Token": "test-agent-token"})

FAKE_PROVIDERS = {
    "glm": {"api_key": "test-glm-key", "base_url": "https://llm.example/v1", "model": "glm-5.3-flash"},
    "deepseek": {"api_key": "test-ds-key", "base_url": "https://ds.example", "model": "deepseek-chat"},
}


class _FakeResponse:
    def __init__(self, status_code=200, content="{}"):
        self.status_code = status_code
        self.text = content if status_code >= 400 else ""
        self._payload = {"choices": [{"message": {"content": content}}]}

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError(f"HTTP {self.status_code}", request=None, response=None)


def test_default_provider_prefers_glm():
    assert app_main._default_provider(FAKE_PROVIDERS) == "glm"


def test_default_provider_respects_llm_provider_env(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "deepseek")
    assert app_main._default_provider(FAKE_PROVIDERS) == "deepseek"


def test_default_provider_ignores_unknown_env_value(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "qwen")
    assert app_main._default_provider(FAKE_PROVIDERS) == "glm"


def test_default_provider_none_without_configured_key():
    assert app_main._default_provider({}) is None


def test_resolve_rejects_unconfigured_provider():
    with pytest.raises(HTTPException) as exc:
        app_main._resolve("qwen", FAKE_PROVIDERS)
    assert exc.value.status_code == 400


def test_resolve_accepts_configured_provider_case_insensitive():
    assert app_main._resolve("GLM", FAKE_PROVIDERS) == "glm"


def test_llm_json_strips_markdown_fences(monkeypatch):
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured["url"] = url
        captured["payload"] = json
        return _FakeResponse(content="```json\n{\"ok\": true}\n```")

    monkeypatch.setattr(app_main.httpx, "post", fake_post)
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDERS)
    result, source = app_main._llm_json("system", "user", "glm")
    assert result == {"ok": True}
    assert source == "GLM"
    assert captured["url"] == "https://llm.example/v1/chat/completions"
    assert captured["payload"]["model"] == "glm-5.3-flash"
    assert captured["payload"]["response_format"] == {"type": "json_object"}


def test_llm_json_retries_without_response_format_on_400(monkeypatch):
    calls = []

    def fake_post(url, headers=None, timeout=None, **kwargs):
        calls.append(kwargs.get("json"))
        if calls[-1].get("response_format"):
            return _FakeResponse(status_code=400, content="response_format unsupported")
        return _FakeResponse(content=json.dumps({"ok": True}))

    monkeypatch.setattr(app_main.httpx, "post", fake_post)
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDERS)
    result, _ = app_main._llm_json("system", "user", "glm")
    assert result == {"ok": True}
    assert len(calls) == 2
    assert "response_format" not in calls[-1]


def test_llm_json_requires_api_key(monkeypatch):
    monkeypatch.setattr(app_main, "_PROVIDERS", {"glm": {"api_key": "", "base_url": "https://llm.example/v1", "model": "glm-5.3-flash"}})
    with pytest.raises(RuntimeError, match="GLM"):
        app_main._llm_json("system", "user", "glm")


def test_classify_endpoint_with_explicit_provider(monkeypatch):
    def fake_post(url, headers=None, timeout=None, **kwargs):
        return _FakeResponse(content=json.dumps({"category": "NETWORK", "priority": "P2", "queueCode": "NETWORK", "summary": "VPN 认证超时", "nextStep": "检查认证日志与端口", "confidence": 0.9}))

    monkeypatch.setattr(app_main.httpx, "post", fake_post)
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDERS)
    response = client.post("/v1/classify", json={"title": "VPN 无法连接", "description": "认证超时", "provider": "glm"})
    assert response.status_code == 200
    body = response.json()
    assert body["source"] == "GLM"
    assert body["category"] == "NETWORK"
    assert body["queueCode"] == "NETWORK"


def test_classify_endpoint_unknown_provider_returns_400():
    response = client.post("/v1/classify", json={"title": "x", "description": "y", "provider": "qwen"})
    assert response.status_code == 400


def test_providers_endpoint_reports_registry(monkeypatch):
    monkeypatch.setattr(app_main, "_PROVIDERS", FAKE_PROVIDERS)
    response = client.get("/v1/providers")
    assert response.status_code == 200
    body = response.json()
    assert body["default"] == "glm"
    assert {p["key"] for p in body["providers"]} == {"glm", "deepseek"}
