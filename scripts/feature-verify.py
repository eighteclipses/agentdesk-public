# -*- coding: utf-8 -*-
"""Feature-completion verification: ticket close/reopen, assignment, filters, audit export,
conversation deletion, full-text search, SLA per priority, detail names,
notification center (status change/assignment/agent decision/read state),
admin dashboard stats endpoint."""
import json
import time
import datetime
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8080/api"
PASS, FAIL = 0, 0


def ts(s):
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000


def login(username, password):
    req = urllib.request.Request(BASE + "/auth/login",
        data=json.dumps({"username": username, "password": password}).encode(),
        method="POST", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.headers.get("Set-Cookie", "").split(";")[0]


def call(method, path, body=None, cookie=None, raw=False):
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            return resp.status, text if raw else json.loads(text)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"PASS - {name}")
    else:
        FAIL += 1
        print(f"FAIL - {name} {detail}")


# ---------- Setup: fresh ticket as employee ----------
emp = login("zhangsan", "AgentDesk@2026")
time.sleep(1)
st, t = call("POST", "/tickets", {
    "title": "Completion regression: printer offline",
    "description": "The printer on floor 2 is offline, test ticket for close/reopen.",
    "category": "GENERAL", "priority": "P1"}, emp)
check("create P1 ticket", st == 200, f"status={st}")
tid = t["id"]
check("SLA follows priority (P1 -> 4h)",
      abs((ts(t["dueAt"]) - ts(t["createdAt"])) - 4 * 3600 * 1000) < 5000,
      f"delta={(ts(t['dueAt']) - ts(t['createdAt']))/3600000}h")
check("ticket response carries requesterName", bool(t.get("requesterName")),
      f"requesterName={t.get('requesterName')}")

# ---------- Agent drives ticket to RESOLVED with reason ----------
agent = login("lisi", "Lisi@2026demo")
time.sleep(1)
st, _ = call("POST", f"/tickets/{tid}/transition", {"status": "TRIAGED"}, agent)
check("agent TRIAGED", st == 200, f"status={st}")
st, _ = call("POST", f"/tickets/{tid}/transition", {"status": "IN_PROGRESS"}, agent)
check("agent IN_PROGRESS", st == 200, f"status={st}")
st, body = call("POST", f"/tickets/{tid}/transition", {"status": "RESOLVED"}, agent)
check("RESOLVED without reason rejected", st == 400, f"status={st}")
st, _ = call("POST", f"/tickets/{tid}/transition",
             {"status": "RESOLVED", "reason": "Fixed by replacing the network cable."}, agent)
check("RESOLVED with reason", st == 200, f"status={st}")

# ---------- Assignee endpoint + explicit assign ----------
st, options = call("GET", f"/tickets/{tid}/assignees", None, agent)
check("assignees endpoint", st == 200 and isinstance(options, list) and len(options) >= 1,
      f"status={st} options={options if not isinstance(options, list) else len(options)}")
st, t2 = call("GET", f"/tickets/{tid}", None, agent)
check("detail carries assigneeName", "assigneeName" in t2, f"keys={list(t2.keys())[-4:]}")

# ---------- Requester closes and reopens ----------
st, _ = call("POST", f"/tickets/{tid}/transition", {"status": "CLOSED", "reason": "Confirmed fixed."}, emp)
check("requester can close RESOLVED ticket", st == 200, f"status={st} body={str(body)[:120]}")
st, _ = call("POST", f"/tickets/{tid}/transition",
             {"status": "IN_PROGRESS", "reason": "Issue recurred, reopening."}, emp)
check("CLOSED is terminal for requester (blocked)", st in (400, 403),
      f"status={st}")
# proper reopen path: create another ticket resolved then reopen
st, t3 = call("POST", "/tickets", {"title": "Reopen regression ticket", "description": "temp", "category": "GENERAL", "priority": "P4"}, emp)
tid2 = t3["id"]
call("POST", f"/tickets/{tid2}/transition", {"status": "TRIAGED"}, agent)
call("POST", f"/tickets/{tid2}/transition", {"status": "IN_PROGRESS"}, agent)
call("POST", f"/tickets/{tid2}/transition", {"status": "RESOLVED", "reason": "done"}, agent)
st, _ = call("POST", f"/tickets/{tid2}/transition", {"status": "IN_PROGRESS", "reason": "Not fixed, reopen."}, emp)
check("requester reopens RESOLVED -> IN_PROGRESS", st == 200, f"status={st}")
call("POST", f"/tickets/{tid2}/transition", {"status": "RESOLVED", "reason": "fixed again"}, agent)
st, _ = call("POST", f"/tickets/{tid2}/transition", {"status": "CLOSED", "reason": "ok now"}, emp)
check("requester closes after re-resolve", st == 200, f"status={st}")
check("SLA P4 = 72h", abs((ts(t3["dueAt"]) - ts(t3["createdAt"])) - 72 * 3600 * 1000) < 5000,
      f"delta={(ts(t3['dueAt']) - ts(t3['createdAt']))/3600000}h")

# ---------- Ticket filters ----------
st, page = call("GET", "/tickets?status=CLOSED&size=100", None, emp)
check("employee filter status=CLOSED", st == 200 and all(i["status"] == "CLOSED" for i in page["items"]),
      f"status={st} n={len(page.get('items', []))}")
st, page = call("GET", "/tickets?status=RESOLVED&size=100", None, agent)
check("agent filter status=RESOLVED", st == 200 and all(i["status"] == "RESOLVED" for i in page["items"]),
      f"status={st}")

# ---------- Full-text search endpoint (front-end wired) ----------
st, hits = call("GET", "/knowledge/search?q=" + urllib.parse.quote("认证超时"), None, emp)
check("FTS search wired endpoint", st == 200 and len(hits) >= 1 and all("citation" in h for h in hits),
      f"status={st} hits={len(hits) if isinstance(hits, list) else hits}")

# ---------- Audit CSV export ----------
admin = login("admin", "AgentDesk@2026")
time.sleep(1)
req = urllib.request.Request(BASE + "/audit/export?action=TICKET", headers={"Cookie": admin})
with urllib.request.urlopen(req, timeout=60) as resp:
    csv = resp.read().decode("utf-8-sig", errors="replace")
    ctype = resp.headers.get("Content-Type", "")
check("audit CSV export", csv.startswith("id,") and "\n" in csv and "text/csv" in ctype,
      f"type={ctype} head={csv[:40]!r}")

# ---------- Conversation delete ----------
st, convs = call("GET", "/knowledge/conversations", None, emp)
check("conversations listed", st == 200 and isinstance(convs, list), f"status={st}")
if convs:
    cid = convs[0]["id"]
    st, _ = call("DELETE", f"/knowledge/conversations/{cid}", None, emp)
    check("conversation delete", st == 200, f"status={st}")
    st, _ = call("GET", f"/knowledge/conversations/{cid}", None, emp)
    check("deleted conversation gone (404)", st == 404, f"status={st}")
    # foreign user cannot delete someone else's
    convs2 = call("GET", "/knowledge/conversations", None, admin)[1]
    if isinstance(convs2, list) and convs2:
        st, _ = call("DELETE", f"/knowledge/conversations/{convs2[0]['id']}", None, emp)
        check("cannot delete others' conversation", st == 404, f"status={st}")

# ---------- Import item original file download ----------
st, batches = call("GET", "/admin/imports", None, admin)
items_st, batch_detail = call("GET", "/admin/imports/2", None, admin)
items = batch_detail.get("items", [])
if items:
    iid = items[0]["id"]
    req = urllib.request.Request(BASE + f"/admin/imports/items/{iid}/file", headers={"Cookie": admin})
    with urllib.request.urlopen(req, timeout=60) as resp:
        content = resp.read()
    check("import item file download", resp.status == 200 and len(content) > 0, f"bytes={len(content)}")

# ---------- Notification center ----------
st, cnt = call("GET", "/notifications/unread-count", None, emp)
check("employee has unread notifications after transitions", st == 200 and cnt.get("count", 0) >= 1,
      f"status={st} count={cnt}")

st, page = call("GET", "/notifications?size=50", None, emp)
types = {n["type"] for n in page.get("items", [])}
check("employee sees TICKET_STATUS_CHANGED notifications",
      st == 200 and "TICKET_STATUS_CHANGED" in types, f"status={st} types={sorted(types)}")
check("employee sees TICKET_ASSIGNED/SLA/AGENT notification types or status changes",
      st == 200 and page.get("unread", 0) >= 1, f"status={st} unread={page.get('unread')}")

st, unread_page = call("GET", "/notifications?size=50&unread=true", None, emp)
check("unread-only filter returns only unread",
      st == 200 and all(not n["read"] for n in unread_page.get("items", [])) and len(unread_page.get("items", [])) >= 1,
      f"status={st} n={len(unread_page.get('items', []))}")

before = unread_page.get("unread", 0)
first_unread = unread_page["items"][0]
st, _ = call("POST", f"/notifications/{first_unread['id']}/read", None, emp)
st, cnt2 = call("GET", "/notifications/unread-count", None, emp)
check("mark one notification read decreases unread count",
      st == 200 and cnt2.get("count", 0) == before - 1, f"before={before} after={cnt2}")

st, _ = call("POST", "/notifications/read-all", None, emp)
st, cnt3 = call("GET", "/notifications/unread-count", None, emp)
check("read-all clears unread", st == 200 and cnt3.get("count", 0) == 0, f"after={cnt3}")

# ---------- Agent action approval -> proposer notification ----------
st, t4 = call("POST", "/tickets", {"title": "Agent action notification regression",
                                   "description": "ticket for transfer approval flow", "category": "GENERAL", "priority": "P3"}, emp)
tid4 = t4["id"]
call("POST", f"/tickets/{tid4}/transition", {"status": "TRIAGED"}, agent)
st, action = call("POST", "/agent/actions", {"actionType": "TRANSFER", "ticketId": tid4,
                                             "payload": {"queueCode": "GENERAL"}}, agent)
check("agent proposes transfer action", st == 200 and action.get("status") == "PENDING", f"status={st}")
st, _ = call("POST", f"/agent/actions/{action['id']}/approve", None, admin)
check("admin approves transfer action", st == 200, f"status={st}")
st, t4b = call("GET", f"/tickets/{tid4}", None, agent)
check("approved transfer moves ticket to ASSIGNED", t4b.get("status") == "ASSIGNED" and t4b.get("queueCode") == "GENERAL",
      f"status={t4b.get('status')} queue={t4b.get('queueCode')}")

st, page = call("GET", "/notifications?size=50", None, agent)
agent_types = {n["type"] for n in page.get("items", [])}
check("agent notified of agent action decision", "AGENT_ACTION_DECIDED" in agent_types,
      f"types={sorted(agent_types)}")

# ---------- Admin dashboard stats ----------
st, stats = call("GET", "/dashboard/stats?days=7", None, admin)
sum_status = sum(b["count"] for b in stats.get("status", [])) if isinstance(stats, dict) else -1
total_tickets = call("GET", "/dashboard", None, admin)[1].get("tickets", -2)
check("stats endpoint returns full sections",
      st == 200 and all(k in stats for k in ("status", "priority", "dailyCreated", "sla", "qa")),
      f"status={st} keys={list(stats)[:8] if isinstance(stats, dict) else stats}")
check("stats status distribution sums to total tickets", sum_status == total_tickets,
      f"sum={sum_status} total={total_tickets}")
check("stats dailyCreated covers 7 days", len(stats.get("dailyCreated", [])) == 7,
      f"n={len(stats.get('dailyCreated', []))}")
check("stats sla section has rate field", isinstance(stats.get("sla"), dict) and "rate" in stats["sla"],
      f"sla={stats.get('sla')}")
st, _ = call("GET", "/dashboard/stats", None, emp)
check("stats endpoint admin-only (employee 403)", st == 403, f"status={st}")

# ---------- Knowledge base: search pagination + OR fallback + facets ----------
st, page = call("GET", "/knowledge/search?q=VPN&page=1&size=5", None, emp)
check("knowledge search pagination structure",
      st == 200 and isinstance(page, dict) and "items" in page and "total" in page and len(page["items"]) <= 5,
      f"status={st} keys={list(page)[:4] if isinstance(page, dict) else page}")

st, hits = call("GET", "/knowledge/search?q=" + urllib.parse.quote("vpn 认证 超时 打印机 投影仪 会议室"), None, emp)
check("long query falls back to OR recall (no more empty results)",
      st == 200 and isinstance(hits, list) and len(hits) >= 1,
      f"status={st} hits={len(hits) if isinstance(hits, list) else hits}")

st, cats = call("GET", "/knowledge/categories", None, emp)
check("categories facet endpoint", st == 200 and isinstance(cats, list), f"status={st}")
st, tags = call("GET", "/knowledge/tags", None, emp)
check("tags facet endpoint", st == 200 and isinstance(tags, list), f"status={st}")
if isinstance(cats, list) and cats:
    st, filtered = call("GET", "/knowledge?page=1&size=50&category=" + urllib.parse.quote(str(cats[0])), None, emp)
    check("list category filter only returns matching category",
          st == 200 and all(i["category"] == cats[0] for i in filtered.get("items", [])) and len(filtered.get("items", [])) >= 1,
          f"status={st} cat={cats[0]}")

# ---------- Knowledge lifecycle: create (sensitivity) -> invisible to employee -> edit -> review ----------
st, created = call("POST", "/knowledge", {
    "title": "KB regression: confidential runbook",
    "content": "# 机密手册\n\nroot 口令保管流程，仅限授权部门。",
    "category": "测试", "visibility": "PUBLIC", "sensitivity": "CONFIDENTIAL",
    "tags": ["回归测试"]}, admin)
check("admin creates article with sensitivity", st == 200 and created.get("id"), f"status={st}")
kb_id = created.get("id")

st, _ = call("GET", f"/knowledge/articles/{kb_id}", None, emp)
check("CONFIDENTIAL article invisible to employee without grant", st == 404, f"status={st}")
st, _ = call("GET", f"/knowledge/articles/{kb_id}", None, admin)
check("admin can read own CONFIDENTIAL article", st == 200, f"status={st}")

st, updated = call("PUT", f"/knowledge/articles/{kb_id}", {
    "content": "# 机密手册 v2\n\nKBEDITMARK：新增应急联系人流程。",
    "sensitivity": "INTERNAL"}, admin)
check("admin edit generates new draft version",
      st == 200 and updated.get("contentChanged") is True and updated.get("newVersion") == 2,
      f"status={st} body={str(updated)[:120]}")
st, arts = call("GET", "/knowledge?page=1&size=50&status=IN_REVIEW", None, admin)
check("edited article enters review status",
      st == 200 and any(a["id"] == kb_id for a in arts.get("items", [])),
      f"status={st} n={len(arts.get('items', [])) if isinstance(arts, dict) else arts}")
st, _ = call("POST", f"/knowledge/articles/{kb_id}/review", {"approve": True, "comment": "回归发布"}, admin)
check("review approve publishes edited version", st == 200, f"status={st}")
st, detail = call("GET", f"/knowledge/articles/{kb_id}", None, admin)
check("published detail carries edited content",
      st == 200 and "KBEDITMARK" in str(detail.get("content", "")) and detail.get("version") == 2,
      f"status={st} version={detail.get('version')}")

print(f"\nTOTAL: {PASS} passed, {FAIL} failed")
raise SystemExit(1 if FAIL else 0)
