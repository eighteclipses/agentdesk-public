# AgentDesk 架构与关键设计

## 1. 分层结构

\`\`\`mermaid
flowchart TB
    subgraph Client[客户端层]
        Employee[员工工作台]
        Operator[服务台工作台]
        Admin[管理后台]
    end

    subgraph App[应用层]
        Vue[Vue 3 / TypeScript]
        API[Spring Boot API]
        Agent[FastAPI Agent]
    end

    subgraph Domain[领域能力]
        Ticket[工单与 SLA]
        Knowledge[知识库与版本]
        Workflow[审批与状态机]
        Governance[权限 / 审计 / 通知]
    end

    subgraph Infra[基础设施]
        Postgres[(PostgreSQL + pgvector)]
        Redis[(Redis)]
        Minio[(MinIO)]
        LLM[OpenAI 兼容 LLM]
    end

    Employee --> Vue
    Operator --> Vue
    Admin --> Vue
    Vue --> API
    API --> Ticket
    API --> Knowledge
    API --> Workflow
    API --> Governance
    API --> Agent
    Ticket --> Postgres
    Knowledge --> Postgres
    Governance --> Postgres
    API --> Redis
    API --> Minio
    Agent --> LLM
    Agent --> Postgres
\`\`\`

## 2. 业务闭环

\`\`\`mermaid
stateDiagram-v2
    [*] --> NEW: 员工提交工单
    NEW --> TRIAGED: 分类 / 分流建议
    TRIAGED --> ASSIGNED: 进入处理队列
    ASSIGNED --> IN_PROGRESS: 处理人认领
    IN_PROGRESS --> PENDING_USER: 等待员工补充
    PENDING_USER --> IN_PROGRESS: 员工回复
    IN_PROGRESS --> RESOLVED: 处理人解决
    RESOLVED --> CLOSED: 员工确认关闭
    RESOLVED --> REOPENED: 员工确认问题仍在
    REOPENED --> IN_PROGRESS
    NEW --> CANCELLED: 有权限用户取消
    CLOSED --> [*]
    CANCELLED --> [*]
\`\`\`

AI 建议不会直接跳过人工确认；关键动作会进入待审批状态，审批后才执行。

## 3. 知识入库与检索

\`\`\`mermaid
flowchart LR
    File[PDF / DOCX / TXT / Markdown] --> Upload[批次上传]
    Upload --> Parse[Agent 解析]
    Parse --> Normalize[归一化为 Markdown]
    Normalize --> Chunk[按标题 / 行边界分块]
    Chunk --> FTS[PostgreSQL CJK FTS]
    Chunk --> Vector[可选 Embedding]
    FTS --> RRF[RRF 融合]
    Vector --> RRF
    RRF --> Review[文章审核]
    Review --> Publish[发布版本]
    Publish --> Cite[回答引用：文章 / 版本 / 分块 / 页码]
\`\`\`

设计要点：

1. 原始文件放在 MinIO，文章正文、版本、分块和索引元数据放在 PostgreSQL。
2. 文章先经过审核，只有发布版本进入员工检索。
3. 每个检索结果带来源定位，问答流先发送引用再发送回答 token。
4. 向量服务不可用时降级到全文检索，避免可选能力阻塞主流程。

## 4. 权限边界

| 角色 | 可见范围 | 关键操作 |
| --- | --- | --- |
| \`ADMIN\` | 全组织 | 用户/部门/队列、知识审核、审批、审计、统计 |
| \`AGENT\` | 被授权队列 | 认领、处理、回复、生成 AI 建议、提交动作 |
| \`EMPLOYEE\` | 自己的工单 + 授权知识 | 提单、补充信息、问答、确认关闭/重开 |

权限由后端查询条件决定，前端只负责呈现。知识检索同时检查文章可见性、部门授权和密级；工单列表、详情、评论、附件和状态流转使用同一套身份上下文。

## 5. 可观测性与故障边界

- 每次导入阶段、知识发布、Agent 运行、检索引用、工单状态变化和审批结论都可落审计事件。
- SSE 事件拥有明确的 \`stage\`、\`citations\`、\`token\`、\`done\`、\`error\` 边界，前端可以停止、重试和清理半截状态。
- 对象存储、数据库和 Agent 通过 Compose 健康检查编排；服务端只在必需的宿主机端口上暴露入口。
- 真实 LLM 是可插拔依赖；没有 Key 时使用规则兜底，测试默认不访问外部模型。
