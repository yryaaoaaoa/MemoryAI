基于RAG+Agent架构的java技术面试练习平台，覆盖“文档摄入→知识检索→AI对话→自动出题→模拟面试”完整闭环。

支持学习笔记、会话登录续期及可配置的 RAG 功能。前端位于 `ai-job-prep-frontend`，Java 17 后端位于 `ai-rag-agent`。

部署采用 Docker Compose。推送 `master` 的应用或部署代码后，由 [GitHub Actions](https://github.com/yryaaoaaoa/MemoryAI/actions/workflows/deploy.yml) 自动测试、构建并发布，也支持手动触发。

- [Docker 部署与更新](deploy/docker/README.md)
- [数据库初始化与迁移](docs/database-migration.md)
- [首次服务器部署验证](docs/docker-deployment-2026-11-16.md)
