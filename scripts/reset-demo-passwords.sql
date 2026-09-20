-- 演示环境工具：把演示账号密码重置为文档化的初始密码（AgentDesk@2026）。
-- 仅用于本地演示数据库被改乱后恢复；生产环境请勿执行。
-- 用法：docker cp scripts/reset-demo-passwords.sql agentdesk-postgres-1:/tmp/
--       docker exec agentdesk-postgres-1 psql -U agentdesk -d agentdesk -f /tmp/reset-demo-passwords.sql
UPDATE users
SET password_hash = 'pbkdf2$120000$fBZGZwOtvTDuQNn7r4+PNg==$r3HVooTOwyQiSukQgGD4Gl6fjHFAvg3RWWSp8b6z9Gs='
WHERE username IN ('admin', 'zhangsan');
