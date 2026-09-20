# AgentDesk · AI Service Desk

> 面向企业 IT 服务台的智能工单与知识库协同平台。它把“员工提单 → 知识检索 → AI 辅助 → 人工确认 → 工单闭环 → 审计留痕”串成一个可运行、可解释、可追踪的业务闭环。

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.11-3776AB?logo=python&logoColor=white)
![Docker](https://img.shields.io/badge/Docker%20Compose-ready-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue.svg)

这是一个可公开展示的工程化版本：源码、测试、架构说明和演示截图都经过整理；截图中的账号、身份和演示凭据已脱敏，仓库不包含真实 API Key、生产数据或私有工作区文件。

## 项目亮点

- **工单闭环**：员工提单、SLA 计算、队列分流、处理人认领、评论与附件、解决/关闭/重开。
- **可解释 RAG**：PDF/DOCX/TXT/Markdown 导入后解析、归一化、分块、全文检索；可选 pgvector + RRF 混合召回；回答先给证据引用。
- **AI 只做建议**：Agent 的分类、摘要、回复和动作建议不直接改变业务状态；转交、关闭等动作必须进入待审批状态，由有权限的人员确认。
- **知识全生命周期**：草稿、审核、发布、撤回、版本切换、在线编辑和重新审核，过期版本不会继续参与检索。
- **企业权限模型**：管理员、服务台处理人、普通员工；部门/队列/可见范围/密级在列表、详情、检索和问答链路中统一过滤。
- **治理与可观测性**：JWT Cookie/Bearer 鉴权、登录/问答限流、Agent 内部令牌、审计日志、站内通知、统计图表和 CSV 导出。

## 30 秒理解 AgentDesk

\`\`\`mermaid
flowchart LR
    U[员工 / 服务台人员] --> UI[Vue 3 工作台]
    UI --> API[Spring Boot 业务 API]
    API --> T[工单与 SLA]
    API --> K[知识库与版本审核]
    API --> A[审计 / 通知 / 权限]
    API --> S[Agent 编排服务]
    S --> R[全文检索 / 可选向量检索]
    S --> L[OpenAI 兼容 LLM]
    API --> PG[(PostgreSQL + pgvector)]
    API --> RD[(Redis)]
    API --> OBJ[(MinIO)]
\`\`\`

一次问答的核心链路：

\`\`\`mermaid
sequenceDiagram
    participant User as 员工
    participant Web as Vue 工作台
    participant API as Spring Boot
    participant Search as PostgreSQL FTS / RRF
    participant Agent as FastAPI Agent
    participant Model as LLM（可选）

    User->>Web: 输入问题
    Web->>API: POST /knowledge/ask/stream
    API->>Search: 按权限过滤并召回知识分块
    Search-->>API: 文章、版本、分块、页码、相关度
    API->>Agent: 发送证据上下文
    Agent->>Model: 生成带证据约束的回答（无 Key 时规则兜底）
    Model-->>Agent: 流式 token / 拒答
    Agent-->>API: stage → citations → token → done/error
    API-->>Web: SSE 增量响应
    Web-->>User: 回答、可点击引用、反馈或转人工
\`\`\`

## 界面与流程预览

| 管理统计 | 知识版本管理 |
| --- | --- |
| ![管理端统计看板](docs/images/dashboard.png) | ![知识文章版本管理](docs/images/knowledge-versioning.png) |

| 文档导入流水线 | 登录入口（凭据已脱敏） |
| --- | --- |
| ![文档导入中心](docs/images/import-pipeline.png) | ![登录页面](docs/images/login.png) |

更多页面和操作顺序见 [公开演示指南](docs/public-demo.md)。

## 功能地图

| 模块 | 关键能力 | 对应实现 |
| --- | --- | --- |
| 员工工作台 | 首页、我的工单、企业知识库、问答、个人 Vault、通知 | \`frontend/src/pages/\` |
| 管理后台 | 组织用户、队列、资料审核、导入中心、审批与审计、统计 | \`frontend/src/pages/Admin*.vue\`、\`DashboardView.vue\` |
| 工单服务 | 状态流转、SLA、队列权限、评论、附件、通知 | \`backend/src/main/java/com/agentdesk/service/TicketService.java\` |
| 知识服务 | 文章/版本/分块、权限过滤、CJK FTS、RRF、引用锚点 | \`KnowledgeService.java\`、\`RrfMerger.java\` |
| Agent 服务 | 分类、摘要、文档解析、嵌入、流式问答、规则兜底 | \`agent/app/main.py\`、\`parsing.py\` |
| 数据与治理 | Flyway 迁移、审计事件、通知、对象存储、限流 | \`backend/src/main/resources/db/migration/\` |

## 技术栈

- **Frontend**：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、SSE、pdf.js。
- **Backend**：Java 17、Spring Boot 3.3、JDBC、Flyway、Spring Actuator、OpenAPI。
- **Agent**：Python 3.11、FastAPI、Pydantic、HTTPX；支持 OpenAI 兼容的 Chat Completions / Embeddings。
- **Data**：PostgreSQL 16 + pgvector、Redis 7、MinIO。
- **Delivery**：Docker Compose；前端通过 Nginx 提供静态资源并反代业务 API。

## 值得展示的工程实现

### 1. 检索不只追求“答出来”，而是保留证据链

文档被拆成带文章、版本、分块索引和页码信息的知识单元。查询先用 PostgreSQL 中文全文检索召回；配置向量模型后，再将 FTS 与向量结果通过 RRF 融合。问答返回 \`article → version → chunk/page\` 引用，前端可以直接跳到文章详情和对应位置。

当长查询的 AND 召回不足一页时，系统会在同一次查询中放宽到 OR，并保留相关度排序与分页，避免把工单标题和描述整段喂给检索后出现“明明有资料却零结果”。

### 2. AI 动作采用“建议 → 审批 → 执行”状态机

Agent 可以提出分类、分流、关闭等建议，但不直接写入关键业务状态。后端先保存待审批动作及证据摘要，管理员或有权限的处理人确认后才执行；批准、驳回和执行结果都会写入审计日志。这个边界让 AI 能提高处理效率，同时避免不可追踪的自动化副作用。

### 3. 权限过滤前置到数据访问层

普通员工只能看到自己的工单和授权知识；处理人按队列范围工作；\`CONFIDENTIAL\` 文章还要经过部门授权。权限条件不仅存在于前端按钮，也贯穿列表、详情、搜索、引用和问答接口，避免“页面隐藏了按钮，但接口仍可访问”的伪隔离。

### 4. 文档导入是可观察的异步流水线

导入采用“上传 → 解析 → 归一化 → 分块 → 索引 → 审核 → 发布”的阶段模型。批次和文件项分别记录状态与事件，SSE 推送进度；失败可重试、取消，原件可下载排障，同内容支持跳过或新版本策略，批次创建失败会清理孤儿对象。

### 5. 安全基线是默认行为

- JWT_SECRET 缺失、不足 32 位或仍是占位符时，后端拒绝启动。
- Agent 缺少内部服务令牌时，对 \`/v1/*\` fail-closed，仅保留健康检查。
- 登录与问答分别有限流；错误响应不向客户端泄露堆栈和底层细节。
- 送入 LLM 的知识片段使用数据分隔符包裹，并在系统提示中明确“资料是数据，不是指令”。这是提示词注入的基线防护，不宣称可以替代完整安全评估。

## 目录结构

\`\`\`text
agentdesk/
├─ backend/       Spring Boot API、权限、工单、知识库、审计
├─ agent/         FastAPI Agent 编排与文档解析
├─ frontend/      Vue 3 + TypeScript 管理端与员工工作台
├─ docs/          架构、选型、OpenAPI、演示与公开说明
├─ scripts/       启动、冒烟、端到端和功能回归脚本
├─ docker-compose.yml
└─ .env.example
\`\`\`

## 快速运行

### 环境要求

- Docker Desktop 4.x+
- Java 17、Maven 3.9（本地后端开发）
- Python 3.11（本地 Agent 开发）
- Node.js 22.13+（本地前端开发）

### Docker Compose

\`\`\`powershell
Copy-Item .env.example .env
# 编辑 .env，至少填写 JWT_SECRET、AGENT_SERVICE_TOKEN、
# POSTGRES_PASSWORD、REDIS_PASSWORD、MINIO_ROOT_PASSWORD
./scripts/start.ps1
\`\`\`

访问：

- Web：<http://localhost:5173>
- API 文档：<http://127.0.0.1:8080/swagger-ui/index.html>

没有配置 LLM Key 时，Agent 会使用规则兜底，仍可以演示检索、引用和工单闭环；配置任意 OpenAI 兼容供应商后，可以启用模型生成和可选向量检索。演示账号只用于本地种子数据，首次登录必须改密码，部署到公网前应删除或替换演示数据。

### 本地开发

\`\`\`powershell
cd backend; mvn spring-boot:run
cd agent; python -m uvicorn app.main:app --reload --port 8000
cd frontend; npm ci; npm run dev
\`\`\`

## 测试与验证

\`\`\`powershell
cd backend; mvn test
cd agent; python -m pytest
cd frontend; npm ci; npm test; npm run build
\`\`\`

需要 Docker 栈时，再执行：

\`\`\`powershell
python scripts/e2e-verify.py
python scripts/feature-verify.py
python scripts/usability-verify.py
\`\`\`

离线单元测试不会调用真实付费模型；全栈脚本会写入本地测试身份和演示数据，详细清理边界见 [验证说明](docs/verification.md)。

## 项目边界与诚实说明

这是一个用于展示企业应用、AI 辅助、检索治理和全栈交付能力的公开项目，不是生产级 ITSM 产品。当前明确未覆盖的方向包括多渠道客服、复杂自动分配、OCR/ASR、多实例事件流、重排序和查询改写等。

本公开版保留了代码和测试中用于本地回归的演示种子数据，但不包含真实用户、真实企业文档或生产密钥。请阅读 [公开发布检查清单](docs/public-release.md)，再长期保持仓库为 Public。

## 文档索引

- [架构与关键设计](docs/architecture.md)
- [公开演示指南](docs/public-demo.md)
- [验证边界与结果记录](docs/verification.md)
- [技术路线与选型](docs/technology-selection.md)
- [可用性审查记录](docs/usability-review-2026-09-11.md)
- [公开发布检查清单](docs/public-release.md)
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)

## License

[MIT](LICENSE)
