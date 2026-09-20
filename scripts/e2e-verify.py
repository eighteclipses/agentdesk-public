"""End-to-end verification against the running docker stack."""
import json
import urllib.request
import http.client

BASE = "http://localhost:8080"
AGENT = "http://localhost:8000"
cookies = {}


def req(method, url, body=None, headers=None, use_cookies=True):
    h = dict(headers or {})
    if body is not None:
        h.setdefault("Content-Type", "application/json")
        data = json.dumps(body).encode()
    else:
        data = None
    if use_cookies and cookies:
        h["Cookie"] = "; ".join(f"{k}={v}" for k, v in cookies.items())
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            for line in resp.headers.get_all("Set-Cookie") or []:
                k, _, rest = line.partition("=")
                cookies[k.strip()] = rest.split(";")[0]
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def check(label, cond):
    print(("PASS" if cond else "FAIL"), "-", label)
    return cond


results = []

# 1. login
st, body = req("POST", BASE + "/api/auth/login", {"username": "admin", "password": "AgentDesk@2026"})
results.append(check("login returns 200 + cookie", st == 200 and "agentdesk_token" in cookies))

# 2. no-cookie protected endpoint -> 401
st, _ = req("GET", BASE + "/api/tickets", use_cookies=False)
results.append(check("no-cookie request -> 401", st == 401))

# 3. chinese search returns published articles
st, body = req("GET", BASE + "/api/knowledge/search?q=" + urllib.request.quote("VPN"))
hits = json.loads(body) if st == 200 else []
results.append(check("search 'VPN' -> 200 with hits", st == 200 and isinstance(hits, list) and len(hits) >= 1))
if hits:
    c = hits[0]
    results.append(check("hit has clickable citation article:x/version:y#cN", "article:" in c.get("citation", "")))

# 4. CJK multi-word search
st, body = req("GET", BASE + "/api/knowledge/search?q=" + urllib.request.quote("网络连通性"))
results.append(check("search '网络连通性' -> 200", st == 200))

# 5. knowledge pagination
st, body = req("GET", BASE + "/api/knowledge?page=1&size=5")
d = json.loads(body) if st == 200 else {}
results.append(check("knowledge pagination items+total", st == 200 and "items" in d and "total" in d))

# 6. tickets pagination
st, body = req("GET", BASE + "/api/tickets?page=1&size=3")
d = json.loads(body) if st == 200 else {}
results.append(check("tickets pagination total>=1", st == 200 and d.get("total", 0) >= 1))

# 7. audit pagination
st, body = req("GET", BASE + "/api/audit?page=1&size=5")
d = json.loads(body) if st == 200 else {}
results.append(check("audit pagination total>=1", st == 200 and d.get("total", 0) >= 1))

# 8. agent service token enforcement：agent 端口不再发布到宿主机（安全加固），
#    通过 backend 容器在 docker 网络内验证
import subprocess

def docker_agent_status(path, token=None):
    curl = f"curl -s -o /dev/null -w '%{{http_code}}' http://agent:8000{path}"
    if token:
        curl = f"curl -s -o /dev/null -w '%{{http_code}}' -H 'X-Agent-Token: {token}' http://agent:8000{path}"
    r = subprocess.run(["docker", "exec", "agentdesk-backend-1", "sh", "-c", curl],
                       capture_output=True, text=True, timeout=30)
    return r.stdout.strip()

def docker_agent_body(path, token=None):
    curl = f"curl -s http://agent:8000{path}"
    if token:
        curl = f"curl -s -H 'X-Agent-Token: {token}' http://agent:8000{path}"
    r = subprocess.run(["docker", "exec", "agentdesk-backend-1", "sh", "-c", curl],
                       capture_output=True, text=True, timeout=30)
    return r.stdout

results.append(check("agent /v1/providers no-token -> 401 (fail-closed)", docker_agent_status("/v1/providers") == "401"))
results.append(check("agent /health open -> 200", docker_agent_status("/health") == "200"))
results.append(check("agent wrong token -> 401", docker_agent_status("/v1/providers", token="wrong-token") == "401"))
import os
correct_token = None
with open(os.path.join(os.path.dirname(__file__), "..", ".env"), encoding="utf-8") as f:
    for line in f:
        if line.startswith("AGENT_SERVICE_TOKEN="):
            correct_token = line.split("=", 1)[1].strip()
results.append(check("agent correct token -> 200", correct_token is not None and docker_agent_status("/v1/providers", token=correct_token) == "200"))

# 9. streaming ask (SSE)
st, body = req("POST", BASE + "/api/knowledge/ask/stream", {"question": "VPN 认证超时怎么处理", "scope": "ENTERPRISE"})
results.append(check("ask/stream -> 200 with SSE frames", st == 200 and "event:" in body))

# 10. retract endpoint exists (admin) on a published article
st, body = req("GET", BASE + "/api/knowledge?page=1&size=50")
arts = json.loads(body).get("items", []) if st == 200 else []
pub = [a for a in arts if a.get("status") == "PUBLISHED"]
if pub:
    aid = pub[0]["id"]
    st, body = req("POST", BASE + f"/api/knowledge/articles/{aid}/retract")
    results.append(check(f"retract article {aid} -> 200", st == 200))
    # re-publish via review approve to restore state
    st, body = req("POST", BASE + f"/api/knowledge/articles/{aid}/review", {"approve": True, "comment": "restore after e2e"})
    results.append(check(f"re-publish article {aid} -> 200", st == 200))
else:
    print("SKIP - no published article to test retract")

print()
print(f"TOTAL: {sum(results)}/{len(results)} passed")
raise SystemExit(0 if all(results) else 1)
