CREATE TABLE IF NOT EXISTS support_queues (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(240),
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS queue_members (
  queue_id BIGINT NOT NULL REFERENCES support_queues(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(queue_id, user_id)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS force_password_change BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS department_id BIGINT REFERENCES departments(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS queue_id BIGINT REFERENCES support_queues(id);

INSERT INTO support_queues(code,name,description) VALUES
  ('NETWORK','网络服务队列','VPN、DNS、Wi-Fi、网络连接问题'),
  ('ACCESS','账号权限队列','账号、密码、权限和审批问题'),
  ('SOFTWARE','终端软件队列','JDK、Maven、办公软件和配置问题'),
  ('GENERAL','通用服务台','其他 IT 服务请求')
ON CONFLICT (code) DO NOTHING;

UPDATE users SET password_hash = CASE id
  WHEN 10000 THEN 'pbkdf2$120000$csvsccoGR0H6eVjQVJsEDw==$IfQhmcghEFIvJukDCVCmDZXogq3zEZ/YNvXmMuOtfmU='
  WHEN 10001 THEN 'pbkdf2$120000$t62wRuk6/n2aGM+YDNg0gA==$j+VTF0D5r/utkfl9Gy8SU6t4V7aTvROW88hnq/6e/W0='
  WHEN 10002 THEN 'pbkdf2$120000$4n1T8/yQ+qjHmlUshMBZwg==$caMfHEa4Y5b4GZdMVLLjS3l2oybUx4TLYJ8SV22x4J4='
  WHEN 10003 THEN 'pbkdf2$120000$v405WWtHMwB5+mRllRO/Jg==$FY7ETiFZ7QOT6YFaYFxLmFEVtWpxTLoj0QNdfgtZgs8='
  ELSE password_hash END,
  force_password_change = true
WHERE id IN (10000,10001,10002,10003) AND (password_hash IS NULL OR password_hash = '');

UPDATE tickets t SET department_id = u.department_id,
  queue_id = q.id
FROM users u, support_queues q
WHERE t.requester_id = u.id
  AND q.code = CASE t.category WHEN 'NETWORK' THEN 'NETWORK' WHEN 'ACCESS' THEN 'ACCESS' WHEN 'SOFTWARE' THEN 'SOFTWARE' ELSE 'GENERAL' END
  AND (t.department_id IS NULL OR t.queue_id IS NULL);

INSERT INTO queue_members(queue_id,user_id)
SELECT q.id, 10002 FROM support_queues q WHERE q.code IN ('NETWORK','ACCESS','SOFTWARE','GENERAL')
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_tickets_department ON tickets(department_id);
CREATE INDEX IF NOT EXISTS idx_queue_members_user ON queue_members(user_id);
