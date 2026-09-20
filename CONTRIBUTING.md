# Contributing

感谢关注 AgentDesk。这个仓库主要用于展示企业应用、RAG 治理和全栈工程实践，欢迎提交能复现问题的 issue 或小范围、可解释的改进。

## 提交前检查

\`\`\`powershell
cd backend; mvn test
cd agent; python -m pytest
cd frontend; npm test; npm run build
\`\`\`

如果改动了数据库、权限或 SSE，请同时更新对应的迁移、OpenAPI、测试和文档。提交描述请说明：

- 背景和用户场景；
- 改动范围；
- 权限/数据/兼容性影响；
- 执行过的验证命令和结果。

不要提交 \`.env\`、真实 API Key、生产数据、日志、构建产物或包含个人信息的截图。
