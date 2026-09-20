"""AgentDesk LLM 供应商冒烟测试：验证新增 GLM (glm-5.3-flash) 等供应商与 AI Agent 数据处理链路。

用法（Windows，在仓库根目录）:
  python scripts/agent-llm-smoke.py --direct            # 直连 LLM 供应商，验证密钥/地址/模型/JSON 输出
  python scripts/agent-llm-smoke.py --agent             # 端到端验证本机 Agent 服务（需先启动 uvicorn）
  python scripts/agent-llm-smoke.py                     # 两者都跑

默认从根目录 .env 读取 GLM_API_KEY / GLM_BASE_URL / GLM_MODEL / LLM_PROVIDER / AGENT_SERVICE_TOKEN；
也可用 --api-key / --base-url / --model / --provider / --token / --agent-base 覆盖。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

try:
    import httpx
except ImportError:
    print("缺少依赖 httpx，请先安装：pip install httpx（agent/requirements.txt 已包含）")
    sys.exit(2)

RESULTS: list[tuple[bool, str, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((ok, name, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  —— {detail}" if detail else ""))


def load_env() -> None:
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


def parse_chat_content(content: str) -> dict:
    text = content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text[3:]
        if text.endswith("```"):
            text = text[:-3]
    return json.loads(text.strip())


def run_direct(args: argparse.Namespace) -> None:
    base = (args.base_url or os.getenv("GLM_BASE_URL", "https://tokenrhythm.studio/v1")).rstrip("/")
    model = args.model or os.getenv("GLM_MODEL", "glm-5.3-flash")
    api_key = args.api_key or os.getenv("GLM_API_KEY", "")
    if not api_key:
        check("直连供应商：找到 API Key", False, "未提供 --api-key，且根目录 .env 中没有 GLM_API_KEY")
        return
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是测试助手。必须只输出 JSON。"},
            {"role": "user", "content": '请输出 JSON：{"ok": true, "greeting": "你好"}'},
        ],
        "temperature": 0.1,
        "max_tokens": 200,
    }
    try:
        response = httpx.post(f"{base}/chat/completions", headers={"Authorization": f"Bearer {api_key}"}, json=payload, timeout=args.timeout)
    except Exception as exc:
        check(f"直连供应商：{base}/chat/completions 可达", False, str(exc))
        return
    check(f"直连供应商：HTTP 200（{model}）", response.status_code == 200, "" if response.status_code == 200 else f"status={response.status_code} body={response.text[:200]}")
    if response.status_code != 200:
        return
    content = response.json().get("choices", [{}])[0].get("message", {}).get("content", "")
    check("直连供应商：返回内容非空", bool(content))
    try:
        data = parse_chat_content(content)
        check("直连供应商：输出为 JSON 对象", isinstance(data, dict), json.dumps(data, ensure_ascii=False)[:120])
    except Exception as exc:
        check("直连供应商：输出为 JSON 对象", False, str(exc))


EVIDENCE = [
    {"citation": "[KB-1] v2", "articleId": 1, "versionId": 1, "title": "VPN 认证超时处理指南", "snippet": "先清理客户端缓存，然后重新配置 VPN 认证端口 443，并在防火墙放行后重试。"},
    {"citation": "[KB-2] v1", "articleId": 2, "versionId": 3, "title": "远程接入常见问题", "snippet": "认证超时通常与系统代理有关，建议关闭代理后重新连接。"},
]


def _post_json(url: str, headers: dict, payload: dict, name: str, timeout: float) -> dict | None:
    try:
        response = httpx.post(url, headers=headers, json=payload, timeout=timeout)
    except Exception as exc:
        check(name, False, str(exc))
        return None
    if response.status_code != 200:
        check(name, False, f"status={response.status_code} body={response.text[:200]}")
        return None
    return response.json()


def run_agent(args: argparse.Namespace) -> None:
    base = args.agent_base.rstrip("/")
    headers = {"X-Agent-Token": args.token} if args.token else {}
    provider = (args.provider or os.getenv("LLM_PROVIDER") or "").strip().lower() or None
    body_provider = {"provider": provider} if provider else {}
    expected_source = provider.upper() if provider else None

    try:
        health = httpx.get(f"{base}/health", timeout=10)
        check("Agent 服务可达（/health）", health.status_code == 200, str(health.json().get("model", "")))
    except Exception as exc:
        check("Agent 服务可达（/health）", False, f"{exc}；请先启动: cd agent && uvicorn app.main:app --port 8000")
        return

    try:
        providers = httpx.get(f"{base}/v1/providers", headers=headers, timeout=10).json()
        names = [p["key"] for p in providers.get("providers", [])]
        check("供应商列表（/v1/providers）", bool(names), f"configured={names} default={providers.get('default')}")
    except Exception as exc:
        check("供应商列表（/v1/providers）", False, str(exc))

    payload = {"title": "VPN 无法连接，认证一直超时", "description": "员工反馈在家办公时 VPN 客户端认证超时，影响远程办公，重启路由器无效。", **body_provider}
    body = _post_json(f"{base}/v1/classify", headers, payload, "工单分类（/v1/classify）", args.timeout)
    if body:
        check("分类：source 来自 LLM（非规则兜底）", body.get("source") not in (None, "RULE_FALLBACK"), f"source={body.get('source')} failureReason={body.get('failureReason')}")
        if expected_source:
            check(f"分类：source 为 {expected_source}", body.get("source") == expected_source, f"实际 {body.get('source')}")
        check("分类：category 在白名单", body.get("category") in {"NETWORK", "ACCESS", "SOFTWARE", "GENERAL"}, str(body.get("category")))
        check("分类：priority 为 P1-P4", body.get("priority") in {"P1", "P2", "P3", "P4"}, str(body.get("priority")))
        check("分类：queueCode 在白名单", body.get("queueCode") in {"NETWORK", "ACCESS", "SOFTWARE", "GENERAL"}, str(body.get("queueCode")))
        check("分类：置信度在 0-1", isinstance(body.get("confidence"), (int, float)) and 0 <= body["confidence"] <= 1, str(body.get("confidence")))
        check("分类：给出摘要与下一步", bool(body.get("summary")) and bool(body.get("nextStep")))

    payload = {"question": "VPN 认证超时如何处理？", "knowledgeContext": EVIDENCE, **body_provider}
    body = _post_json(f"{base}/v1/retrieve-draft", headers, payload, "带引用回复（/v1/retrieve-draft）", args.timeout)
    if body:
        check("回复：给出草稿内容", bool(body.get("draft")), str(body.get("failureReason")))
        check("回复：保留知识引用", (len(body.get("citations", [])) > 0) if body.get("ok") else True, f"{len(body.get('citations', []))} 条")

    payload = {"question": "VPN 认证超时如何处理？", "knowledgeContext": EVIDENCE, **body_provider}
    body = _post_json(f"{base}/v1/answer", headers, payload, "知识问答：有证据时回答（/v1/answer）", args.timeout)
    if body:
        check("问答：给出回答", bool(body.get("answer")), f"source={body.get('source')}")

    payload = {"question": "公司今年年会抽奖预算是多少？"}
    body = _post_json(f"{base}/v1/answer", headers, payload, "知识问答：无证据拒答", args.timeout)
    if body:
        check("拒答：无证据时 ok=false", body.get("ok") is False, f"answer={str(body.get('answer'))[:60]}")
        check("拒答：不编造引用", body.get("citations") == [])

    md = "# 远程办公 VPN 配置指南\n\n1. 下载客户端并使用域账号登录。\n2. 认证超时时检查 443 端口与系统代理设置。\n3. 连接后内网不通时切换隧道模式。\n"
    files = {"file": ("vpn-guide.md", md.encode("utf-8"), "text/markdown")}
    data = {"provider": provider} if provider else None
    try:
        response = httpx.post(f"{base}/v1/analyze-document", headers=headers, files=files, data=data, timeout=args.timeout)
        check("文档分析（/v1/analyze-document）", response.status_code == 200, "" if response.status_code == 200 else f"status={response.status_code}")
        if response.status_code == 200:
            body = response.json()
            check("文档分析：生成标题与摘要", bool(body.get("title")) and bool(body.get("summary")), f"source={body.get('source')} title={str(body.get('title'))[:40]}")
            check("文档分析：source 来自 LLM", body.get("source") not in (None, "RULE_FALLBACK"), str(body.get("source")))
    except Exception as exc:
        check("文档分析（/v1/analyze-document）", False, str(exc))


def main() -> int:
    parser = argparse.ArgumentParser(description="AgentDesk LLM 供应商冒烟测试")
    parser.add_argument("--direct", action="store_true", help="直连 LLM 供应商验证")
    parser.add_argument("--agent", action="store_true", help="端到端验证 Agent 服务")
    parser.add_argument("--provider", default="", help="指定供应商 key（glm/deepseek），默认取 LLM_PROVIDER 环境变量")
    parser.add_argument("--agent-base", default=os.getenv("AGENT_BASE_URL", "http://localhost:8000"), help="Agent 服务地址")
    parser.add_argument("--token", default="", help="X-Agent-Token，默认取 AGENT_SERVICE_TOKEN")
    parser.add_argument("--base-url", default="", help="直连模式供应商 Base URL，默认取 GLM_BASE_URL")
    parser.add_argument("--model", default="", help="直连模式模型名，默认取 GLM_MODEL")
    parser.add_argument("--api-key", default="", help="直连模式 API Key，默认取 GLM_API_KEY")
    parser.add_argument("--timeout", type=float, default=90.0)
    args = parser.parse_args()
    load_env()
    if not args.direct and not args.agent:
        args.direct = True
        args.agent = True

    print(f"== AgentDesk LLM 冒烟测试 == {datetime.now():%Y-%m-%d %H:%M:%S}")
    if args.direct:
        run_direct(args)
    if args.agent:
        run_agent(args)

    failed = [r for r in RESULTS if not r[0]]
    print(f"\n共 {len(RESULTS)} 项：通过 {len(RESULTS) - len(failed)}，失败 {len(failed)}")
    for _, name, detail in failed:
        print(f"  FAIL {name}: {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
