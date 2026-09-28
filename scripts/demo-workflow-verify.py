"""Exercise a real model-assisted ticket lifecycle on a local synthetic demo stack.

Requires an existing QA fixture file from knowledge-http-verify.py. Creates a new
synthetic ticket and preserves it for inspection. Does not read model API keys.
Calls the configured provider through the application's authenticated API.
"""
import argparse
import datetime as dt
import http.cookiejar
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Session:
    def __init__(self, base):
        self.base = base.rstrip('/')
        parsed = urllib.parse.urlsplit(base)
        self.origin = f'{parsed.scheme}://{parsed.netloc}'
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, payload=None, expected=200):
        body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
        req = urllib.request.Request(self.base + path, data=body, method=method,
                                     headers={'Content-Type': 'application/json', 'Origin': self.origin})
        try:
            with self.opener.open(req, timeout=100) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        if status != expected:
            # Avoid persisting upstream diagnostics or credentials in a public report.
            raise AssertionError(f'{method} {path}: expected HTTP {expected}, got {status}')
        return json.loads(raw) if raw else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--provider', default='deepseek')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.base)
    if url.hostname not in {'localhost', '127.0.0.1', '::1'} or url.username or url.password:
        parser.error('Use a local isolated demo endpoint without credentials in its URL')
    if args.output.exists():
        parser.error('Choose a new output filename; existing evidence is never overwritten')
    fixtures = json.loads(args.fixtures.read_text(encoding='utf-8'))
    if fixtures.get('database') != 'agentdesk_test':
        parser.error('Only fixtures belonging to agentdesk_test are accepted')
    report = {'started_at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'provider': args.provider, 'synthetic_data': True, 'checks': [], 'status': 'RUNNING'}

    def passed(name, **details):
        report['checks'].append({'name': name, 'status': 'passed', **details})
        print('PASS ' + name, flush=True)

    try:
        sessions = {}
        for role in ('employee', 'agent', 'admin'):
            session = Session(args.base)
            session.call('POST', '/auth/login', {'username': fixtures['users'][role]['username'],
                                                'password': fixtures['password']})
            sessions[role] = session
        employee, agent, admin = (sessions[r] for r in ('employee', 'agent', 'admin'))
        passed('three real cookie sessions')
        report['providers'] = agent.call('GET', '/agent/providers').get('providers', [])
        title = '演示工单：VPN 认证超时 ' + uuid.uuid4().hex[:6]
        description = '合成演示数据：出差电脑连接公司 VPN 时提示认证超时，请协助排查设备时间、证书和客户端缓存。'
        ticket = employee.call('POST', '/tickets', {'title': title, 'description': description,
                                                   'category': 'NETWORK', 'priority': 'P2'})
        tid = ticket['id']
        report['ticket_id'] = tid
        assert ticket['status'] == 'NEW'
        passed('employee creates NEW ticket', ticket_id=tid)
        context = {'ticketId': tid, 'title': title, 'description': description, 'provider': args.provider}
        for endpoint in ('classify', 'recommend', 'retrieve-draft'):
            payload = context if endpoint != 'retrieve-draft' else {
                'ticketId': tid, 'question': 'VPN 认证超时如何排查？', 'provider': args.provider}
            started = time.monotonic()
            result = agent.call('POST', '/agent/' + endpoint, payload)
            assert result.get('ok') is True, endpoint + ' did not complete'
            assert result.get('source', '').lower() == args.provider.lower(), endpoint + ' used a fallback'
            if endpoint == 'retrieve-draft':
                assert result.get('citations'), 'reply must retain evidence citations'
            report[endpoint] = result
            passed('real model ' + endpoint, source=result['source'], run_id=result.get('runId'),
                   elapsed_ms=round((time.monotonic() - started) * 1000))
        suggestion = report['recommend']['suggestedAction']
        assert suggestion['ticketId'] == tid and suggestion['actionType'] == 'TRANSFER'
        pending = agent.call('POST', '/agent/actions', suggestion)
        aid = pending['id']
        assert pending['status'] == 'PENDING'
        assert employee.call('GET', f'/tickets/{tid}')['status'] == 'NEW'
        passed('model suggestion waits for human approval', action_id=aid)
        employee.call('POST', f'/agent/actions/{aid}/approve', expected=403)
        passed('employee cannot approve an Agent action')
        decision = admin.call('POST', f'/agent/actions/{aid}/approve')
        assert decision['status'] == 'APPROVED'
        assert admin.call('GET', f'/tickets/{tid}')['status'] == 'ASSIGNED'
        passed('administrator approves; ticket becomes ASSIGNED')
        assert agent.call('POST', f'/tickets/{tid}/transition', {'status': 'IN_PROGRESS'})['status'] == 'IN_PROGRESS'
        passed('queue agent starts handling the ticket')
        resolution = '演示处理记录：依据知识库核对设备时间和证书，清理客户端缓存；模拟连接验证通过，请员工确认。'
        assert agent.call('POST', f'/tickets/{tid}/transition', {'status': 'RESOLVED', 'reason': resolution})['status'] == 'RESOLVED'
        passed('agent resolves with an employee-visible explanation')
        comments = employee.call('GET', f'/tickets/{tid}/comments')
        assert any(resolution in comment.get('content', '') for comment in comments)
        closed = employee.call('POST', f'/tickets/{tid}/transition', {'status': 'CLOSED', 'reason': '演示确认：已核对处理结果，确认关闭。'})
        assert closed['status'] == 'CLOSED'
        passed('requester confirms and closes the ticket')
        audit = admin.call('GET', '/audit?page=1&size=200')['items']
        events = [event for event in audit if event.get('resource_type') == 'TICKET'
                  and event.get('resource_id') == tid and event.get('action') == 'TICKET_STATUS_CHANGED']
        assert len(events) >= 3
        report['state_changes'] = [event.get('detail') for event in events]
        report['final_status'] = 'CLOSED'
        passed('status transitions are auditable', event_count=len(events))
        report['status'] = 'PASSED'
    except Exception as error:
        report['status'] = 'FAILED'
        report['error'] = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print('FAIL ' + report['error'], flush=True)
    finally:
        report['finished_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f"RESULT {report['status']}: {len(report['checks'])} checks; {args.output}", flush=True)
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
