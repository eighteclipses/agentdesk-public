# 公开发布检查清单

## 已做

- [x] 从原项目当前提交创建独立公开副本，不带原 Git 历史。
- [x] \`.env\`、运行日志、个人材料、构建产物和原项目未跟踪截图没有进入公开副本。
- [x] 演示截图遮挡了账号、密码、用户身份等界面信息。
- [x] \`.env.example\` 只保留占位符，不包含真实 API Key。
- [x] README 增加架构图、业务流程图、功能地图、运行方式和验证边界。
- [x] 增加 MIT License、贡献指南和安全策略。

## 发布前人工确认

- [ ] 确认仓库中没有需要保密的课程材料、客户资料、企业文档或个人联系方式。
- [ ] 确认截图和 README 中的产品名、项目归属和署名方式符合你的意愿。
- [ ] 如果要部署到公网，删除 Flyway 种子账号或在首次启动后立刻更换密码。
- [ ] 在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置 CI 所需的最小权限；不要把 Key 写进仓库。
- [ ] 设置仓库 Topics：\`spring-boot\`、\`vue3\`、\`fastapi\`、\`rag\`、\`postgresql\`、\`docker-compose\`、\`it-service-desk\`。
- [ ] 发布后检查 GitHub 的 secret scanning、Dependabot 和 branch protection 状态。

## 明确不应上传的内容

\`\`\`text
.env
真实 LLM API Key
生产数据库导出
真实企业文档 / 工单附件
带个人姓名、手机号、邮箱、内部地址的截图
Docker volume 备份
target/、node_modules/、dist/、本地运行日志
\`\`\`

公开仓库是展示工程能力的载体，不等于可以把真实环境配置复制出去。
