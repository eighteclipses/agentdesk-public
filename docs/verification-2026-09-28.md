# 2026-09-28 实际运行与 DeepSeek 工单闭环验证

本次已运行隔离的六服务 Docker 环境，并通过真实 DeepSeek 完成合成工单的“提单 → AI 建议 → 人工审批 → 处理 → 员工确认关闭”。以下是本次实际结果，不以单元测试替代全栈运行，也不代表生产上线验收。

## 实测结果

| 验证内容 | 结果 | 范围 |
| --- | --- | --- |
| Java 后端 | 55 项通过，0 失败、0 错误、0 跳过 | 44 项单元测试和 11 项真实 PostgreSQL 生命周期集成测试；集成测试的审计、通知、向量服务使用测试替身 |
| Python Agent | 31 项通过 | 离线单元测试，模型响应使用 mock |
| 前端 | 8 项通过；TypeScript 检查、Vite 构建通过 | 开发版与脱敏公开版分别验证；文章详情约 501 KB 分块产生体积提示，未测量加载性能 |
| AI 评估工具 | 9 项测试通过；24 条案例、2 组 Prompt 离线校验通过 | 尚未运行 48 次 Prompt 对照评估，不能据此给出模型准确率 |
| 知识库与权限 HTTP 流程 | 7 组通过 | 真实 Cookie 登录、前端代理、版本访问、Markdown 解析、MinIO 原件、同批重复导入、队列审批、员工越权拦截、新建部门 |
| 发布进度 SSE | 通过 | 从前端代理读取批次事件回放，确认唯一 PUBLISHED 事件对应已提交的发布项；不是断网、并发、多实例压力验收 |
| 真实模型工单流程 | 12 项检查通过 | 分类、建议、引用回复均为 `source=DEEPSEEK`，并验证审批前不执行、员工审批返回 403、人工批准和最终关闭 |

## 环境与代码对应

- Docker Engine 29.7.2；前端 Nginx、Spring Boot、FastAPI Agent、PostgreSQL、Redis、MinIO 六个容器实际运行在隔离网络中。
- 使用 `agentdesk_test` 数据库和合成业务资料，不使用真实企业工单。前端入口为本机 `http://127.0.0.1:15179`，后端健康检查端口为 `18089`。
- 本次复用了隔离容器和已有镜像，更新后端 JAR、前端构建产物与 Agent 配置。**没有执行从空缓存开始的 `docker compose up --build`**，因此不宣称已验证全新机器的一键安装；`docker compose config --quiet` 已通过。
- Flyway V17、V18 均已成功应用。源目录构建 JAR、隔离栈挂载 JAR、运行容器中的 JAR 的 SHA-256 均为 `9e07313a67687093c49abc6814fc2b6c3bb92315ce4d37e2e6103a223a23a10c`。9 月 22 日最后一次修改未部署的历史限制已在本次消除。
- 公开版后端和 Agent 运行源码与本次实测开发版一致；前端保留公开版既有的脱敏登录页，其余本次同步的业务代码一致。

## 真实模型与审批证据

本次配置供应商为 DeepSeek，配置模型标识为 `deepseek-chat`。密钥仅保存在被 Git 忽略的本地环境配置中，不写入报告。先进行了一次直接连通性请求；之后三次业务调用均经过实际的前端代理、后端鉴权及 Agent 服务，没有用规则回退替代模型成功。

成功报告中的工单为 `39`，审批动作为 `5`，Agent 运行记录为 `2 / 3 / 4`。报告使用 UTC 时间，对应北京时间 2026-09-28 17:08:56 至 17:09:01。

1. 员工通过真实 Cookie 会话创建 VPN 问题工单，状态为 `NEW`。
2. 处理人调用分类、处理建议、引用回复三个模型接口。三次响应均为 `ok=true`、`source=DEEPSEEK`；回复包含知识引用。
3. 将模型实际返回的 `suggestedAction` 提交为待审批动作。此时工单仍为 `NEW`，证明生成建议没有直接执行分流。
4. 员工尝试批准返回 HTTP 403；管理员批准后动作变为 `APPROVED`，工单变为 `ASSIGNED`。
5. 队列处理人推进至 `IN_PROGRESS`、`RESOLVED`；员工可见处理说明，并确认关闭至 `CLOSED`。审计中核对了三次后续状态变化。

“VPN 排障成功”是合成演示中的处理说明，本次没有修复真实 VPN 故障。此流程证明接口集成、状态与权限边界成立，不证明所有工单场景或模型输出质量均已通过验收。

机器可读记录：[DeepSeek 工单闭环](evidence/2026-09-28-deepseek-ticket-flow.json)、[知识 HTTP 场景](evidence/2026-09-28-knowledge-http.json)、[发布事件回放](evidence/2026-09-28-sse-publish-replay.json)。仅保留合成业务数据，测试登录凭据和本机文件路径已排除。

## 复现

在明确隔离的 `agentdesk_test` 环境中，先运行知识 HTTP 验证脚本生成随机测试账号，再将其生成的本地 fixture 文件交给模型流程脚本：

```powershell
python scripts/knowledge-http-verify.py `
  --base http://127.0.0.1:15179/api `
  --isolated http://127.0.0.1:18089 `
  --container agentdesk-functional-20260922-postgres `
  --keep-fixtures

python scripts/demo-workflow-verify.py `
  --base http://127.0.0.1:15179/api `
  --fixtures verification-output/knowledge-http-fixtures-<run-tag>.json `
  --provider deepseek `
  --output verification-output/deepseek-ticket-flow-<new-run-tag>.json
```

第二个脚本会实际请求付费模型，创建并保留合成工单，不删除已有数据，也不覆盖既有结果文件。需要先配置可用供应商及已发布、员工有权访问的 VPN 排障知识；不是空知识库的通用验收。

后端测试需设置 `AGENTDESK_TEST_JDBC`、`AGENTDESK_TEST_USER`、`AGENTDESK_TEST_PASSWORD`，然后在 `backend` 运行 `mvn test`；未设置时 11 项真实数据库测试会跳过。本次首次无数据库配置的打包出现了 11 项跳过，随后在数据库就绪后重新运行，最终结果为 55 项全部执行并通过。

## 已解决的问题与未完成事项

本轮先恢复了 Docker 运行环境。首次应用内模型测试因临时环境文件将多个变量拼到同一行而失败；按独立行重建配置并重新创建 Agent 容器后，真实模型完整流程通过。失败记录保留在本地，成功报告没有覆盖它。

适合面试讲解的代码问题是“新知识稿影响旧发布版和原件读取”。本次同步的实现用 `source_import_item_id` 将原件与具体版本绑定，让员工只能读取当前已发布版本，并让审核请求携带已查看的版本。它避免审核期间草稿或最新上传原件提前暴露，也能拒绝过期审核。相关实现见 `KnowledgeService`、`ImportService`、V17 迁移和 `KnowledgeLifecycleIntegrationTest`；11 项数据库测试与真实 HTTP 流程共同提供证据。

**本次没有完成录屏文件。** 已定位并启动本地 oCam，但界面自动化工具无法可靠确认当前 Windows 浏览器 URL，停止了本轮 Computer Use。此后没有继续操作浏览器或录屏，也没有把 HTTP 测试报告当作视频。

仍未覆盖正式生产部署、HTTPS、备份恢复、长期负载、多实例一致性、真实企业资料、完整 Prompt 对照评估与人工质量评分。本记录适合作为课程/面试的运行证据，不能宣称企业可直接无条件上线。
