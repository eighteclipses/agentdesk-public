#!/usr/bin/env python3
"""Real HTTP verification for the isolated AgentDesk functional stack.

This script is intentionally separate from application code.  It talks to the
frontend API proxy using real cookie sessions and uses docker/psql only to
create uniquely named QA fixtures in the explicitly checked ``agentdesk_test``
database.  It never reads ``.env`` and never calls an LLM or paid API.

Example (PowerShell):
  python scripts\knowledge-http-verify.py `
    --base http://127.0.0.1:15179/api `
    --isolated http://127.0.0.1:18089 `
    --container agentdesk-functional-20260922-postgres `
    --keep-fixtures
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import http.cookiejar
import ipaddress
import json
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
VERIFY_OUTPUT_DIR = ROOT / "verification-output"
DEFAULT_CONTAINER = "agentdesk-functional-postgres"
CONTAINER_PREFIX = "agentdesk-functional-"
DATABASE = "agentdesk_test"
DB_USER = "agentdesk"
QA_PASSWORD = secrets.token_urlsafe(18)
PASSWORD_ITERATIONS = 120_000


def _password_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS, dklen=32)
    return "pbkdf2${}${}${}".format(
        PASSWORD_ITERATIONS,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(key).decode("ascii"),
    )


QA_PASSWORD_HASH = _password_hash(QA_PASSWORD)
HTTP_TIMEOUT = 20.0
IMPORT_TIMEOUT = 120.0
POLL_INTERVAL = 1.0


class VerifyFailure(RuntimeError):
    pass


class Logger:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = self.path.open("w", encoding="utf-8")

    def write(self, message: str) -> None:
        line = message.rstrip("\n")
        print(line, flush=True)
        self._handle.write(line + "\n")
        self._handle.flush()

    def close(self) -> None:
        self._handle.close()


@dataclass
class HttpResponse:
    status: int
    headers: Any
    body: bytes


class HttpSession:
    """Small standard-library HTTP client with a real cookie jar."""

    def __init__(self, base_url: str, label: str, logger: Logger):
        self.base_url = base_url.rstrip("/")
        self.label = label
        self.logger = logger
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def _url(self, path: str) -> str:
        if not path.startswith("/"):
            path = "/" + path
        return self.base_url + path

    def request(self, method: str, path: str, *, body: bytes | None = None, content_type: str | None = None, headers: dict[str, str] | None = None) -> HttpResponse:
        parsed_origin = urllib.parse.urlsplit(self.base_url)
        request_headers = {"Accept": "application/json", "Origin": f"{parsed_origin.scheme}://{parsed_origin.netloc}"}
        if headers:
            request_headers.update(headers)
        if content_type:
            request_headers["Content-Type"] = content_type
        request = urllib.request.Request(self._url(path), data=body, headers=request_headers, method=method.upper())
        try:
            with self.opener.open(request, timeout=HTTP_TIMEOUT) as response:
                result = HttpResponse(int(response.status), response.headers, response.read())
        except urllib.error.HTTPError as error:
            result = HttpResponse(int(error.code), error.headers, error.read())
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise VerifyFailure(f"{self.label} {method} {path} network error: {_safe_error(error)}") from error
        self.logger.write(f"HTTP {self.label} {method.upper()} {path} -> {result.status}")
        return result

    def json(self, method: str, path: str, value: Any | None = None) -> tuple[HttpResponse, Any | None]:
        body = None if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
        response = self.request(method, path, body=body, content_type="application/json" if body is not None else None)
        if not response.body:
            return response, None
        try:
            return response, json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return response, None


def _safe_error(error: Any, limit: int = 500) -> str:
    text = str(error or "")
    text = re.sub(r"(?i)(authorization\s*[:=]\s*bearer\s+)[^\s,;]+", r"\1[REDACTED]", text)
    text = re.sub(r"(?i)(agentdesk_token\s*[=:]\s*)[^;\s]+", r"\1[REDACTED]", text)
    return text[:limit]


def _json_body(response: HttpResponse) -> Any:
    try:
        return json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def _body_preview(response: HttpResponse) -> str:
    body = response.body.decode("utf-8", errors="replace")
    return _safe_error(body, 800).replace("\n", " ")


def _require_status(response: HttpResponse, expected: int | set[int], label: str) -> Any:
    expected_set = {expected} if isinstance(expected, int) else expected
    if response.status not in expected_set:
        raise VerifyFailure(f"{label}: expected HTTP {sorted(expected_set)}, got {response.status}: {_body_preview(response)}")
    return _json_body(response)


def _check_url_loopback(value: str, label: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise VerifyFailure(f"{label} must be an http(s) URL with a hostname")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise VerifyFailure(f"{label} must not contain userinfo, query parameters, or fragments")
    host = parsed.hostname.lower().rstrip(".")
    is_loopback = host == "localhost"
    if not is_loopback:
        try:
            is_loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            is_loopback = False
    if not is_loopback:
        raise VerifyFailure(f"{label} must resolve to loopback (got {host})")
    return value.rstrip("/")


def _valid_container(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", value) or not value.startswith(CONTAINER_PREFIX):
        raise VerifyFailure(f"--container must be a Docker name under fixed prefix {CONTAINER_PREFIX!r}")
    return value


def _psql(container: str, sql: str) -> str:
    command = ["docker", "exec", container, "psql", "-U", DB_USER, "-d", DATABASE, "-At", "-v", "ON_ERROR_STOP=1", "-c", sql]
    try:
        completed = subprocess.run(command, check=False, capture_output=True, text=True, encoding="utf-8", timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerifyFailure(f"docker/psql invocation failed: {_safe_error(error)}") from error
    if completed.returncode != 0:
        raise VerifyFailure(f"docker/psql failed: {_safe_error(completed.stderr)}")
    return completed.stdout.strip()


def _sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _bootstrap_fixtures(container: str, tag: str, logger: Logger) -> dict[str, Any]:
    database = _psql(container, "SELECT current_database()")
    if database != DATABASE:
        raise VerifyFailure(f"refusing fixture writes: container database is {database!r}, expected {DATABASE!r}")
    department = _psql(container, "SELECT id FROM departments ORDER BY id LIMIT 1")
    if not department.isdigit():
        raise VerifyFailure("refusing fixture writes: no existing department was found")
    department_id = int(department)
    password_hash = _sql_literal(QA_PASSWORD_HASH)
    specs = [
        (f"qa_admin_{tag}", f"QA Admin {tag}", "ADMIN"),
        (f"qa_employee_{tag}", f"QA Employee {tag}", "EMPLOYEE"),
        (f"qa_agent_{tag}", f"QA Agent {tag}", "AGENT"),
    ]
    users: dict[str, dict[str, Any]] = {}
    for username, display_name, role in specs:
        sql = (
            "INSERT INTO users(username,display_name,department_id,password_hash,account_status,force_password_change) "
            f"VALUES ({_sql_literal(username)},{_sql_literal(display_name)},{department_id},{password_hash},'ACTIVE',false) RETURNING id"
        )
        raw_id = _psql(container, sql)
        id_match = re.search(r"(?m)^\s*(\d+)\s*$", raw_id)
        if not id_match:
            raise VerifyFailure(f"fixture insert did not return a user id for {username}")
        user_id = int(id_match.group(1))
        role_sql = (
            "INSERT INTO user_roles(user_id,role_id) SELECT "
            f"{user_id},id FROM roles WHERE code={_sql_literal(role)} ON CONFLICT DO NOTHING"
        )
        _psql(container, role_sql)
        users[role.lower()] = {"id": user_id, "username": username, "display_name": display_name, "role": role}
    agent_id = users["agent"]["id"]
    _psql(container, f"INSERT INTO queue_members(queue_id,user_id) SELECT id,{agent_id} FROM support_queues WHERE code='NETWORK' ON CONFLICT DO NOTHING")
    _psql(container, f"INSERT INTO queue_members(queue_id,user_id) SELECT id,{agent_id} FROM support_queues WHERE code='GENERAL' ON CONFLICT DO NOTHING")
    fixture = {
        "tag": tag,
        "database": DATABASE,
        "container": container,
        "department_id": department_id,
        "password": QA_PASSWORD,
        "users": users,
    }
    logger.write("FIXTURE CREATED: three ACTIVE users with ADMIN/EMPLOYEE/AGENT roles; AGENT is in NETWORK and GENERAL")
    return fixture


def _write_fixture_file(fixture: dict[str, Any], tag: str) -> Path:
    VERIFY_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    path = VERIFY_OUTPUT_DIR / f"knowledge-http-fixtures-{tag}.json"
    path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def _multipart_files(fields: dict[str, str | list[str]], file_field: str, files: list[tuple[str, bytes, str]]) -> tuple[bytes, str]:
    boundary = "----AgentDeskVerify" + uuid.uuid4().hex
    chunks: list[bytes] = []
    for name, value in fields.items():
        values = value if isinstance(value, list) else [value]
        for one_value in values:
            chunks.extend([
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                str(one_value).encode("utf-8"),
                b"\r\n",
            ])
    for filename, content, content_type in files:
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'.encode(),
            f"Content-Type: {content_type}\r\n\r\n".encode(),
            content,
            b"\r\n",
        ])
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def _multipart(fields: dict[str, str], file_field: str, filename: str, content: bytes, content_type: str) -> tuple[bytes, str]:
    return _multipart_files(fields, file_field, [(filename, content, content_type)])


def _query_path(path: str, **params: Any) -> str:
    encoded = urllib.parse.urlencode({key: value for key, value in params.items() if value is not None})
    return path + ("?" + encoded if encoded else "")


def _wait_import(session: HttpSession, batch_id: int, item_id: int, logger: Logger) -> dict[str, Any]:
    deadline = time.monotonic() + IMPORT_TIMEOUT
    previous = ""
    while time.monotonic() < deadline:
        response = session.request("GET", f"/admin/imports/{batch_id}")
        body = _require_status(response, 200, "poll import batch")
        if not isinstance(body, dict):
            raise VerifyFailure("poll import batch returned a non-object")
        items = body.get("items") or []
        item = next((candidate for candidate in items if int(candidate.get("id", -1)) == item_id), None)
        if not isinstance(item, dict):
            raise VerifyFailure(f"import item {item_id} was not returned in batch {batch_id}")
        status = str(item.get("status"))
        if status != previous:
            logger.write(f"IMPORT ITEM {item_id}: status={status} progress={item.get('progress')}")
            previous = status
        if status in {"REVIEW", "FAILED", "DUPLICATE", "CANCELED"}:
            return item
        time.sleep(POLL_INTERVAL)
    raise VerifyFailure(f"import item {item_id} did not reach a terminal state within {IMPORT_TIMEOUT:g}s")


def _login(base: str, logger: Logger, role: str, username: str, password: str) -> HttpSession:
    session = HttpSession(base, role, logger)
    response, body = session.json("POST", "/auth/login", {"username": username, "password": password})
    _require_status(response, 200, f"{role} login")
    if not isinstance(body, dict) or body.get("username") != username:
        raise VerifyFailure(f"{role} login returned an unexpected user payload")
    logger.write(f"LOGIN OK: {role} username={username} role={body.get('role')}")
    return session


def _create_knowledge_flow(admin: HttpSession, employee: HttpSession, tag: str, logger: Logger) -> dict[str, Any]:
    title = f"QA 手工版本隔离 {tag}"
    content_v1 = f"# {title}\n\nVPN v1：先关闭系统代理，再检查 TCP 443 端口。测试标签 {tag}。\n"
    response, body = admin.json("POST", "/knowledge", {"title": title, "content": content_v1, "category": "NETWORK", "tags": ["qa-http", tag], "visibility": "PUBLIC", "departmentIds": [], "sensitivity": "INTERNAL"})
    article = _require_status(response, 200, "admin create knowledge")
    if not isinstance(article, dict) or not isinstance(article.get("id"), int):
        raise VerifyFailure("knowledge create did not return an article id")
    article_id = int(article["id"])
    versions_response = admin.request("GET", f"/knowledge/articles/{article_id}/versions")
    versions = _require_status(versions_response, 200, "admin list initial versions")
    if not isinstance(versions, list) or len(versions) != 1:
        raise VerifyFailure("new knowledge article did not expose exactly one initial version to admin")
    version1_id = int(versions[0]["id"])
    publish_response = admin.request("POST", f"/knowledge/{article_id}/publish")
    _require_status(publish_response, 200, "publish initial knowledge")

    employee_detail_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_detail = _require_status(employee_detail_response, 200, "employee reads published knowledge")
    if employee_detail.get("content") != content_v1 or int(employee_detail.get("versionId")) != version1_id:
        raise VerifyFailure("employee did not read the initial published version")
    employee_versions_response = employee.request("GET", f"/knowledge/articles/{article_id}/versions")
    employee_versions = _require_status(employee_versions_response, 200, "employee lists published versions")
    if not isinstance(employee_versions, list) or len(employee_versions) != 1 or int(employee_versions[0]["id"]) != version1_id:
        raise VerifyFailure("employee version list exposed hidden draft/history")

    content_v2 = f"# {title}\n\nVPN v2 draft：先关闭代理，再检查 TCP 443，并记录客户端版本。测试标签 {tag}。\n"
    update_response, update_body = admin.json("PUT", f"/knowledge/articles/{article_id}", {"content": content_v2, "category": "NETWORK", "visibility": "PUBLIC", "sensitivity": "INTERNAL", "tags": ["qa-http", tag], "departmentIds": []})
    _require_status(update_response, 200, "admin create knowledge draft")
    if not isinstance(update_body, dict) or not int(update_body.get("newVersionId", 0)):
        raise VerifyFailure("knowledge update did not return newVersionId")
    version2_id = int(update_body["newVersionId"])
    employee_draft_response = employee.request("GET", _query_path(f"/knowledge/articles/{article_id}", versionId=version2_id))
    if employee_draft_response.status != 404:
        raise VerifyFailure(f"employee could read draft version {version2_id}; got HTTP {employee_draft_response.status}")
    employee_current_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_current = _require_status(employee_current_response, 200, "employee keeps reading old version during draft")
    if int(employee_current.get("versionId")) != version1_id or employee_current.get("content") != content_v1:
        raise VerifyFailure("employee current read changed before draft review")
    admin_versions_response = admin.request("GET", f"/knowledge/articles/{article_id}/versions")
    admin_versions = _require_status(admin_versions_response, 200, "admin lists draft version")
    if not any(int(row.get("id")) == version2_id and row.get("status") == "DRAFT" for row in admin_versions):
        raise VerifyFailure("admin could not see the newly created draft version")

    reject_response, _ = admin.json("POST", f"/knowledge/articles/{article_id}/review", {"approve": False, "comment": "QA reject draft and retain published version", "versionId": version2_id})
    _require_status(reject_response, 200, "reject exact draft version")
    reject_check = employee.request("GET", f"/knowledge/articles/{article_id}")
    reject_detail = _require_status(reject_check, 200, "employee reads retained version after reject")
    if int(reject_detail.get("versionId")) != version1_id:
        raise VerifyFailure("rejecting draft did not retain old published version")

    content_v3 = f"# {title}\n\nVPN v3 published：关闭系统代理，检查 TCP 443，联系网络服务台。测试标签 {tag}。\n"
    update3_response, update3_body = admin.json("PUT", f"/knowledge/articles/{article_id}", {"content": content_v3, "category": "NETWORK", "visibility": "PUBLIC", "sensitivity": "INTERNAL", "tags": ["qa-http", tag], "departmentIds": []})
    _require_status(update3_response, 200, "admin create second draft")
    version3_id = int(update3_body["newVersionId"])
    approve3_response, _ = admin.json("POST", f"/knowledge/articles/{article_id}/review", {"approve": True, "comment": "QA publish exact version", "versionId": version3_id})
    _require_status(approve3_response, 200, "approve exact second draft")
    employee_v3_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_v3 = _require_status(employee_v3_response, 200, "employee reads switched published version")
    if int(employee_v3.get("versionId")) != version3_id or employee_v3.get("content") != content_v3:
        raise VerifyFailure("employee did not read the newly approved version")
    old_version_response = employee.request("GET", _query_path(f"/knowledge/articles/{article_id}", versionId=version1_id))
    if old_version_response.status != 404:
        raise VerifyFailure(f"employee could read archived old version {version1_id}; got HTTP {old_version_response.status}")

    # Leave a published article with a new draft for browser/UI acceptance.
    content_v4 = f"# {title}\n\nVPN v4 pending draft：仅供 UI 审核验收。测试标签 {tag}。\n"
    update4_response, update4_body = admin.json("PUT", f"/knowledge/articles/{article_id}", {"content": content_v4, "category": "NETWORK", "visibility": "PUBLIC", "sensitivity": "INTERNAL", "tags": ["qa-http", tag], "departmentIds": []})
    _require_status(update4_response, 200, "leave published article with pending draft")
    version4_id = int(update4_body["newVersionId"])
    employee_after_draft_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_after_draft = _require_status(employee_after_draft_response, 200, "employee reads published content while UI draft is pending")
    if int(employee_after_draft.get("versionId")) != version3_id:
        raise VerifyFailure("pending UI draft replaced the published employee version")
    logger.write(f"KNOWLEDGE FLOW OK: article={article_id}, publishedVersion={version3_id}, pendingDraft={version4_id}, rejectedVersion={version2_id}")
    return {"article_id": article_id, "version1_id": version1_id, "rejected_version_id": version2_id, "published_version_id": version3_id, "pending_draft_id": version4_id}


def _upload_markdown(admin: HttpSession, content: bytes, filename: str, strategy: str, tag: str) -> tuple[int, int, dict[str, Any]]:
    body, content_type = _multipart({"name": f"QA Markdown {tag}", "duplicateStrategy": strategy, "sourcePaths": f"qa/{filename}"}, "files", filename, content, "text/markdown")
    response = admin.request("POST", "/admin/imports", body=body, content_type=content_type)
    payload = _require_status(response, 200, f"create {strategy} import batch")
    if not isinstance(payload, dict) or not isinstance(payload.get("batchId"), int) or not payload.get("itemIds"):
        raise VerifyFailure(f"import create returned unexpected payload: {payload!r}")
    return int(payload["batchId"]), int(payload["itemIds"][0]), payload


def _create_import_flow(admin: HttpSession, employee: HttpSession, tag: str, logger: Logger) -> dict[str, Any]:
    filename = f"qa-import-{tag}.md"
    content = f"# QA Imported Article {tag}\n\nThis synthetic Markdown is parsed by the real Agent service and stored in MinIO.\n\n- Keep TCP 443 open for VPN.\n- Contact the network service desk when authentication still times out.\n"
    raw = content.encode("utf-8")
    batch1, item1, _ = _upload_markdown(admin, raw, filename, "skip", tag)
    item1_body = _wait_import(admin, batch1, item1, logger)
    if item1_body.get("status") != "REVIEW" or not item1_body.get("articleId"):
        raise VerifyFailure(f"first import did not reach REVIEW: {item1_body!r}")
    article_id = int(item1_body["articleId"])
    item_file_response = admin.request("GET", f"/admin/imports/items/{item1}/file")
    _require_status(item_file_response, 200, "download first imported original")
    if item_file_response.body != raw:
        raise VerifyFailure("downloaded first imported original does not match uploaded Markdown")
    versions_response = admin.request("GET", f"/knowledge/articles/{article_id}/versions")
    versions = _require_status(versions_response, 200, "list first imported version")
    if not isinstance(versions, list) or not versions:
        raise VerifyFailure("first import has no article version")
    published_version_id = int(max(versions, key=lambda row: int(row.get("version", 0)))["id"])
    review1_response, _ = admin.json("POST", f"/knowledge/articles/{article_id}/review", {"approve": True, "comment": "QA publish imported baseline", "versionId": published_version_id})
    _require_status(review1_response, 200, "publish first imported version")
    employee_imported_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_imported = _require_status(employee_imported_response, 200, "employee reads published imported article")
    if int(employee_imported.get("versionId")) != published_version_id:
        raise VerifyFailure("employee did not read first imported published version")

    batch2, item2, _ = _upload_markdown(admin, raw, filename, "version", tag)
    item2_body = _wait_import(admin, batch2, item2, logger)
    if item2_body.get("status") != "REVIEW" or int(item2_body.get("articleId", -1)) != article_id:
        raise VerifyFailure(f"duplicateStrategy=version did not create a review draft on same article: {item2_body!r}")
    versions2_response = admin.request("GET", f"/knowledge/articles/{article_id}/versions")
    versions2 = _require_status(versions2_response, 200, "list duplicate version draft")
    draft_versions = [row for row in versions2 if row.get("status") == "DRAFT"]
    if not draft_versions:
        raise VerifyFailure("duplicateStrategy=version produced no DRAFT version")
    draft_version_id = int(max(draft_versions, key=lambda row: int(row.get("version", 0)))["id"])
    employee_review_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_review = _require_status(employee_review_response, 200, "employee reads old imported article during review")
    if int(employee_review.get("versionId")) != published_version_id:
        raise VerifyFailure("employee switched to imported draft while it was in REVIEW")
    employee_file_response = employee.request("GET", f"/knowledge/articles/{article_id}/file")
    _require_status(employee_file_response, 200, "employee downloads old imported original during review")
    if employee_file_response.body != raw:
        raise VerifyFailure("employee original download during review did not match the published source")
    draft_read_response = employee.request("GET", _query_path(f"/knowledge/articles/{article_id}", versionId=draft_version_id))
    if draft_read_response.status != 404:
        raise VerifyFailure(f"employee could explicitly read import draft {draft_version_id}; got HTTP {draft_read_response.status}")
    review2_response, _ = admin.json("POST", f"/knowledge/articles/{article_id}/review", {"approve": True, "comment": "QA approve requested import version", "versionId": draft_version_id})
    _require_status(review2_response, 200, "approve specified imported version")
    employee_new_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_new = _require_status(employee_new_response, 200, "employee reads approved imported version")
    if int(employee_new.get("versionId")) != draft_version_id:
        raise VerifyFailure("employee did not switch to the explicitly approved imported version")
    logger.write(f"IMPORT FLOW OK: article={article_id}, item1={item1}, publishedVersion={published_version_id}, item2={item2}, approvedVersion={draft_version_id}")
    return {"article_id": article_id, "item1_id": item1, "item2_id": item2, "batch1_id": batch1, "batch2_id": batch2, "published_version_id": published_version_id, "approved_version_id": draft_version_id}


def _create_same_batch_version_flow(admin: HttpSession, employee: HttpSession, tag: str, logger: Logger) -> dict[str, Any]:
    """Verify same-SHA files in one version batch are serialized and bulk published safely."""
    content = (f"# QA Same Batch Version {tag}\n\n"
               f"Unique synthetic content for same-SHA concurrency verification: {tag}.\n"
               "The two file names intentionally differ but their bytes are identical.\n").encode("utf-8")
    filename_a = f"qa-same-batch-a-{tag}.md"
    filename_b = f"qa-same-batch-b-{tag}.md"
    body, content_type = _multipart_files(
        {"name": f"QA same batch version {tag}", "duplicateStrategy": "version", "sourcePaths": [f"qa/{filename_a}", f"qa/{filename_b}"]},
        "files",
        [(filename_a, content, "text/markdown"), (filename_b, content, "text/markdown")],
    )
    response = admin.request("POST", "/admin/imports", body=body, content_type=content_type)
    payload = _require_status(response, 200, "create same-batch version import")
    if not isinstance(payload, dict) or not isinstance(payload.get("batchId"), int) or len(payload.get("itemIds") or []) != 2:
        raise VerifyFailure(f"same-batch import did not return exactly two item ids: {payload!r}")
    batch_id = int(payload["batchId"])
    item_ids = [int(value) for value in payload["itemIds"]]
    items = [_wait_import(admin, batch_id, item_id, logger) for item_id in item_ids]
    if any(item.get("status") != "REVIEW" for item in items):
        raise VerifyFailure(f"same-batch same-SHA import did not produce two REVIEW items: {items!r}")
    article_ids = {int(item.get("articleId", -1)) for item in items}
    if len(article_ids) != 1 or -1 in article_ids:
        raise VerifyFailure(f"same-batch version import created different or missing articles: {article_ids!r}")
    article_id = next(iter(article_ids))
    versions_response = admin.request("GET", f"/knowledge/articles/{article_id}/versions")
    versions = _require_status(versions_response, 200, "list same-batch versions before bulk publish")
    if not isinstance(versions, list) or len(versions) < 2:
        raise VerifyFailure(f"same-batch import did not produce two article versions: {versions!r}")
    publish_response = admin.request("POST", f"/admin/imports/batches/{batch_id}/publish")
    publish_body = _require_status(publish_response, 200, "bulk publish same-batch versions")
    if not isinstance(publish_body, dict) or int(publish_body.get("published", 0)) < 1:
        raise VerifyFailure(f"same-batch bulk publish returned no published count: {publish_body!r}")
    batch_after_response = admin.request("GET", f"/admin/imports/{batch_id}")
    batch_after = _require_status(batch_after_response, 200, "read same-batch statuses after bulk publish")
    final_items = batch_after.get("items") if isinstance(batch_after, dict) else None
    statuses = [str(item.get("status")) for item in (final_items or [])]
    if statuses.count("PUBLISHED") != 1 or statuses.count("CANCELED") != 1:
        raise VerifyFailure(f"same-batch bulk publish must leave one PUBLISHED and one CANCELED item: {statuses!r}")
    employee_response = employee.request("GET", f"/knowledge/articles/{article_id}")
    employee_article = _require_status(employee_response, 200, "employee reads latest same-batch published version")
    if int(employee_article.get("versionId", 0)) <= 0:
        raise VerifyFailure("employee could not read the same-batch published article")
    logger.write(f"SAME-BATCH VERSION FLOW OK: batch={batch_id}, article={article_id}, items={item_ids}, statuses={statuses}")
    return {"batch_id": batch_id, "article_id": article_id, "item_ids": item_ids, "statuses": statuses, "published_count": int(publish_body["published"])}


def _create_agent_flow(admin: HttpSession, employee: HttpSession, agent: HttpSession, logger: Logger) -> dict[str, Any]:
    ticket_response, ticket_body = employee.json("POST", "/tickets", {"title": "QA HTTP VPN action", "description": "Synthetic ticket used to verify queue scoped Agent action approval.", "category": "NETWORK", "priority": "P2"})
    ticket = _require_status(ticket_response, 200, "employee creates ticket")
    if not isinstance(ticket, dict) or not isinstance(ticket.get("id"), int):
        raise VerifyFailure("ticket create did not return an id")
    ticket_id = int(ticket["id"])
    action_payload = {"actionType": "TRANSFER", "ticketId": ticket_id, "payload": {"queueCode": "NETWORK", "reason": "QA transfer suggestion"}}
    action_response, action_body = admin.json("POST", "/agent/actions", action_payload)
    action = _require_status(action_response, 200, "admin creates Agent action suggestion")
    if not isinstance(action, dict) or not isinstance(action.get("id"), int) or action.get("status") != "PENDING":
        raise VerifyFailure(f"admin action suggestion was not pending: {action!r}")
    action_id = int(action["id"])
    agent_actions_response = agent.request("GET", "/agent/actions")
    agent_actions = _require_status(agent_actions_response, 200, "queue Agent lists pending actions")
    if not isinstance(agent_actions, list) or not any(int(row.get("id", -1)) == action_id for row in agent_actions):
        raise VerifyFailure("queue Agent could not see the action for NETWORK")
    employee_actions_response = employee.request("GET", "/agent/actions")
    if employee_actions_response.status != 403:
        raise VerifyFailure(f"employee action list was not forbidden; got HTTP {employee_actions_response.status}")
    approve_response = agent.request("POST", f"/agent/actions/{action_id}/approve")
    _require_status(approve_response, 200, "queue Agent approves action")
    ticket_after_response = agent.request("GET", f"/tickets/{ticket_id}")
    ticket_after = _require_status(ticket_after_response, 200, "queue Agent reads action ticket")
    if ticket_after.get("status") != "ASSIGNED":
        raise VerifyFailure(f"approved transfer did not assign ticket: {ticket_after!r}")

    # Leave a second pending action in the UI for manual approval inspection.
    pending_response, pending_body = admin.json("POST", "/agent/actions", action_payload)
    pending = _require_status(pending_response, 200, "admin creates pending UI action")
    if not isinstance(pending, dict) or pending.get("status") != "PENDING":
        raise VerifyFailure("could not leave a pending Agent action for UI acceptance")
    pending_id = int(pending["id"])
    logger.write(f"AGENT FLOW OK: ticket={ticket_id}, approvedAction={action_id}, pendingUiAction={pending_id}")
    return {"ticket_id": ticket_id, "approved_action_id": action_id, "pending_action_id": pending_id}


def _create_department_flow(admin: HttpSession, tag: str, logger: Logger) -> dict[str, Any]:
    name = f"QA Department {tag}"
    response, body = admin.json("POST", "/admin/departments", {"name": name})
    created = _require_status(response, 201, "admin creates department")
    if not isinstance(created, dict) or created.get("name") != name or not isinstance(created.get("id"), int):
        raise VerifyFailure(f"department create returned unexpected payload: {created!r}")
    logger.write(f"DEPARTMENT FLOW OK: id={created['id']} name={name}")
    return {"id": int(created["id"]), "name": name}


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run synthetic real-HTTP checks against an explicitly isolated AgentDesk stack.")
    parser.add_argument("--base", required=True, help="frontend API proxy base, for example http://127.0.0.1:15179/api")
    parser.add_argument("--isolated", required=True, help="direct backend health URL, for example http://127.0.0.1:18089")
    parser.add_argument("--container", default=DEFAULT_CONTAINER, help=f"isolated Postgres container (must start with {CONTAINER_PREFIX})")
    parser.add_argument("--keep-fixtures", action="store_true", help="explicitly retain this run's QA users/articles/ticket for browser acceptance; fixtures are retained by default")
    parser.add_argument("--skip-department", action="store_true", help="skip the V18 department creation check")
    parser.add_argument("--skip-same-batch-version", action="store_true", help="skip the same-SHA two-file version batch check (only for an older backend build)")
    parser.add_argument("--tag", default="", help="optional alphanumeric run tag; default is UTC timestamp plus random suffix")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        base = _check_url_loopback(args.base, "--base")
        isolated = _check_url_loopback(args.isolated, "--isolated")
        container = _valid_container(args.container)
        tag = args.tag or (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S") + uuid.uuid4().hex[:6])
        if not re.fullmatch(r"[A-Za-z0-9_-]{6,40}", tag):
            raise VerifyFailure("--tag must contain only letters, digits, underscore or hyphen and be 6-40 chars")
    except VerifyFailure as error:
        print(f"ARGUMENT ERROR: {error}")
        return 2

    VERIFY_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    log_path = VERIFY_OUTPUT_DIR / f"knowledge-http-verify-{tag}.log"
    logger = Logger(log_path)
    fixture: dict[str, Any] | None = None
    report: dict[str, Any] = {"tag": tag, "base": base, "isolated": isolated, "container": container, "database": DATABASE, "checks": [], "status": "FAILED"}
    try:
        logger.write(f"START knowledge HTTP verification tag={tag}")
        logger.write(f"BASE={base} ISOLATED={isolated} CONTAINER={container} DATABASE={DATABASE}")
        health = HttpSession(isolated, "isolated-health", logger).request("GET", "/actuator/health")
        health_body = _require_status(health, 200, "isolated backend health")
        if not isinstance(health_body, dict) or health_body.get("status") not in {"UP", "up"}:
            raise VerifyFailure(f"isolated backend health is not UP: {health_body!r}")
        report["checks"].append({"name": "isolated backend health", "status": "passed"})
        probe = HttpSession(base, "proxy-probe", logger).request("GET", "/auth/departments")
        departments = _require_status(probe, 200, "frontend proxy API probe")
        if not isinstance(departments, list) or not departments:
            raise VerifyFailure("frontend proxy departments probe returned no departments")
        report["checks"].append({"name": "frontend proxy API", "status": "passed"})

        fixture = _bootstrap_fixtures(container, tag, logger)
        fixture_path = _write_fixture_file(fixture, tag)
        logger.write(f"FIXTURE FILE: {fixture_path}")
        admin = _login(base, logger, "admin", fixture["users"]["admin"]["username"], QA_PASSWORD)
        employee = _login(base, logger, "employee", fixture["users"]["employee"]["username"], QA_PASSWORD)
        agent = _login(base, logger, "agent", fixture["users"]["agent"]["username"], QA_PASSWORD)

        report["knowledge"] = _create_knowledge_flow(admin, employee, tag, logger)
        report["checks"].append({"name": "knowledge lifecycle and version visibility", "status": "passed", "article_id": report["knowledge"]["article_id"]})
        report["imports"] = _create_import_flow(admin, employee, tag, logger)
        report["checks"].append({"name": "Markdown import, MinIO original and version review", "status": "passed", "article_id": report["imports"]["article_id"]})
        if not args.skip_same_batch_version:
            report["same_batch_version"] = _create_same_batch_version_flow(admin, employee, tag, logger)
            report["checks"].append({"name": "same-SHA two-file version batch bulk publish", "status": "passed", "batch_id": report["same_batch_version"]["batch_id"]})
        report["agent"] = _create_agent_flow(admin, employee, agent, logger)
        report["checks"].append({"name": "queue scoped Agent action proposal, approval and employee 403", "status": "passed", "ticket_id": report["agent"]["ticket_id"]})
        if not args.skip_department:
            report["department"] = _create_department_flow(admin, tag, logger)
            report["checks"].append({"name": "department creation", "status": "passed", "department_id": report["department"]["id"]})
        report["status"] = "PASSED"
        report["check_count"] = len(report["checks"])
        logger.write(f"RESULT: PASSED — synthetic real-HTTP checks completed; checks={report['check_count']}")
    except VerifyFailure as error:
        report["error"] = str(error)
        report["check_count"] = len(report["checks"])
        logger.write(f"RESULT: FAILED — {error}")
    except Exception as error:  # keep an auditable failure report for unexpected client errors
        report["error"] = f"unexpected {type(error).__name__}: {_safe_error(error)}"
        report["check_count"] = len(report["checks"])
        logger.write(f"RESULT: FAILED — {report['error']}")
    finally:
        report.setdefault("check_count", len(report["checks"]))
        report_path = VERIFY_OUTPUT_DIR / f"knowledge-http-report-{tag}.json"
        report["log_path"] = str(log_path)
        report["fixture_path"] = str(VERIFY_OUTPUT_DIR / f"knowledge-http-fixtures-{tag}.json")
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        logger.write(f"REPORT FILE: {report_path}")
        if fixture is not None:
            logger.write(f"QA CREDENTIALS FILE (test-only): {VERIFY_OUTPUT_DIR / f'knowledge-http-fixtures-{tag}.json'}")
            logger.write("FIXTURE POLICY: retained for browser acceptance; no existing or seed rows were deleted")
        logger.close()
    if report.get("status") == "PASSED":
        print(f"PASSED; report={report_path}; fixtures={VERIFY_OUTPUT_DIR / f'knowledge-http-fixtures-{tag}.json'}; log={log_path}")
        return 0
    print(f"FAILED; report={report_path}; fixtures={VERIFY_OUTPUT_DIR / f'knowledge-http-fixtures-{tag}.json'}; log={log_path}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
