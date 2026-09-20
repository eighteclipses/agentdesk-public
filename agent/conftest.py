"""离线确定性：即使本机配置了真实 Key，单测也只覆盖规则兜底与纯函数路径。"""
import os

for _var in ("GLM_API_KEY", "DEEPSEEK_API_KEY", "LLM_PROVIDER"):
    os.environ.pop(_var, None)
# app.main 启动时要求内部令牌必须配置（fail-closed），测试环境固定一个测试令牌
os.environ["AGENT_SERVICE_TOKEN"] = "test-agent-token"
