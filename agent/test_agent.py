from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, headers={"X-Agent-Token": "test-agent-token"})


def test_classify_has_auditable_fields():
    response = client.post("/v1/classify", json={"title": "VPN 无法连接", "description": "认证超时"})
    assert response.status_code == 200
    body = response.json()
    assert body["category"] == "NETWORK"
    assert 0 <= body["confidence"] <= 1
    assert "failureReason" in body


def test_unknown_question_refuses_without_evidence():
    response = client.post("/v1/retrieve-draft", json={"question": "量子计算机采购预算"})
    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is False
    assert body["citations"] == []


def test_service_token_is_required():
    anonymous = TestClient(app)
    response = anonymous.post("/v1/classify", json={"title": "x", "description": "y"})
    assert response.status_code == 401
    # /health 属于探活接口，不受令牌保护
    assert anonymous.get("/health").status_code == 200
