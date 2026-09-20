# AgentDesk 完善方案与验收清单

> 2026-09-11 更新：当前可用性、逐项修复和验证边界以 [本轮审查](usability-review-2026-09-11.md) 为准。下方为历史方案/能力梳理，勾选不代表本轮已经通过真实端到端验收；竞品缺失标记也不应视为已证实结论。

## 〇、已落地实施状态（2026-09 代码同步）

P0 已按本方案的"完善版"落地，与代码一一对应：

- **异步导入流水线**：`import_batches/import_items/processing_events`（V7）+ 4 worker `FOR UPDATE SKIP LOCKED` + agent `/v1/parse-document`（纯解析无 LLM）+ 分块索引 + SSE 进度（断线重放）+ 单项重试/取消 + 批次批量发布；同 sha256 去重支持 skip/version 两种策略（V12）。`/api/admin/imports/*`。
- **分块检索与可点击引用**：检索单位改为 `knowledge_chunks`（V8），引用升级为 `article:{id}/version:{v}#c{chunkIndex}`（PDF 命中带页码）；`KnowledgeService.search()` 的部门参数已参数化（原字符串拼接缺陷修复）。启动回填旧版本分块。
- **文件详情页与下载**：`GET /api/knowledge/articles/{id}`、`/versions`、`/chunks`、`/file`（补 StorageService 下载链路，工单附件同步可下载）；前端 pdf.js 按页渲染 + 分块锚点定位，前端路由/pinia/页面已拆分。
- **流式问答**：agent `/v1/answer/stream`（stage→citations→token→done/error，无 Key 时规则摘要兜底），后端 `/api/knowledge/ask/stream` 透传并落会话/消息/引用（V11）；支持企业/仅个人/合并三范围；前端问答工作区含停止、反馈、引用点击、转人工建工单、保存为笔记。
- **审核状态机**：`knowledge_versions.status` 独立（DRAFT/PUBLISHED/ARCHIVED），`/review` 发布最新版本并切换 current_version_id，旧版本归档；文章五态 DRAFT/IN_REVIEW/PUBLISHED/REJECTED/ARCHIVED（V9），审核同步 import_items。
- **个人 Vault**：V10 表组 + `/api/vault/*`（notes CRUD、`[[双向链接]]` 解析与反向链接、个人笔记 FTS 检索与"个人零进入企业检索"的结构隔离、`/graph`、笔记提交审核为企业草稿带 source_note_id）。
- **治理**：新增审计动作 IMPORT_BATCH_CREATED/IMPORT_ITEM_FAILED/IMPORT_ITEM_DUPLICATED/IMPORT_ITEM_INDEXED/IMPORT_ITEM_RETRIED/KNOWLEDGE_REVIEWED/KNOWLEDGE_VERSION_PUBLISHED/NOTE_CREATED 等；nginx 已关 SSE 缓冲；compose 换 `pgvector/pgvector:pg16`；上传上限 50MB/批次 300MB。

第二轮（功能缺口补齐，基于 38 项内部排查 + 14 个同类项目对比）：

- **工单闭环补全**：请求人可确认关闭/重开已解决工单（`transition` 权限拆分）；处理工作台"标记已解决"弹窗填写原因（不再硬编码）；工单详情展示请求人/处理人/队列/优先级/SLA 倒计时（超时标红）；新增 `GET /api/tickets/{id}/assignees` 支持队列内指派处理人；工单列表按状态筛选（后端 `/api/tickets` 增加 status 参数）。
- **SLA 按优先级**：建单截止时间改为 P1=4h / P2=8h / P3=24h / P4=72h（原固定 24h）。
- **问答与知识**：会话删除（`DELETE /api/knowledge/conversations/{id}`，级联清理消息与引用）；知识库浏览页接入全文分块检索（`/knowledge/search`，命中带可点击锚点引用）。
- **管理后台补齐**：队列启停（`PATCH /admin/queues/{id}`）、部门改名、用户角色/部门编辑、重置密码入口；审计日志 CSV 导出（`/api/audit/export`，带 BOM 兼容 Excel）；导入项原件下载（任意状态，排障用）。
- **前端体验**：404 页（替代静默重定向）；所有异步操作统一错误提示与按钮加载态。

第三轮（2026-09，对比 Chatwoot/Zammad/osTicket/Peppermint 与 AnythingLLM/RAGFlow 等同类项目后补齐两个最大共性差距，详见 [`docs/comparison-similar-projects.md`](comparison-similar-projects.md)）：

- **站内通知中心**：`notifications` 表（V15）+ `NotificationService` + `/api/notifications/*`（分页/未读过滤/已读/全部已读/未读数）；触发点覆盖工单流转与指派（请求人/处理人各归其位、操作者不自发）、SLA 首次违约（按 ref 去重防调度器重复打扰）、Agent 动作审批结论（提议坐席）、知识审核结果（文章作者）、账号开通（新用户）、Vault 提交审核（全体管理员）。前端顶栏铃铛 + 未读徽标（30s 轮询 + 通知页操作即时同步）+ 下拉面板 + `/workspace/notifications` 全量历史页（分页/只看未读/全部已读）。
- **统计图表仪表盘**：`GET /api/dashboard/stats`（管理员）：状态/优先级分布、近 N 天新建趋势（generate_series 补零）、SLA 达成率（V15 新增 `tickets.resolved_at`，首次解决时间 vs 截止时间，重开清空）、问答规模/反馈/引用点击率；前端零依赖 SVG 图表（折线 + 分色条形）。
- **顺手修复**：登录限流器超过 1 万桶时由"整体清空（可被洪水 key 重置他人限流状态）"改为"先淘汰过期桶、再按最久未使用摊销裁剪到 8k"；`scripts/feature-verify.py` 纳入文档（README 演示密码说明与脚本对齐）。

知识库专项（2026-09 第二轮，聚焦企业知识库，对比 AnythingLLM/RAGFlow/FastGPT/Onyx 的检索类差距）：

- **检索质量**：`search` 重写为两级召回——AND 精确优先，候选不足一页时用新 SQL 函数 `agentdesk_fts_query_tokens_or`（V16）放宽为 OR 并按 ts_rank 排序，修复长查询（工单标题+描述直接喂检索）几乎必然空结果的召回断崖；检索分页（page/size，上限 50）；窗口函数按文章去重（每篇最多 2 条）；浏览页检索结果展示相关度得分。
- **向量混合检索**：激活 V8 预留的 pgvector——agent 新增 `/v1/embed`（GLM embedding-3，`AGENT_EMBED_DIMENSIONS` 默认 512 对齐 `vector(512)`，HTTP 失败/未配置返回 ok=false 不抛错）；`EmbeddingService`（后端）带 60s 熔断；导入 INDEXING 后异步向量化、手动创建文章同步向量化、启动回填存量（版本数上限 200）；查询时 FTS+向量 `RRF 融合`（`RrfMerger` 纯函数，同文章配额去重）；未配置 `AGENT_EMBED_MODEL` 时整体降级纯 FTS。
- **知识全生命周期**：新增 `PUT /api/knowledge/articles/{id}`（管理员）——内容变更生成新版本（vMax+1，DRAFT）重新分块并回到 IN_REVIEW，发布版本编辑期间不下线，审核通过才切换 current_version_id；手动创建支持 `sensitivity`（PUBLIC/INTERNAL/CONFIDENTIAL）——激活 V9 以来的密级检索过滤死旋钮；前端管理台"新建文章"表单 + 状态筛选、文章详情"编辑"面板（Markdown+元数据）。
- **体验与缺陷**：浏览页分类/标签筛选（新增 `/knowledge/categories`、`/knowledge/tags` 字典端点，列表与检索均可过滤）；导入中心渲染处理事件时间线（原收集未展示）；Chunker 超长段落改按行边界切（原硬字符截断）；docx 解析按文档顺序保留表格（原只读 paragraphs 丢表格，转 Markdown 管道表）；导入 version 去重策略沿用本批次可见性/部门授权（原继承旧文章设置）；`knowledge_feedback.message_id` 改 ON DELETE SET NULL（删除会话后反馈保留，对齐注释语义）。

待后续阶段：重排序/查询改写（混合检索之上）、预设回复/宏、CSAT 满意度、自动分配策略（负载/轮转）、分块重叠与语义切分、角色权限矩阵、Obsidian 插件同步（P2）、OCR/ASR（P2）、多实例 Redis Stream（P3）。

---

## 一、当前版本评价

项目已经不是单纯的 CRUD：登录与角色、部门隔离、工单、队列、知识检索、Agent 分析、人工审批、文档导入、SLA 检查和审计链路都已经具备。作为课程设计，技术路线和业务主题是成立的，适合展示“企业应用 + AI 辅助 + 权限治理”的综合能力。

本次完善重点补齐了三个会影响现场演示的断点：

- AGENT 角色增加处理工作台，可以对授权队列工单进行分类、分流建议、状态流转和引用回复。
- 管理员的资料与知识页面增加文章审核和发布，文档导入后可以形成真正可检索的知识。
- 工单分配和 Agent 动作增加服务端校验，处理人必须属于目标队列且账号处于激活状态；组织、用户、队列管理动作写入审计。

## 二、建议的业务闭环

```text
员工注册/登录
    -> 提交工单（分类、优先级、SLA 截止时间）
    -> Agent 只读分析（分类、队列、知识证据）
    -> 处理人确认/生成待审批动作
    -> 管理员或有权限处理人批准
    -> 队列处理、评论、附件、状态流转
    -> 解决/关闭并记录原因
    -> 审计日志、SLA、知识沉淀
```

答辩时要强调：模型只提出建议，不直接修改业务数据；所有重要写入动作都有人工确认和审计记录。

## 三、建议继续完善的功能

### P0：演示前必须补齐

1. **工单详情页**：展示完整描述、请求人、处理人、时间线、评论、附件和 SLA 倒计时。员工可补充评论和附件，处理人可回复并填写解决原因。
2. **知识审核详情**：管理员可查看解析后的 Markdown、摘要、标签和部门可见范围，再执行发布；支持新版本替换旧版本，保留历史版本。
3. **演示初始化**：增加“一键重置演示数据”脚本或独立演示数据库卷，避免上一次演示修改密码后影响下一次答辩。
4. **错误与加载状态**：所有按钮统一显示提交中、成功、失败原因；Agent 或文档服务不可用时，页面明确显示规则兜底，而不是静默失败。

### P1：让系统更贴近企业使用

1. 工单列表增加状态、优先级、队列、创建时间筛选和分页；管理员增加按部门、队列、SLA 超时筛选。
2. SLA 按优先级配置（P1/P2/P3/P4），支持暂停计时（等待用户）和恢复计时，而不是所有工单固定 24 小时。
3. 增加站内通知：新工单分派、待审批动作、即将超时和解决结果通知相关人员。
4. 增加知识文章编辑、归档、撤回发布、标签和部门授权维护；员工问答显示“无证据拒答”和转人工入口。
5. 管理员增加角色权限矩阵，而不是只用 ADMIN、AGENT、EMPLOYEE 三个固定角色。

### P2：生产化和扩展

1. 文档解析和 Agent 调用改为异步任务队列，前端轮询或 WebSocket 查看进度，避免上传大文件时 HTTP 请求长时间阻塞。
2. 检索从 PostgreSQL 全文检索扩展到 pgvector 或 OpenSearch，并记录召回耗时、命中率和人工采纳率。
3. 增加 Prometheus 指标、结构化日志、备份恢复和对象存储生命周期策略。
4. 生产环境启用 HTTPS、Secure/SameSite Cookie、CSRF 防护、登录限流；禁止使用默认 JWT、MinIO 和数据库密码。

## 四、答辩演示路径

1. 用 `zhangsan` 登录，提交“VPN 认证超时”工单，展示自动进入 NETWORK 队列和 SLA 截止时间。
2. 用 `lisi` 登录“处理工作台”，只看到自己被授权队列的工单；点击 Agent 分析和带引用回复。
3. 点击分流建议，生成 `TRANSFER` 待审批动作；用 `admin` 登录，在“审批与审计”批准，展示工单状态、队列和审计变化。
4. 用员工账号提问“VPN 认证超时如何处理”，展示回答、置信度、文章 ID/版本引用；再问一个知识库没有的问题，展示拒答。
5. 管理员上传 TXT/Markdown/PDF/DOCX，展示导入状态和解析摘要，在“知识文章审核”点击发布，再用员工账号检索新文章。
6. 演示停用一个账号或移除队列成员，再验证该账号无法登录或看不到该队列工单，说明权限不是前端假隐藏。

## 五、验收标准

- 普通员工只能读取自己的工单和有权限的已发布知识。
- 处理人只能读取自己所属队列的工单，不能把工单分配给非本队列或未激活账号。
- Agent 无证据时拒答；DeepSeek 不可用时可解释地回退到规则结果。
- 任何状态流转、Agent 动作、用户/部门/队列管理和知识发布都可在审计页追溯。
- 文档导入失败不会生成可检索的半成品文章，并能看到失败原因。
- `mvn test`、Agent `pytest`、前端 `npm run build` 和冒烟脚本均通过。

