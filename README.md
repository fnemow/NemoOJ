# NemoOJ · nemo的算法小站

NemoOJ 是一个支持 Java 8、Python 3.12 和 C++17 的在线算法练习系统，提供 ACM / 核心代码模式、隔离判题，以及通过模型 API 提供的 AI 辅助学习能力。

## 功能

- 题库浏览、标签筛选、提交贡献日历与代码编辑器。
- 三语言判题、编译与运行诊断、资源消耗记录和提交进度。
- AI 代码分析、刷题计划、私人题目生成与沙箱验证。
- 网站答疑悬浮助手，以及管理端反馈处理和题目维护。
- 用户角色、VIP 有效期、邀请码和可配置的注册开关。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `nemo-frontend/` | Vue 3 / TypeScript 前端 |
| `OJ-backend-microservice/` | Java / Spring Boot 微服务 |
| `nemo-sandbox/` | Docker 隔离执行沙箱 |
| `nemo-agent/` | Python / LangChain / LangGraph Agent |
| `deploy/` | Compose、数据库迁移、部署与运维工具 |
| `record/tutorials/` | Agent 和工作流教程及练习 |
| `doc/`、`record/introduction/` | 项目设计文档 |

## 启动与部署

1. 阅读 [部署说明](deploy/DEPLOY.md)，根据目标环境选择 Compose 配置。
2. 从 `deploy/.env.example` 复制本地配置并填写自己的数据库、消息队列和内部认证参数；AI 服务配置参考 `deploy/.env.agent.example`。
3. 按部署文档准备数据库迁移和固定版本的语言运行时镜像，再启动所需服务。
4. 前端开发说明见 [前端 README](nemo-frontend/README.md)。

PowerShell 复制示例：

```powershell
Copy-Item deploy/.env.example deploy/.env
```

数据库密码、模型 API Key、SMTP 授权码和私钥必须只保存在本地受限配置中，不能提交到 Git。

## Agent 测评

参见 [业务测评说明](nemo-agent/evaluation/BUSINESS_EVALUATION.md)。离线测评不需要模型密钥；真实模型测评需要显式配置模型并设置调用和费用上限。离线 Mock 结果不代表真实模型效果。

## 发布内容说明

本仓库从当前开发工作区制作源码快照，未导入本地开发历史。真实环境配置、数据库和 Cookie、运行验收记录、构建压缩包、缓存与虚拟环境均不包含在公开版本中。

部署工具中可能包含特定环境的路径或主机参数，使用前请按自己的环境检查。仓库上传不代表所有 CI 或部署验收已经通过；请以实际运行结果为准。
