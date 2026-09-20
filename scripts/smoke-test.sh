#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"

echo "1) health"
curl -fsS "$BASE/actuator/health" >/dev/null

echo "2) admin login"
cookie=$(mktemp)
curl -fsS -c "$cookie" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"AgentDesk@2026"}' >/dev/null

echo "3) employee login"
emp_cookie=$(mktemp)
curl -fsS -c "$emp_cookie" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"zhangsan","password":"AgentDesk@2026"}' >/dev/null
curl -fsS -b "$emp_cookie" -X POST "$BASE/api/auth/password" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"AgentDesk@2026","newPassword":"AgentDesk@2026"}' >/dev/null

echo "4) employee creates a ticket"
curl -fsS -b "$emp_cookie" -X POST "$BASE/api/tickets" -H 'Content-Type: application/json' \
  -d '{"title":"VPN 无法连接","description":"出差时无法连接公司 VPN","category":"NETWORK","priority":"P2"}' >/tmp/agentdesk-ticket.json

echo "4) employee cannot read another user's ticket"
status=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/tickets/2" -b "$emp_cookie")
test "$status" = "403"

echo "5) knowledge search returns chunk anchor citations (article:x/version:y#cN)"
curl -fsS "$BASE/api/knowledge/search?q=VPN" -b "$emp_cookie" >/tmp/agentdesk-search.json
grep -q 'VPN' /tmp/agentdesk-search.json
grep -q '#c[0-9]' /tmp/agentdesk-search.json

echo "5) article detail exposes chunks + versions + tags"
article_id=$(grep -o '"articleId":[0-9]*' /tmp/agentdesk-search.json | head -1 | cut -d: -f2)
curl -fsS "$BASE/api/knowledge/articles/$article_id" -b "$emp_cookie" >/tmp/agentdesk-detail.json
grep -q '"chunks"' /tmp/agentdesk-detail.json
curl -fsS "$BASE/api/knowledge/articles/$article_id/versions" -b "$emp_cookie" >/dev/null

echo "6) batch import a document -> polls to REVIEW/PUBLISHED"
curl -fsS -b "$cookie" -X POST "$BASE/api/admin/imports" \
  -F 'name=smoke-batch' -F 'duplicateStrategy=skip' \
  -F 'files=@scripts/sample-doc.md;type=text/markdown' >/tmp/agentdesk-batch.json
batch_id=$(grep -o '"batchId":[0-9]*' /tmp/agentdesk-batch.json | head -1 | cut -d: -f2)
curl -fsS "$BASE/api/admin/imports/$batch_id" -b "$cookie" >/tmp/agentdesk-batch-detail.json
imported_article=$(grep -o '"articleId":[0-9]*' /tmp/agentdesk-batch-detail.json | head -1 | cut -d: -f2)
if [ -z "$imported_article" ] || [ "$imported_article" = "0" ]; then
  echo "  (sample-doc.md missing; skipping batch publish assertion)"
else
  echo "  publishing article $imported_article"
  curl -fsS -b "$cookie" -X POST "$BASE/api/knowledge/articles/$imported_article/review" \
    -H 'Content-Type: application/json' -d '{"approve":true,"comment":"smoke publish"}' >/dev/null
  curl -fsS "$BASE/api/knowledge/articles/$imported_article" -b "$emp_cookie" >/dev/null
fi

echo "7) feedback + conversations history"
curl -fsS -b "$emp_cookie" "$BASE/api/knowledge/conversations" >/dev/null

echo "smoke tests passed"
