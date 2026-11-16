# MemoryAI Docker 部署验证（2026-11-16）

部署位置：`106.55.13.138:/opt/memoryai`。访问入口：`http://106.55.13.138/`。使用当前工作区代码构建，未提交或覆盖已有工作区改动。

## 部署状态

- Compose 项目：`memoryai`；应用镜像：`memoryai-backend:20261116-01`、`memoryai-web:20261116-01`。
- Nginx 1.28、后端、MySQL 8.0.41、Redis 7.4、Elasticsearch 8.17.10 共 5 个常驻容器均为 healthy；ES 初始化容器以 0 正常退出。
- Nginx 将宿主机 80 端口用于静态页面和 `/api/` 反向代理。配置支持前端路由刷新、60 MB 请求体和 SSE，代理缓冲已关闭。数据库、Redis、ES 和 Java 后端未发布宿主机端口。
- 使用独立 Docker 网络和 `memoryai_mysql-data`、`memoryai_redis-data`、`memoryai_es-data` 数据卷。ES 复用已有镜像，不使用博客数据卷；Myblog 仍处于停止状态。QQ Bot 和 API 继续运行。
- 从空库初始化，不迁移本机账号、笔记、知识库或其他业务数据。模型与 OCR 凭据沿用本机配置中的有效值，通过服务器权限为 600 的 `.env` 注入。
- HTTP 登录使用 `COOKIE_SECURE=false`、HttpOnly、SameSite=Lax。未配置域名或 HTTPS。

## 已完成验证

1. `mvn -B -ntp clean verify`：194 项测试通过，0 失败、0 错误、0 跳过。
2. `pnpm build`：通过；仍有现有的大文件分包和动态导入警告。
3. 确认 JAR 不包含本机 `application.yml`，应用资源中不包含本机配置的实际密码/API Key；压缩包与 Docker 上下文不包含 `.env`。
4. 上传包 SHA256 校验一致：`3c121bd435eccf84d1038360b0fe5ff44e4e9f82d0c562b90af62ecdca72b5b0`。
5. Compose 配置校验、Docker 镜像构建、容器健康检查和 `nginx -t` 均通过。
6. 在仅绑定服务器回环地址的临时入口上验证注册、登录、会话 Cookie 和 RAG 开关。
7. 上传含随机校验码的 Markdown 文档：解析成功、嵌入 API 返回、ES 入库成功。通过 Nginx 发起真实 AI 流式问答，回答包含仅存在于上传文档中的校验码；聊天消息成功持久化。
8. 从上述对话创建中文学习笔记，写入后读取一致。用 Edge 无头浏览器完成登录、笔记页面显示及直接路由刷新，无页面运行时异常。
9. 上线前删除本次测试账号、笔记、知识库、文档、切片、聊天消息及登录会话；确认业务表计数为 0，ES 文档数为 0，Redis DB size 为 0。
10. 切换 Nginx 至 `0.0.0.0:80` 后，从本机通过公网 IP 请求首页返回 HTTP 200，公共知识库接口返回成功且列表为空。

验证后服务器内存约 2.7 GiB 已用、4.8 GiB 可用，此数值仅为当时采样，不代表容量测试。

## 验证范围

本次实测了登录、学习笔记、Markdown 入库、向量检索和 AI 流式回答。未逐项实测 OCR、PDF 简历解析、模拟面试与刷题完整流程，未进行并发压测。

打开网页后自行注册新账号；注册账号默认是普通用户，未创建默认管理员。

构建与运维步骤见 [Docker 部署说明](../deploy/docker/README.md)。
