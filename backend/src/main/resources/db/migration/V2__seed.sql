INSERT INTO departments(id,name) VALUES (1,'IT 服务台'),(2,'研发中心'),(3,'销售中心') ON CONFLICT DO NOTHING;
INSERT INTO users(id,username,display_name,department_id) VALUES
  (10000,'admin','系统管理员',1),(10001,'zhangsan','张三',2),(10002,'lisi','李四',1),(10003,'wangwu','王五',3)
  ON CONFLICT DO NOTHING;
INSERT INTO roles(code,name) VALUES ('ADMIN','管理员'),('AGENT','服务台处理人'),('EMPLOYEE','普通员工') ON CONFLICT DO NOTHING;
INSERT INTO user_roles(user_id,role_id) SELECT 10000,id FROM roles WHERE code='ADMIN' ON CONFLICT DO NOTHING;
INSERT INTO user_roles(user_id,role_id) SELECT 10002,id FROM roles WHERE code='AGENT' ON CONFLICT DO NOTHING;
INSERT INTO user_roles(user_id,role_id) SELECT 10001,id FROM roles WHERE code='EMPLOYEE' ON CONFLICT DO NOTHING;
INSERT INTO user_roles(user_id,role_id) SELECT 10003,id FROM roles WHERE code='EMPLOYEE' ON CONFLICT DO NOTHING;

INSERT INTO tickets(id,requester_id,assignee_id,title,description,category,priority,status,due_at)
VALUES (1,10001,10002,'VPN 无法连接','出差时无法连接公司 VPN，客户端提示认证超时。','NETWORK','P2','ASSIGNED',now()+interval '12 hours'),
       (2,10003,10002,'无法安装开发工具','需要安装 JDK 17 和 Maven，缺少管理员权限。','SOFTWARE','P3', 'IN_PROGRESS', now()+interval '18 hours')
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_articles(id,title,category,status,visibility) VALUES
 (1,'VPN 认证超时排查手册','网络','PUBLISHED','PUBLIC'),
 (2,'JDK 与 Maven 安装规范','软件','PUBLISHED','PUBLIC'),
 (3,'账号权限申请与审批流程','账号','PUBLISHED','PUBLIC')
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_versions(id,article_id,version,content,created_by) VALUES
 (1,1,1,'VPN 认证超时时，先确认设备时间与企业证书有效期；再切换到公司网络测试 DNS；仍失败时清理客户端缓存并重新登录。若连续三次失败，请附上客户端日志提交 IT 服务台。',10002),
 (2,2,1,'开发设备安装 JDK 17 与 Maven 前，需要在软件中心提交申请。安装后设置 JAVA_HOME，并使用 mvn -v 验证版本。禁止从未知站点下载可执行安装包。',10002),
 (3,3,1,'申请生产、数据库或管理员权限时，填写业务用途、有效期和直属主管。IT 服务台审核后由系统管理员授权，临时权限到期自动回收。',10000)
ON CONFLICT DO NOTHING;
UPDATE knowledge_articles SET current_version_id=id WHERE id IN (1,2,3) AND current_version_id IS NULL;
SELECT setval('tickets_id_seq', GREATEST((SELECT COALESCE(MAX(id),1) FROM tickets),1));
SELECT setval('knowledge_articles_id_seq', GREATEST((SELECT COALESCE(MAX(id),1) FROM knowledge_articles),1));
SELECT setval('knowledge_versions_id_seq', GREATEST((SELECT COALESCE(MAX(id),1) FROM knowledge_versions),1));
