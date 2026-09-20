"""Real PostgreSQL/API regression. Creates only qa_* fixtures and cleans them by ID.

Run against a local Compose development stack: python scripts/usability-verify.py
Use --keep-fixtures for browser verification; fixture credentials are test-only.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import pathlib
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:5173/api')
parser.add_argument('--keep-fixtures', action='store_true')
args = parser.parse_args()
prefix = 'qa_' + secrets.token_hex(4)
password = 'Qa-only@' + secrets.token_hex(8)
salt = secrets.token_bytes(16)
hashed = 'pbkdf2$120000$' + base64.b64encode(salt).decode() + '$' + base64.b64encode(hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 120000)).decode()

def sql(statement):
    result = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'agentdesk', '-d', 'agentdesk', '-v', 'ON_ERROR_STOP=1', '-At'], input=statement, text=True, encoding='utf-8', capture_output=True, cwd=ROOT, check=True)
    return result.stdout.strip()

def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def call(c, method, path, data=None, raw=False, headers=None):
    body = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode() if data is not None else None
    req = urllib.request.Request(args.base + path, data=body, method=method, headers=headers or {'Content-Type': 'application/json'})
    try:
        with c.open(req, timeout=45) as r:
            content = r.read().decode('utf-8', errors='replace')
            return r.status, content if raw else json.loads(content) if content else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', errors='replace')

def ok(name, condition):
    if not condition:
        raise AssertionError(name)
    print('PASS - ' + name, flush=True)
    results.append(name)

results, ids = [], []
try:
    # Insert new, isolated identities; never reset or change a pre-existing account.
    for suffix, role in [('employee','EMPLOYEE'),('other','EMPLOYEE'),('agent','AGENT')]:
        name = prefix + '_' + suffix
        inserted = sql(f"INSERT INTO users(username,display_name,department_id,password_hash,account_status,force_password_change) SELECT '{name}','QA {suffix}',min(id),'{hashed}','ACTIVE',false FROM departments RETURNING id;")
        uid = int(inserted.splitlines()[0]); ids.append(uid)
        sql(f"INSERT INTO user_roles(user_id,role_id) SELECT {uid},id FROM roles WHERE code='{role}';")
    employee_id, other_id, agent_id = ids
    sql(f"INSERT INTO queue_members(queue_id,user_id) SELECT id,{agent_id} FROM support_queues WHERE code='NETWORK';")
    employee, other, agent = client(), client(), client()
    for c, suffix in [(employee,'employee'),(other,'other'),(agent,'agent')]:
        status, _ = call(c, 'POST', '/auth/login', {'username': prefix+'_'+suffix, 'password':password})
        ok('login ' + suffix, status == 200)
    status, ticket = call(employee,'POST','/tickets', {'title':prefix+' VPN 连接异常','description':'QA synthetic fixture. VPN cannot connect.','category':'network','priority':'p1'})
    ok('create normalizes category and priority', status == 200 and ticket['priority'] == 'P1' and ticket['category'] == 'NETWORK')
    tid = ticket['id']
    ok('other employee cannot open ticket', call(other,'GET',f'/tickets/{tid}')[0] == 403)
    status, search = call(employee,'GET','/tickets?' + urllib.parse.urlencode({'q':'#'+str(tid),'page':1,'size':20}))
    ok('search by ticket number', status == 200 and [t['id'] for t in search['items']] == [tid])
    status, search = call(other,'GET','/tickets?' + urllib.parse.urlencode({'q':prefix,'page':1,'size':20}))
    ok('search preserves requester isolation', status == 200 and search['total'] == 0)
    status, search = call(employee,'GET','/tickets?' + urllib.parse.urlencode({'q':"' OR 1=1 --",'page':1,'size':20}))
    ok('search treats SQL syntax as text', status == 200 and search['total'] == 0)
    status, started = call(agent,'POST',f'/tickets/{tid}/transition', {'status':'IN_PROGRESS'})
    ok('agent starts and claims new ticket', status == 200 and started['assigneeId'] == agent_id)
    status, mine = call(agent,'GET','/tickets?view=mine&page=1&size=20')
    ok('assigned-to-me view', status == 200 and tid in [t['id'] for t in mine['items']])
    status, _ = call(agent,'POST',f'/tickets/{tid}/comments', {'content':'QA reply: please verify connectivity.'})
    ok('agent reply is saved', status == 200)
    status, notices = call(employee,'GET','/notifications?size=100')
    ok('reply sends a direct ticket notification', status == 200 and any(n['type']=='TICKET_COMMENTED' and n['link'].endswith('?ticket='+str(tid)) for n in notices['items']))
    boundary = 'qaBoundary'
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="qa-large.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode() + b'x'*(2*1024*1024) + f'\r\n--{boundary}--\r\n'.encode())
    status, _ = call(employee,'POST',f'/tickets/{tid}/attachments',payload,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    ok('2MB upload works through frontend proxy', status == 200)
    ok('cannot resolve without explanation', call(agent,'POST',f'/tickets/{tid}/transition',{'status':'RESOLVED'})[0] == 400)
    status, _ = call(agent,'POST',f'/tickets/{tid}/transition',{'status':'RESOLVED','reason':'QA restored network'})
    ok('resolution succeeds', status == 200)
    status, comments = call(employee,'GET',f'/tickets/{tid}/comments')
    ok('employee can read resolution reason', status == 200 and any('QA restored network' in c['content'] for c in comments))
    status, _ = call(employee,'POST',f'/tickets/{tid}/transition',{'status':'IN_PROGRESS','reason':'QA still failing','assigneeId':agent_id})
    ok('requester cannot inject assignee on reopen', status == 403)
    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda _: call(employee,'POST',f'/tickets/{tid}/transition',{'status':'CLOSED','reason':'QA verified recovery'})[0], range(2)))
    ok('concurrent close is applied once', statuses.count(200) == 1 and all(s in (200,400,403) for s in statuses))
    status, stream = call(employee,'POST','/knowledge/ask/stream',{'question':'zznonevidence'+secrets.token_hex(16)},raw=True)
    events = []
    for frame in stream.replace('\r\n','\n').split('\n\n'):
        data = next((x[5:].strip() for x in frame.splitlines() if x.startswith('data:')),None)
        if data: events.append(json.loads(data))
    error = next((x for x in events if x.get('reason')=='NO_EVIDENCE'),{})
    ok('no-evidence answer has persisted identity', status == 200 and bool(error.get('messageId')) and bool(error.get('conversationId')))
    cid, mid = error['conversationId'], error['messageId']
    status, history = call(employee,'GET',f'/knowledge/conversations/{cid}')
    ok('history roles match frontend contract', status == 200 and [m['role'] for m in history['messages']] == ['user','assistant'])
    ok('feedback SQL accepts reasons and comment', call(employee,'POST','/knowledge/feedback',{'messageId':mid,'rating':-1,'reasons':['证据不足'],'comment':'QA feedback'})[0] == 200)
    ok('feedback rejects invalid rating', call(employee,'POST','/knowledge/feedback',{'messageId':mid,'rating':9})[0] == 400)
    ok('other employee cannot rate this answer', call(other,'POST','/knowledge/feedback',{'messageId':mid,'rating':1})[0] == 404)
    print(f'{len(results)} checks passed',flush=True)
finally:
    if ids:
        id_list=','.join(str(x) for x in ids)
        # Delete only rows owned by identities created in this execution.
        cleanup=f"""BEGIN;
DELETE FROM notifications WHERE user_id IN ({id_list}) OR (ref_type='TICKET' AND ref_id IN (SELECT id FROM tickets WHERE requester_id IN ({id_list})));
DELETE FROM ticket_attachments WHERE ticket_id IN (SELECT id FROM tickets WHERE requester_id IN ({id_list}));
DELETE FROM tickets WHERE requester_id IN ({id_list});
DELETE FROM knowledge_feedback WHERE user_id IN ({id_list});
DELETE FROM conversations WHERE user_id IN ({id_list});
DELETE FROM audit_logs WHERE actor_id IN ({id_list});
DELETE FROM queue_members WHERE user_id IN ({id_list});
DELETE FROM users WHERE id IN ({id_list});
COMMIT;"""
        if args.keep_fixtures:
            path=ROOT/'scripts'/'qa-fixtures.local.json'
            path.write_text(json.dumps({'prefix':prefix,'password':password,'ids':ids,'cleanupSql':cleanup},ensure_ascii=False),encoding='utf-8')
            print('Browser fixtures saved to scripts/qa-fixtures.local.json')
        else:
            sql(cleanup)
