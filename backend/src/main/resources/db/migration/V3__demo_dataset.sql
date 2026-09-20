-- 33 additional anonymized IT service tickets for evaluation and dashboard demos.
INSERT INTO tickets(id,requester_id,assignee_id,title,description,category,priority,status,due_at)
SELECT gs,
       CASE WHEN gs % 3 = 0 THEN 10003 ELSE 10001 END,
       CASE WHEN gs % 4 = 0 THEN NULL ELSE 10002 END,
       (ARRAY['VPN 认证超时','账号密码过期','JDK 安装申请','办公网络丢包','软件配置异常','权限审批进度查询'])[1 + (gs % 6)],
       '脱敏演示工单 #' || gs || '：请按照 IT 服务台标准流程处理并记录证据。',
       (ARRAY['NETWORK','ACCESS','SOFTWARE','NETWORK','SOFTWARE','ACCESS'])[1 + (gs % 6)],
       (ARRAY['P2','P3','P3','P4'])[1 + (gs % 4)],
       (ARRAY['NEW','TRIAGED','ASSIGNED','IN_PROGRESS','PENDING_USER','RESOLVED'])[1 + (gs % 6)],
       now() + ((gs % 3) - 1) * interval '6 hours'
FROM generate_series(3,35) gs
ON CONFLICT DO NOTHING;
SELECT setval('tickets_id_seq', GREATEST((SELECT COALESCE(MAX(id),1) FROM tickets),1));
